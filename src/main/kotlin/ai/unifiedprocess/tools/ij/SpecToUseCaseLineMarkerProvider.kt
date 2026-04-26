package ai.unifiedprocess.tools.ij

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PsiElementListCellRenderer
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil

/**
 * Adds gutter icons in Markdown spec files:
 *  - Next to "**Use Case ID:** UC-XXX" lines, jumping to all test methods
 *    annotated with that Use Case ID.
 *  - Next to "### BR-XXX" headings, jumping to test methods that reference
 *    that business rule via the @UseCase(businessRules = {...}) attribute.
 *
 * We work on PSI leaf elements to comply with IntelliJ's LineMarkerProvider rules.
 * Markdown PSI does not expose a stable AST across versions, so we do plain text
 * matching on leaf nodes and ensure the marker is anchored to the first leaf of
 * the matching line.
 */
class SpecToUseCaseLineMarkerProvider : LineMarkerProvider {

    private val useCaseIdLine = Regex("""\*\*Use Case ID:\*\*\s*(UC-[A-Za-z0-9_-]+)""")
    private val businessRuleHeading = Regex("""^#{1,6}\s+(BR-[A-Za-z0-9_-]+)\b""")
    private val titleHeading = Regex("""^# \S""")
    private val mainScenarioHeading = Regex("""^#{1,6}\s+Main\s+Success\s+Scenario\s*$""")
    private val altFlowHeading = Regex("""^#{1,6}\s+([A-Z]\d+)\b""")

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // Only consider leaf elements
        if (element.firstChild != null) return null

        val text = element.text ?: return null
        val project = element.project

        // Case 1: Use Case ID line
        val ucMatch = useCaseIdLine.find(text)
        if (ucMatch != null) {
            // Anchor the gutter on the leaf that actually contains the ID match,
            // and avoid duplicate markers by skipping if a previous sibling on
            // the same line already carried the pattern.
            if (!isFirstMatchingLeafOnLine(element, useCaseIdLine)) return null

            val useCaseId = ucMatch.groupValues[1]
            val tests = UseCaseIndex.findTestMethods(project, useCaseId)
            if (tests.isEmpty()) return null

            return NavigationGutterIconBuilder
                .create(AllIcons.Nodes.Method)
                .setTargets(tests)
                .setCellRenderer(Computable { TestMethodRenderer })
                .setTooltipText("Go to test methods for $useCaseId")
                .setPopupTitle("Tests for $useCaseId")
                .setAlignment(GutterIconRenderer.Alignment.LEFT)
                .createLineMarkerInfo(element)
        }

        // Case 2: Business Rule heading
        val brMatch = businessRuleHeading.find(text)
        if (brMatch != null) {
            if (!isFirstMatchingLeafOnLine(element, businessRuleHeading)) return null

            val brId = brMatch.groupValues[1]
            val tests = UseCaseIndex.findTestMethodsForBusinessRule(project, brId)
            if (tests.isEmpty()) return null

            return NavigationGutterIconBuilder
                .create(AllIcons.Nodes.Method)
                .setTargets(tests)
                .setCellRenderer(Computable { TestMethodRenderer })
                .setTooltipText("Go to test methods for $brId")
                .setPopupTitle("Tests for $brId")
                .setAlignment(GutterIconRenderer.Alignment.LEFT)
                .createLineMarkerInfo(element)
        }

        // Case 3: Title (H1) — link to the test class(es). Only for files that
        // declare a Use Case ID, so random Markdown H1s elsewhere don't get a marker.
        if (titleHeading.containsMatchIn(text)) {
            if (!isFirstMatchingLeafOnLine(element, titleHeading)) return null
            val vfile = element.containingFile?.virtualFile ?: return null
            val useCaseId = UseCaseIndex.extractUseCaseId(vfile) ?: return null
            val classes = UseCaseIndex.findTestClasses(project, useCaseId)
            if (classes.isEmpty()) return null

            return NavigationGutterIconBuilder
                .create(AllIcons.Nodes.Class)
                .setTargets(classes)
                .setTooltipText("Go to test class for $useCaseId")
                .setPopupTitle("Test class for $useCaseId")
                .setAlignment(GutterIconRenderer.Alignment.LEFT)
                .createLineMarkerInfo(element)
        }

        // Case 4: "## Main Success Scenario" — link to test methods without a
        // scenario attribute (or with scenario = "Main Success Scenario").
        if (mainScenarioHeading.containsMatchIn(text)) {
            if (!isFirstMatchingLeafOnLine(element, mainScenarioHeading)) return null
            val vfile = element.containingFile?.virtualFile ?: return null
            val useCaseId = UseCaseIndex.extractUseCaseId(vfile) ?: return null
            val tests = UseCaseIndex.findTestMethodsForMainScenario(project, useCaseId)
            if (tests.isEmpty()) return null

            return NavigationGutterIconBuilder
                .create(AllIcons.Nodes.Method)
                .setTargets(tests)
                .setCellRenderer(Computable { TestMethodRenderer })
                .setTooltipText("Go to test methods for the Main Success Scenario of $useCaseId")
                .setPopupTitle("Tests for Main Success Scenario")
                .setAlignment(GutterIconRenderer.Alignment.LEFT)
                .createLineMarkerInfo(element)
        }

        // Case 5: Alternative-flow heading like "### A1: Missing Description" —
        // link to test methods whose `scenario` attribute starts with that code.
        val afMatch = altFlowHeading.find(text)
        if (afMatch != null) {
            if (!isFirstMatchingLeafOnLine(element, altFlowHeading)) return null
            val vfile = element.containingFile?.virtualFile ?: return null
            val useCaseId = UseCaseIndex.extractUseCaseId(vfile) ?: return null
            val code = afMatch.groupValues[1]
            val tests = UseCaseIndex.findTestMethodsForScenario(project, useCaseId, code)
            if (tests.isEmpty()) return null

            return NavigationGutterIconBuilder
                .create(AllIcons.Nodes.Method)
                .setTargets(tests)
                .setCellRenderer(Computable { TestMethodRenderer })
                .setTooltipText("Go to test methods for $code")
                .setPopupTitle("Tests for $code")
                .setAlignment(GutterIconRenderer.Alignment.LEFT)
                .createLineMarkerInfo(element)
        }

        return null
    }

    /**
     * Returns true if `element` is the first leaf on its document line whose
     * text matches `pattern`. This prevents us from placing two markers on
     * the same visible line when the pattern spans multiple PSI leaves.
     */
    private fun isFirstMatchingLeafOnLine(element: PsiElement, pattern: Regex): Boolean {
        val document = com.intellij.psi.PsiDocumentManager
            .getInstance(element.project)
            .getDocument(element.containingFile) ?: return true

        val lineNumber = document.getLineNumber(element.textRange.startOffset)
        val lineStart = document.getLineStartOffset(lineNumber)

        // Walk backwards through previous leaves on the same line; if any of
        // them already match the pattern, this is not the first occurrence.
        var prev = PsiTreeUtil.prevLeaf(element)
        while (prev != null && prev.textRange.startOffset >= lineStart) {
            if (prev.text != null && pattern.containsMatchIn(prev.text)) {
                return false
            }
            prev = PsiTreeUtil.prevLeaf(prev)
        }
        return true
    }

    private object TestMethodRenderer : PsiElementListCellRenderer<PsiMethod>() {
        override fun getElementText(element: PsiMethod): String {
            val scenario = readScenarioAttribute(element)
            return if (scenario.isNullOrBlank()) element.name else "${element.name} — $scenario"
        }

        override fun getContainerText(element: PsiMethod, name: String?): String? =
            element.containingClass?.name

        override fun getIconFlags(): Int = 0

        private fun readScenarioAttribute(method: PsiMethod): String? {
            val annotations = method.modifierList.annotations
            for (annotation in annotations) {
                val qn = annotation.qualifiedName ?: continue
                if (qn != "UseCase" && !qn.endsWith(".UseCase")) continue
                val value = annotation.findAttributeValue("scenario") ?: return null
                return when (value) {
                    is PsiLiteralExpression -> value.value as? String
                    else -> value.text?.trim('"')
                }
            }
            return null
        }
    }
}
