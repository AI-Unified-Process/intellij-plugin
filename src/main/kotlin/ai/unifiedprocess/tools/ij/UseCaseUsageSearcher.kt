package ai.unifiedprocess.tools.ij

import com.intellij.find.usages.api.PsiUsage
import com.intellij.find.usages.api.Usage
import com.intellij.find.usages.api.UsageSearchParameters
import com.intellij.find.usages.api.UsageSearcher
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.AbstractQuery
import com.intellij.util.Processor
import com.intellij.util.Query

/**
 * Returns every Java/Markdown site that participates in the same Use Case
 * relation as the cursor target. Handles `UseCaseSymbol` (UC-XXX),
 * `BusinessRuleSymbol` (UC-XXX × BR-YYY) and `ScenarioSymbol`
 * (UC-XXX × {Main | Aₙ}). Site list, in order:
 *   - Java `@UseCase(...)` literals matching the symbol
 *   - Markdown spec lines/headings matching the symbol
 *
 * Note: when invoked from a Markdown spec, the spec's own line appears as a
 * self-reference in the result. There is no API to detect the cursor's source
 * site from a `UsageSearcher`, and marking the entry as a declaration causes
 * the framework to drop it entirely — losing the Java→spec link. Living with
 * the one-row self-reference is the trade-off that keeps both directions
 * working in Find Usages.
 */
class UseCaseUsageSearcher : UsageSearcher {

    override fun collectSearchRequests(
        parameters: UsageSearchParameters,
    ): Collection<Query<out Usage>> {
        val target = parameters.target as? UseCaseRelatedSymbol ?: return emptyList()
        val project = parameters.project
        return listOf(ReadActionUsageQuery { collectUsages(project, target) })
    }

    private fun collectUsages(project: Project, target: UseCaseRelatedSymbol): List<Usage> =
        buildList {
            addAll(javaUsages(project, target))
            addAll(markdownUsages(project, target))
        }

    /**
     * Runs the search and downstream consumer (which converts each `Usage`
     * into a `UsageInfo` via smart-pointer creation) inside a single read
     * action. The eager `runReadAction` in `collectSearchRequests` is not
     * enough — the framework iterates the returned query later on a pooled
     * thread without read access, and `PsiUsage2UsageInfo.<init>` then needs
     * one to build smart pointers.
     */
    private class ReadActionUsageQuery(
        private val supplier: () -> List<Usage>,
    ) : AbstractQuery<Usage>() {
        override fun processResults(consumer: Processor<in Usage>): Boolean =
            ReadAction.computeBlocking<Boolean, RuntimeException> {
                supplier().all(consumer::process)
            }
    }

    private fun javaUsages(project: Project, target: UseCaseRelatedSymbol): List<Usage> {
        val annotationClass = findUseCaseAnnotationClass(project) ?: return emptyList()
        val fqn = annotationClass.qualifiedName ?: return emptyList()
        val annotated = AnnotatedElementsSearch
            .searchPsiMethods(annotationClass, GlobalSearchScope.projectScope(project))
            .findAll()

        val result = mutableListOf<Usage>()
        for (method in annotated) {
            val ann = method.getAnnotation(fqn) ?: continue
            val ucId = stringAttr(ann, "id") ?: continue
            if (ucId != target.useCaseId) continue
            collectAnnotationLiterals(ann, target, result)
        }
        return result
    }

    private fun collectAnnotationLiterals(
        ann: PsiAnnotation,
        target: UseCaseRelatedSymbol,
        out: MutableList<Usage>,
    ) {
        var sawScenarioAttr = false
        for (pair in ann.parameterList.attributes) {
            when (pair.name ?: "value") {
                "id" -> if (target is UseCaseSymbol) {
                    (pair.value as? PsiLiteralExpression)?.let { out += it.asValueUsage() }
                }
                "scenario" -> {
                    sawScenarioAttr = true
                    if (target is ScenarioSymbol) {
                        val literal = pair.value as? PsiLiteralExpression ?: continue
                        val scenario = literal.value as? String
                        val code = scenario?.let(::extractScenarioCode)
                        if (code == target.scenarioCode) out += literal.asValueUsage()
                    }
                }
                "businessRules" -> if (target is BusinessRuleSymbol) {
                    val value = pair.value ?: continue
                    val literals = when (value) {
                        is PsiLiteralExpression -> listOf(value)
                        else -> PsiTreeUtil.findChildrenOfType(value, PsiLiteralExpression::class.java).toList()
                    }
                    for (literal in literals) {
                        val br = literal.value as? String ?: continue
                        if (br == target.brId) out += literal.asValueUsage()
                    }
                }
            }
        }

        // Implicit Main Success Scenario: a method with no explicit `scenario`
        // attribute defaults to the main scenario, so anchor the usage on the
        // annotation's `UseCase` identifier (no literal exists to point at).
        if (target is ScenarioSymbol && target.scenarioCode == null && !sawScenarioAttr) {
            ann.nameReferenceElement?.let { ref ->
                out += PsiUsage.textUsage(ref.containingFile, ref.textRange)
            }
        }
    }

    private fun markdownUsages(project: Project, target: UseCaseRelatedSymbol): List<Usage> {
        val specs = UseCaseIndex.findSpecFiles(project, target.useCaseId)
        val psiManager = PsiManager.getInstance(project)
        val result = mutableListOf<Usage>()
        for (vfile in specs) {
            val psiFile = psiManager.findFile(vfile) ?: continue
            collectMarkdownSites(psiFile, target, result)
        }
        return result
    }

    private fun collectMarkdownSites(
        psiFile: PsiFile,
        target: UseCaseRelatedSymbol,
        out: MutableList<Usage>,
    ) {
        val document = PsiDocumentManager.getInstance(psiFile.project).getDocument(psiFile) ?: return
        val text = document.charsSequence
        val lineCount = document.lineCount
        for (i in 0 until lineCount) {
            val start = document.getLineStartOffset(i)
            val end = document.getLineEndOffset(i)
            val line = text.subSequence(start, end).toString()

            when (target) {
                is UseCaseSymbol -> {
                    USE_CASE_ID_LINE.find(line)?.let { m ->
                        if (m.groupValues[1] == target.useCaseId) {
                            val r = m.groups[1]!!.range
                            out += PsiUsage.textUsage(psiFile, TextRange(start + r.first, start + r.last + 1))
                        }
                    }
                    if (TITLE_HEADING.containsMatchIn(line)) {
                        val titleStart = line.indexOf("# ") + 2
                        if (titleStart in 0..line.length) {
                            out += PsiUsage.textUsage(psiFile, TextRange(start + titleStart, end))
                        }
                    }
                }
                is BusinessRuleSymbol -> {
                    BR_HEADING.find(line)?.let { m ->
                        if (m.groupValues[1] == target.brId) {
                            val r = m.groups[1]!!.range
                            out += PsiUsage.textUsage(psiFile, TextRange(start + r.first, start + r.last + 1))
                        }
                    }
                }
                is ScenarioSymbol -> {
                    if (target.scenarioCode == null) {
                        if (MAIN_SCENARIO_HEADING.containsMatchIn(line)) {
                            val phrase = "Main Success Scenario"
                            val phraseStart = line.indexOf(phrase)
                            if (phraseStart >= 0) {
                                out += PsiUsage.textUsage(
                                    psiFile,
                                    TextRange(start + phraseStart, start + phraseStart + phrase.length),
                                )
                            }
                        }
                    } else {
                        ALT_FLOW_HEADING.find(line)?.let { m ->
                            if (m.groupValues[1] == target.scenarioCode) {
                                val r = m.groups[1]!!.range
                                out += PsiUsage.textUsage(
                                    psiFile,
                                    TextRange(start + r.first, start + r.last + 1),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun PsiLiteralExpression.asValueUsage(): Usage {
        val r = textRange
        // Skip the surrounding quotes.
        val rangeForUsage = if (r.length >= 2) TextRange(r.startOffset + 1, r.endOffset - 1) else r
        return PsiUsage.textUsage(containingFile, rangeForUsage)
    }

    private fun stringAttr(annotation: PsiAnnotation, name: String): String? =
        ((annotation.findAttributeValue(name) as? PsiLiteralExpression)?.value) as? String

    private fun extractScenarioCode(scenario: String): String? {
        if (scenario.isBlank() || scenario.equals("Main Success Scenario", ignoreCase = true)) {
            return null
        }
        val colon = scenario.indexOf(':')
        val prefix = (if (colon >= 0) scenario.substring(0, colon) else scenario).trim()
        return prefix.takeIf { it.matches(SCENARIO_PREFIX) }
    }

    private fun findUseCaseAnnotationClass(project: Project) =
        PsiShortNamesCache.getInstance(project)
            .getClassesByName("UseCase", GlobalSearchScope.allScope(project))
            .firstOrNull { it.isAnnotationType }

    private companion object {
        val USE_CASE_ID_LINE = Regex("""\*\*Use Case ID:\*\*\s*(UC-[A-Za-z0-9_-]+)""")
        val BR_HEADING = Regex("""^#{1,6}\s+(BR-[A-Za-z0-9_-]+)\b""")
        val ALT_FLOW_HEADING = Regex("""^#{1,6}\s+([A-Z]\d+)\b""")
        val MAIN_SCENARIO_HEADING = Regex("""^#{1,6}\s+Main\s+Success\s+Scenario\s*$""")
        val TITLE_HEADING = Regex("""^# \S""")
        val SCENARIO_PREFIX = Regex("""[A-Z]\d+""")
    }
}
