package ai.unifiedprocess.tools.ij

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import java.util.function.Supplier

/**
 * Adds a gutter icon next to the Use Case markers in implementation code, such
 * as `// UC-002 BR-001: …`, `-- UC-007 BR-001: …` or a Javadoc line `UC-010 A2: …`.
 * Clicking it navigates to that rule or flow in the spec.
 *
 * Registered for every language, so it works on PSI alone. Doc and XML comments
 * are composites, so the marker anchors on the leaf inside the [PsiComment] and
 * reads the references from that leaf's text: one line of a Javadoc comment, or
 * a whole line or block comment.
 */
class CommentToSpecLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.firstChild != null) return null

        val references = UseCaseIndex.findUseCaseReferences(element.text)
        if (references.isEmpty() || !UseCaseIndex.isCommentLeaf(element)) return null

        val resolved = references.associateWith { UseCaseIndex.findSpecTargets(element.project, it) }
            .filterValues { it.isNotEmpty() }
        if (resolved.isEmpty()) return null

        val labels = resolved.keys.joinToString(", ") { it.label }
        return NavigationGutterIconBuilder
            .create(AllIcons.FileTypes.Text)
            .setTargets(resolved.values.flatten().distinct())
            .setTooltipText("Go to spec for $labels")
            .setPopupTitle("Spec for $labels")
            .setTargetRenderer(Supplier { SpecTargetRenderer })
            .setAlignment(GutterIconRenderer.Alignment.LEFT)
            .createLineMarkerInfo(element)
    }
}
