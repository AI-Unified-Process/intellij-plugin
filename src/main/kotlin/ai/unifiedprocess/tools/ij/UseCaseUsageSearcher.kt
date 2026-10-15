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
import com.intellij.util.AbstractQuery
import com.intellij.util.Processor
import com.intellij.util.Query
import org.jetbrains.uast.UAnchorOwner
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.toUElementOfType

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

    // Explicit no-op overrides for the other two `Searcher` defaults. Without
    // these, Kotlin generates type-narrowed bridge methods that `invokespecial`
    // the `@ApiStatus.OverrideOnly` defaults — which the JetBrains plugin
    // verifier flags as override-only API call violations.
    override fun collectSearchRequest(parameters: UsageSearchParameters): Query<out Usage>? = null
    override fun collectImmediateResults(parameters: UsageSearchParameters): Collection<Usage> = emptyList()

    private fun collectUsages(project: Project, target: UseCaseRelatedSymbol): List<Usage> =
        buildList {
            addAll(annotationUsages(project, target))
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

    private fun annotationUsages(project: Project, target: UseCaseRelatedSymbol): List<Usage> {
        val annotationClass = findUseCaseAnnotationClass(project) ?: return emptyList()
        val annotated = AnnotatedElementsSearch
            .searchPsiMethods(annotationClass, GlobalSearchScope.projectScope(project))
            .findAll()

        val result = mutableListOf<Usage>()
        for (method in annotated) {
            // The search answers in Java terms, which for a test written in another language is a
            // light method whose ranges point at nothing the author can see. UAST leads back to the
            // annotation as written, so every usage below lands in real source.
            val uMethod = method.toUElementOfType<UMethod>() ?: continue
            val annotation = uMethod.uAnnotations
                .firstOrNull { UseCaseIndex.isUseCaseAnnotation(it) } ?: continue
            val ucId = UseCaseIndex.attributeString(annotation, "id") ?: continue
            if (ucId != target.useCaseId) continue
            collectAnnotationValues(annotation, target, result)
        }
        return result
    }

    private fun collectAnnotationValues(
        annotation: UAnnotation,
        target: UseCaseRelatedSymbol,
        out: MutableList<Usage>,
    ) {
        var sawScenarioAttr = false
        for (attribute in annotation.attributeValues) {
            when (attribute.name ?: "value") {
                "id" -> if (target is UseCaseSymbol) {
                    attribute.expression.sourcePsi?.let { out += it.asValueUsage() }
                }
                "scenario" -> {
                    sawScenarioAttr = true
                    if (target is ScenarioSymbol) {
                        val scenario = attribute.expression.evaluate() as? String
                        val code = scenario?.let(::extractScenarioCode)
                        if (code == target.scenarioCode) {
                            attribute.expression.sourcePsi?.let { out += it.asValueUsage() }
                        }
                    }
                }
                "businessRules" -> if (target is BusinessRuleSymbol) {
                    for (element in UseCaseIndex.arrayElements(attribute.expression)) {
                        if (element.evaluate() as? String != target.brId) continue
                        element.sourcePsi?.let { out += it.asValueUsage() }
                    }
                }
            }
        }

        // Implicit Main Success Scenario: a method with no explicit `scenario`
        // attribute defaults to the main scenario, so anchor the usage on the
        // annotation's `UseCase` identifier (no literal exists to point at).
        if (target is ScenarioSymbol && target.scenarioCode == null && !sawScenarioAttr) {
            val anchor = (annotation as? UAnchorOwner)?.uastAnchor?.sourcePsi ?: annotation.sourcePsi
            anchor?.let { out += PsiUsage.textUsage(it.containingFile, it.textRange) }
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
                            val phrase = MAIN_SCENARIO_PHRASES.firstOrNull { line.contains(it) }
                            val phraseStart = phrase?.let { line.indexOf(it) } ?: -1
                            if (phrase != null && phraseStart >= 0) {
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

    // The element a value was written as, whatever the language: a Java literal or a Kotlin string
    // template, both of which carry their quotes in the text range.
    private fun PsiElement.asValueUsage(): Usage {
        val r = textRange
        // Skip the surrounding quotes.
        val rangeForUsage = if (r.length >= 2) TextRange(r.startOffset + 1, r.endOffset - 1) else r
        return PsiUsage.textUsage(containingFile, rangeForUsage)
    }

    private fun extractScenarioCode(scenario: String): String? {
        if (scenario.isBlank() || UseCaseIndex.isMainScenarioLabel(scenario)) {
            return null
        }
        return UseCaseIndex.scenarioPrefix(scenario)
    }

    private fun findUseCaseAnnotationClass(project: Project) =
        PsiShortNamesCache.getInstance(project)
            .getClassesByName("UseCase", GlobalSearchScope.allScope(project))
            .firstOrNull { it.isAnnotationType }

    private companion object {
        val USE_CASE_ID_LINE = UseCaseIndex.USE_CASE_ID_LINE
        val BR_HEADING = UseCaseIndex.BUSINESS_RULE_SITE
        val ALT_FLOW_HEADING = UseCaseIndex.ALT_FLOW_HEADING
        val MAIN_SCENARIO_HEADING = UseCaseIndex.MAIN_SCENARIO_HEADING
        val MAIN_SCENARIO_PHRASES = UseCaseIndex.MAIN_SCENARIO_LABELS
        val TITLE_HEADING = Regex("""^# \S""")
    }
}
