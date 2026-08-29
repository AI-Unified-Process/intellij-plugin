package ai.unifiedprocess.tools.ij

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolDeclaration
import com.intellij.model.psi.PsiSymbolDeclarationProvider
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.ULiteralExpression
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.toUElementOfType

/**
 * Declares `UseCaseSymbol` / `BusinessRuleSymbol` / `ScenarioSymbol` at every
 * AI Unified Process-relevant site so Alt+F7 can resolve a target there.
 *
 * Annotation sites (any language UAST covers — Java, Kotlin, …):
 *  - `@UseCase(id = "UC-XXX")` literal -> UseCaseSymbol
 *  - `@UseCase(scenario = "A1: ...")` literal -> ScenarioSymbol
 *  - `@UseCase(businessRules = {"BR-XXX"})` literal -> BusinessRuleSymbol
 *
 * Markdown sites (declared on a sub-range of the line so they don't blanket
 * the bundled Markdown plugin's `HeaderSymbol`):
 *  - `**Use Case ID:** UC-XXX` -> UseCaseSymbol on the UC-XXX text
 *  - `# Title` (H1, only when the file declares a UC ID) -> UseCaseSymbol on the title text
 *  - `## Main Success Scenario` / `## Hauptablauf` -> ScenarioSymbol(null)
 *  - `### A1: ...` / `### 3a. ...` -> ScenarioSymbol("A1" / "3a")
 *  - `### BR-XXX` heading or `- **GR-XXX:** ...` bullet -> BusinessRuleSymbol
 */
class UseCaseDeclarationProvider : PsiSymbolDeclarationProvider {

    override fun getDeclarations(
        element: PsiElement,
        offsetInElement: Int,
    ): Collection<PsiSymbolDeclaration> {
        annotationDeclaration(element)?.let { return listOf(it) }
        return markdownDeclarations(element, offsetInElement)
    }

    private fun annotationDeclaration(element: PsiElement): PsiSymbolDeclaration? {
        val literal = element.toUElementOfType<ULiteralExpression>() ?: return null
        // Declare on the element that owns the string and no other: the platform offers every
        // ancestor of the caret, and each language nests its literals differently.
        if (literal.sourcePsi !== element) return null
        val value = literal.evaluate() as? String ?: return null

        val annotation = literal.getParentOfType<UAnnotation>() ?: return null
        if (!UseCaseIndex.isUseCaseAnnotation(annotation)) return null

        val ucId = UseCaseIndex.attributeString(annotation, "id") ?: return null
        val project = element.project

        val symbol: Symbol = when (annotation.attributeNameOf(literal)) {
            "id" -> UseCaseSymbol(project, ucId)
            "businessRules" -> BusinessRuleSymbol(project, ucId, value)
            "scenario" -> ScenarioSymbol(project, ucId, scenarioPrefix(value))
            else -> return null
        }

        val rangeInElement = element.rangeInsideQuotes() ?: return null
        return SimpleDeclaration(element, rangeInElement, symbol)
    }

    /**
     * Which attribute a value was written for, decided by source position rather than by identity:
     * UAST rebuilds its elements per conversion, so the same literal is not the same instance twice.
     */
    private fun UAnnotation.attributeNameOf(value: ULiteralExpression): String? {
        val target = value.sourcePsi?.textRange ?: return null
        return attributeValues.firstOrNull { attribute ->
            attribute.expression.sourcePsi?.textRange?.contains(target) == true
        }?.name
    }

    /** The range inside a string literal that excludes its quotes, whichever quoting the language uses. */
    private fun PsiElement.rangeInsideQuotes(): TextRange? {
        val text = text ?: return null
        val quote = QUOTE_FORMS.firstOrNull { text.length >= 2 * it.length && text.startsWith(it) && text.endsWith(it) }
            ?: return null
        return TextRange(quote.length, text.length - quote.length)
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

    private fun scenarioPrefix(scenario: String): String? = UseCaseIndex.scenarioPrefix(scenario)

    private companion object {
        // Longest first: a Kotlin raw string starts with the one-character form too.
        val QUOTE_FORMS = listOf("\"\"\"", "\"")

        val USE_CASE_ID_LINE = UseCaseIndex.USE_CASE_ID_LINE
        val BR_HEADING = UseCaseIndex.BUSINESS_RULE_SITE
        val ALT_FLOW_HEADING = UseCaseIndex.ALT_FLOW_HEADING
        val MAIN_SCENARIO_HEADING = UseCaseIndex.MAIN_SCENARIO_HEADING
        val TITLE_HEADING = Regex("""^# \S""")
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
