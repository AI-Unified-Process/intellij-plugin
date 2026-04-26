package ai.unifiedprocess.tools.ij

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.codeInsight.navigation.impl.PsiTargetPresentationRenderer
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import java.util.function.Supplier

/**
 * Adds gutter icons in Markdown spec files:
 *  - Next to "**Use Case ID:** UC-XXX" lines, jumping to all test methods
 *    annotated with that Use Case ID.
 *  - Next to "### BR-XXX" headings, jumping to test methods that reference
 *    that business rule via the @UseCase(businessRules = {...}) attribute.
 *  - Next to scenario headings (`## Main Success Scenario`, `### A1: …`),
 *    jumping to test methods whose `scenario` attribute matches.
 *  - Next to the H1 title, jumping to the test class.
 *
 * Markdown PSI splits a heading line across multiple leaves (the `###`
 * marker is its own token, `**…**` fragments inline text further), so we
 * cannot reliably match a multi-token regex against a single leaf. Instead,
 * we anchor on the first leaf of each document line and run the regexes
 * against the full line text read from the document.
 */
class SpecToUseCaseLineMarkerProvider : LineMarkerProvider {

    private val useCaseIdLine = Regex("""\*\*Use Case ID:\*\*\s*(UC-[A-Za-z0-9_-]+)""")
    private val businessRuleHeading = Regex("""^#{1,6}\s+(BR-[A-Za-z0-9_-]+)\b""")
    private val titleHeading = Regex("""^# \S""")
    private val mainScenarioHeading = Regex("""^#{1,6}\s+Main\s+Success\s+Scenario\s*$""")
    private val altFlowHeading = Regex("""^#{1,6}\s+([A-Z]\d+)\b""")

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.firstChild != null) return null

        val project = element.project
        val containingFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project)
            .getDocument(containingFile) ?: return null

        val startOffset = element.textRange.startOffset
        val lineNumber = document.getLineNumber(startOffset)
        val lineStart = document.getLineStartOffset(lineNumber)

        // Anchor on the first leaf of each line so we emit at most one marker
        // per visible line, even when patterns span multiple PSI leaves.
        val prev = PsiTreeUtil.prevLeaf(element)
        if (prev != null && prev.textRange.endOffset > lineStart) return null

        val lineEnd = document.getLineEndOffset(lineNumber)
        val lineText = document.getText(TextRange(lineStart, lineEnd))

        // Case 1: Use Case ID line
        val ucMatch = useCaseIdLine.find(lineText)
        if (ucMatch != null) {
            val useCaseId = ucMatch.groupValues[1]
            val tests = UseCaseIndex.findTestMethods(project, useCaseId)
            if (tests.isEmpty()) return null

            return NavigationGutterIconBuilder
                .create(AllIcons.Nodes.Method)
                .setTargets(tests)
                .setTargetRenderer(Supplier { TestMethodRenderer })
                .setTooltipText("Go to test methods for $useCaseId")
                .setPopupTitle("Tests for $useCaseId")
                .setAlignment(GutterIconRenderer.Alignment.LEFT)
                .createLineMarkerInfo(element)
        }

        // Case 2: Business Rule heading
        val brMatch = businessRuleHeading.find(lineText)
        if (brMatch != null) {
            val brId = brMatch.groupValues[1]
            val tests = UseCaseIndex.findTestMethodsForBusinessRule(project, brId)
            if (tests.isEmpty()) return null

            return NavigationGutterIconBuilder
                .create(AllIcons.Nodes.Method)
                .setTargets(tests)
                .setTargetRenderer(Supplier { TestMethodRenderer })
                .setTooltipText("Go to test methods for $brId")
                .setPopupTitle("Tests for $brId")
                .setAlignment(GutterIconRenderer.Alignment.LEFT)
                .createLineMarkerInfo(element)
        }

        // Case 3: Title (H1) — link to the test class(es). Only for files that
        // declare a Use Case ID, so random Markdown H1s elsewhere don't get a marker.
        if (titleHeading.containsMatchIn(lineText)) {
            val vfile = containingFile.virtualFile ?: return null
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
        if (mainScenarioHeading.containsMatchIn(lineText)) {
            val vfile = containingFile.virtualFile ?: return null
            val useCaseId = UseCaseIndex.extractUseCaseId(vfile) ?: return null
            val tests = UseCaseIndex.findTestMethodsForMainScenario(project, useCaseId)
            if (tests.isEmpty()) return null

            return NavigationGutterIconBuilder
                .create(AllIcons.Nodes.Method)
                .setTargets(tests)
                .setTargetRenderer(Supplier { TestMethodRenderer })
                .setTooltipText("Go to test methods for the Main Success Scenario of $useCaseId")
                .setPopupTitle("Tests for Main Success Scenario")
                .setAlignment(GutterIconRenderer.Alignment.LEFT)
                .createLineMarkerInfo(element)
        }

        // Case 5: Alternative-flow heading like "### A1: Missing Description" —
        // link to test methods whose `scenario` attribute starts with that code.
        val afMatch = altFlowHeading.find(lineText)
        if (afMatch != null) {
            val vfile = containingFile.virtualFile ?: return null
            val useCaseId = UseCaseIndex.extractUseCaseId(vfile) ?: return null
            val code = afMatch.groupValues[1]
            val tests = UseCaseIndex.findTestMethodsForScenario(project, useCaseId, code)
            if (tests.isEmpty()) return null

            return NavigationGutterIconBuilder
                .create(AllIcons.Nodes.Method)
                .setTargets(tests)
                .setTargetRenderer(Supplier { TestMethodRenderer })
                .setTooltipText("Go to test methods for $code")
                .setPopupTitle("Tests for $code")
                .setAlignment(GutterIconRenderer.Alignment.LEFT)
                .createLineMarkerInfo(element)
        }

        return null
    }

    private object TestMethodRenderer : PsiTargetPresentationRenderer<PsiElement>() {
        override fun getElementText(element: PsiElement): String {
            val method = element as? PsiMethod ?: return super.getElementText(element)
            val scenario = readScenarioAttribute(method)
            return if (scenario.isNullOrBlank()) method.name else "${method.name} — $scenario"
        }

        override fun getContainerText(element: PsiElement): String? =
            (element as? PsiMethod)?.containingClass?.name

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
