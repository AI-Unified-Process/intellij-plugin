package ai.unifiedprocess.tools.ij

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolDeclaration
import com.intellij.model.psi.PsiSymbolDeclarationProvider
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil

/**
 * Declares `UseCaseSymbol` / `BusinessRuleSymbol` / `ScenarioSymbol` at every
 * AIUP-relevant site so Alt+F7 can resolve a target there.
 *
 * Java sites:
 *  - `@UseCase(id = "UC-XXX")` literal -> UseCaseSymbol
 *  - `@UseCase(scenario = "A1: ...")` literal -> ScenarioSymbol
 *  - `@UseCase(businessRules = {"BR-XXX"})` literal -> BusinessRuleSymbol
 *
 * Markdown sites (declared on a sub-range of the line so they don't blanket
 * the bundled Markdown plugin's `HeaderSymbol`):
 *  - `**Use Case ID:** UC-XXX` -> UseCaseSymbol on the UC-XXX text
 *  - `# Title` (H1, only when the file declares a UC ID) -> UseCaseSymbol on the title text
 *  - `## Main Success Scenario` -> ScenarioSymbol(null) on "Main Success Scenario"
 *  - `### A1: ...` -> ScenarioSymbol("A1") on "A1"
 *  - `### BR-XXX` -> BusinessRuleSymbol on "BR-XXX"
 */
class UseCaseDeclarationProvider : PsiSymbolDeclarationProvider {

    override fun getDeclarations(
        element: PsiElement,
        offsetInElement: Int,
    ): Collection<PsiSymbolDeclaration> {
        javaDeclaration(element)?.let { return listOf(it) }
        return markdownDeclarations(element, offsetInElement)
    }

    private fun javaDeclaration(element: PsiElement): PsiSymbolDeclaration? {
        val literal = element as? PsiLiteralExpression ?: return null
        val value = literal.value as? String ?: return null

        val pair = PsiTreeUtil.getParentOfType(literal, PsiNameValuePair::class.java) ?: return null
        val ann = PsiTreeUtil.getParentOfType(pair, PsiAnnotation::class.java) ?: return null
        if (!isUseCaseAnnotation(ann)) return null

        val ucId = (ann.findAttributeValue("id") as? PsiLiteralExpression)?.value as? String
            ?: return null
        val project = element.project

        val symbol: Symbol = when (pair.name) {
            "id" -> UseCaseSymbol(project, ucId)
            "businessRules" -> BusinessRuleSymbol(project, ucId, value)
            "scenario" -> ScenarioSymbol(project, ucId, scenarioPrefix(value))
            else -> return null
        }

        // Range inside the literal that excludes the surrounding quotes.
        val length = literal.textLength
        if (length < 2) return null
        val rangeInElement = TextRange(1, length - 1)
        return SimpleDeclaration(literal, rangeInElement, symbol)
    }

    private fun markdownDeclarations(
        element: PsiElement,
        offsetInElement: Int,
    ): Collection<PsiSymbolDeclaration> {
        if (element.firstChild != null) return emptyList()
        val file = element.containingFile ?: return emptyList()
        val vfile = file.virtualFile ?: return emptyList()
        if (vfile.extension != "md") return emptyList()

        val document = PsiDocumentManager.getInstance(file.project).getDocument(file)
            ?: return emptyList()
        val absoluteOffset = element.textRange.startOffset + offsetInElement
        if (absoluteOffset < 0 || absoluteOffset > document.textLength) return emptyList()

        val line = document.getLineNumber(absoluteOffset)
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        val lineText = document.getText(TextRange(lineStart, lineEnd))
        val project = file.project

        val lineRange = TextRange(lineStart, lineEnd)

        // Use Case ID body line.
        USE_CASE_ID_LINE.find(lineText)?.let { match ->
            val symbol = UseCaseSymbol(project, match.groupValues[1])
            return declareOnLeafForLine(element, lineRange, symbol)
        }

        val ucIdFromFile = UseCaseIndex.extractUseCaseId(vfile) ?: return emptyList()

        BR_HEADING.find(lineText)?.let { match ->
            val symbol = BusinessRuleSymbol(project, ucIdFromFile, match.groupValues[1])
            return declareOnLeafForLine(element, lineRange, symbol)
        }

        ALT_FLOW_HEADING.find(lineText)?.let { match ->
            val symbol = ScenarioSymbol(project, ucIdFromFile, match.groupValues[1])
            return declareOnLeafForLine(element, lineRange, symbol)
        }

        if (MAIN_SCENARIO_HEADING.containsMatchIn(lineText)) {
            val symbol = ScenarioSymbol(project, ucIdFromFile, null)
            return declareOnLeafForLine(element, lineRange, symbol)
        }

        if (TITLE_HEADING.containsMatchIn(lineText)) {
            val symbol = UseCaseSymbol(project, ucIdFromFile)
            return declareOnLeafForLine(element, lineRange, symbol)
        }

        return emptyList()
    }

    /**
     * Emits a declaration on the cursor's leaf covering the leaf's text range
     * clamped to the matched line. This makes Find Usages fire from any
     * cursor position on the line, not just on the captured-token leaf.
     */
    private fun declareOnLeafForLine(
        element: PsiElement,
        lineRange: TextRange,
        symbol: Symbol,
    ): Collection<PsiSymbolDeclaration> {
        val leafOnLine = element.textRange.intersection(lineRange) ?: return emptyList()
        if (leafOnLine.isEmpty) return emptyList()
        val rangeInElement = leafOnLine.shiftLeft(element.textRange.startOffset)
        return listOf(SimpleDeclaration(element, rangeInElement, symbol))
    }

    private fun isUseCaseAnnotation(ann: PsiAnnotation): Boolean {
        val qn = ann.qualifiedName ?: return false
        return qn == "UseCase" || qn.endsWith(".UseCase")
    }

    private fun scenarioPrefix(scenario: String): String? {
        val colon = scenario.indexOf(':')
        val prefix = (if (colon >= 0) scenario.substring(0, colon) else scenario).trim()
        return prefix.takeIf { it.matches(SCENARIO_PREFIX) }
    }

    private companion object {
        val USE_CASE_ID_LINE = Regex("""\*\*Use Case ID:\*\*\s*(UC-[A-Za-z0-9_-]+)""")
        val BR_HEADING = Regex("""^#{1,6}\s+(BR-[A-Za-z0-9_-]+)\b""")
        val ALT_FLOW_HEADING = Regex("""^#{1,6}\s+([A-Z]\d+)\b""")
        val MAIN_SCENARIO_HEADING = Regex("""^#{1,6}\s+Main\s+Success\s+Scenario\s*$""")
        val TITLE_HEADING = Regex("""^# \S""")
        val SCENARIO_PREFIX = Regex("""[A-Z]\d+""")
    }
}

private class SimpleDeclaration(
    private val element: PsiElement,
    private val range: TextRange,
    private val symbol: Symbol,
) : PsiSymbolDeclaration {
    override fun getDeclaringElement(): PsiElement = element
    override fun getRangeInDeclaringElement(): TextRange = range
    override fun getSymbol(): Symbol = symbol
}
