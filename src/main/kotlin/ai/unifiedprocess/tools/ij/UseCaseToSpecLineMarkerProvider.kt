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
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import java.util.function.Supplier
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.toUElementOfType

/**
 * Adds a gutter icon next to `@UseCase` annotations on test methods.
 * Clicking it navigates to the matching Markdown spec file.
 *
 * Registered for the UAST meta-language rather than for Java, so one code path serves every
 * language with a UAST implementation — a Kotlin test carries the same annotation and gets the
 * same icon. The marker is placed on a leaf element (the annotation name token) to comply with
 * IntelliJ's LineMarkerProvider contract.
 */
class UseCaseToSpecLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val annotation = element.useCaseAnnotationAtName() ?: return null

        val useCaseId = UseCaseIndex.attributeString(annotation, "id") ?: return null
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
            .setTargetRenderer(Supplier { SpecTargetRenderer })
            .setAlignment(GutterIconRenderer.Alignment.LEFT)

        return builder.createLineMarkerInfo(element)
    }

    /**
     * The `@UseCase` annotation this leaf names, or null for any other leaf.
     *
     * Anchoring on the name token is what keeps the marker to one per annotation: the short name
     * appears exactly once at each site, in every language. Requiring the token's parent to be a
     * reference rules out a string argument that happens to read the same — in Kotlin the contents
     * of `"UseCase"` are a leaf whose text matches too.
     */
    private fun PsiElement.useCaseAnnotationAtName(): UAnnotation? {
        if (firstChild != null) return null
        if (text != UseCaseIndex.ANNOTATION_NAME) return null
        val named = parent ?: return null
        if (named !is PsiReference && named.reference == null) return null

        var candidate: PsiElement? = named
        repeat(MAX_NAME_DEPTH) {
            if (candidate == null || candidate is PsiFile) return null
            val annotation = candidate.toUElementOfType<UAnnotation>()
            if (annotation != null) {
                return annotation.takeIf { UseCaseIndex.isUseCaseAnnotation(it) }
            }
            candidate = candidate.parent
        }
        return null
    }

    private object SpecTargetRenderer : PsiTargetPresentationRenderer<PsiElement>() {
        override fun getElementText(element: PsiElement): String {
            val file = element.containingFile ?: return element.text.orEmpty()
            return headingTextAt(file, element.textRange.startOffset)
                ?: file.name
        }

        override fun getContainerText(element: PsiElement): String? =
            element.containingFile?.name

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

    private companion object {
        // How far above the name token the annotation sits: two levels in Java, five in Kotlin,
        // whose name is wrapped in a type reference and a constructor callee on the way up. The
        // cap only stops a runaway walk — the text and reference checks are what select the token.
        const val MAX_NAME_DEPTH = 10
    }
}
