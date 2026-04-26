package ai.unifiedprocess.tools.ij

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PsiElementListCellRenderer
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager

/**
 * Adds a gutter icon next to `@UseCase` annotations on test methods.
 * Clicking it navigates to the matching Markdown spec file.
 *
 * The marker is placed on a leaf element (the annotation name identifier)
 * to comply with IntelliJ's LineMarkerProvider contract.
 */
class UseCaseToSpecLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // Only react on the leaf identifier of an annotation reference,
        // e.g. the "UseCase" token inside `@UseCase(...)`.
        if (element !is PsiIdentifier) return null

        val refElement = element.parent as? PsiJavaCodeReferenceElement ?: return null
        val annotation = refElement.parent as? PsiAnnotation ?: return null

        if (annotation.qualifiedName?.endsWith(".UseCase") != true &&
            annotation.qualifiedName != "UseCase") {
            return null
        }

        val idValue = annotation.findAttributeValue("id") as? PsiLiteralExpression ?: return null
        val useCaseId = idValue.value as? String ?: return null

        val project = element.project

        // Prefer specific lines in the spec (scenario heading + BR headings).
        // Fall back to the spec file(s) only when none of those headings exist yet.
        val targets: List<PsiElement> = UseCaseIndex
            .findSpecLeavesForAnnotation(project, annotation)
            .ifEmpty {
                UseCaseIndex.findSpecFiles(project, useCaseId)
                    .mapNotNull { PsiManager.getInstance(project).findFile(it) }
            }

        if (targets.isEmpty()) return null

        val builder = NavigationGutterIconBuilder
            .create(AllIcons.FileTypes.Text)
            .setTargets(targets)
            .setTooltipText("Go to spec for $useCaseId")
            .setPopupTitle("Spec for $useCaseId")
            .setCellRenderer(Computable { SpecTargetRenderer })
            .setAlignment(GutterIconRenderer.Alignment.LEFT)

        return builder.createLineMarkerInfo(element)
    }

    private object SpecTargetRenderer : PsiElementListCellRenderer<PsiElement>() {
        override fun getElementText(element: PsiElement): String {
            val file = element.containingFile ?: return element.text.orEmpty()
            return headingTextAt(file, element.textRange.startOffset)
                ?: file.name
        }

        override fun getContainerText(element: PsiElement, name: String?): String? =
            element.containingFile?.name

        override fun getIconFlags(): Int = 0

        private fun headingTextAt(file: PsiFile, offset: Int): String? {
            val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null
            if (offset < 0 || offset > document.textLength) return null
            val line = document.getLineNumber(offset)
            val raw = document.getText(
                TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)),
            )
            val stripped = raw.trimStart().trimStart('#').trim()
            return stripped.takeIf { it.isNotEmpty() }
        }
    }
}
