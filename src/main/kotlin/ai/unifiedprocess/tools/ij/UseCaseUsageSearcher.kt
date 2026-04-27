package ai.unifiedprocess.tools.ij

import com.intellij.find.usages.api.PsiUsage
import com.intellij.find.usages.api.Usage
import com.intellij.find.usages.api.UsageSearchParameters
import com.intellij.find.usages.api.UsageSearcher
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.CollectionQuery
import com.intellij.util.Query

/**
 * Returns every Java/Markdown site that participates in the same Use Case
 * relation as the cursor target. Handles `UseCaseSymbol` (UC-XXX),
 * `BusinessRuleSymbol` (UC-XXX × BR-YYY) and `ScenarioSymbol`
 * (UC-XXX × {Main | Aₙ}). Site list, in order:
 *   - Java `@UseCase(...)` literals matching the symbol
 *   - Markdown spec lines/headings matching the symbol
 */
class UseCaseUsageSearcher : UsageSearcher {

    override fun collectSearchRequests(
        parameters: UsageSearchParameters,
    ): Collection<Query<out Usage>> {
        val target = parameters.target as? UseCaseRelatedSymbol ?: return emptyList()
        val project = parameters.project

        val usages: List<Usage> = @Suppress("DEPRECATION") runReadAction {
            buildList {
                addAll(javaUsages(project, target))
                addAll(markdownUsages(project, target))
            }
        }
        if (usages.isEmpty()) return emptyList()
        return listOf(CollectionQuery(usages))
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
        for (pair in ann.parameterList.attributes) {
            when (pair.name ?: "value") {
                "id" -> if (target is UseCaseSymbol) {
                    (pair.value as? PsiLiteralExpression)?.let { out += it.asValueUsage() }
                }
                "scenario" -> if (target is ScenarioSymbol) {
                    val literal = pair.value as? PsiLiteralExpression ?: continue
                    val scenario = literal.value as? String
                    val code = scenario?.let(::extractScenarioCode)
                    if (code == target.scenarioCode) out += literal.asValueUsage()
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
