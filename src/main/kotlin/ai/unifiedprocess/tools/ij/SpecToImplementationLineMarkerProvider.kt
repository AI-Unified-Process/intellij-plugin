package ai.unifiedprocess.tools.ij

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.codeInsight.navigation.impl.PsiTargetPresentationRenderer
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.util.function.Supplier

/**
 * Adds a gutter icon in Markdown specs that jumps to the implementation: the
 * code comments carrying a matching Use Case marker (`// UC-XXX BR-YYY: …`).
 * It sits next to the test icon of [SpecToUseCaseLineMarkerProvider], on
 *  - the `**Use Case ID:** UC-XXX` line, or the H1 title `# UC-XXX: …` of a spec
 *    without that line, for every marker of the Use Case,
 *  - business-rule sites, for the markers of that rule,
 *  - alternative-flow headings, for the markers of that flow.
 *
 * Built in [collectSlowLineMarkers] because each site runs a project search.
 */
class SpecToImplementationLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        elements.mapNotNullTo(result, ::markerFor)
    }

    private fun markerFor(element: PsiElement): LineMarkerInfo<*>? {
        if (element.firstChild != null) return null
        val file = element.containingFile ?: return null
        val lineText = lineTextOfFirstLeaf(element) ?: return null
        val site = specSite(lineText, file) ?: return null

        val markers = UseCaseIndex.findImplementationMarkers(element.project, site.useCaseId, site.qualifier)
        if (markers.isEmpty()) return null

        return NavigationGutterIconBuilder
            .create(AllIcons.Gutter.ImplementedMethod)
            .setTargets(markers.map { it.comment }.distinct())
            .setTargetRenderer(Supplier { MarkerRenderer })
            .setTooltipText("Go to implementation of ${site.label}")
            .setPopupTitle("Implementation of ${site.label}")
            .setAlignment(GutterIconRenderer.Alignment.LEFT)
            .createLineMarkerInfo(element)
    }

    private fun specSite(lineText: String, file: PsiFile): UseCaseIndex.UseCaseReference? {
        UseCaseIndex.USE_CASE_ID_LINE.find(lineText)?.let {
            return UseCaseIndex.UseCaseReference(it.groupValues[1])
        }
        val useCaseId = file.virtualFile?.let(UseCaseIndex::extractUseCaseId) ?: return null

        val qualifier = UseCaseIndex.BUSINESS_RULE_SITE.find(lineText)?.groupValues?.get(1)
            ?: UseCaseIndex.ALT_FLOW_HEADING.find(lineText)?.groupValues?.get(1)
        if (qualifier != null) return UseCaseIndex.UseCaseReference(useCaseId, qualifier)

        val isTitleSite = UseCaseIndex.USE_CASE_TITLE.find(lineText)?.groupValues?.get(1) == useCaseId &&
            !UseCaseIndex.USE_CASE_ID_LINE.containsMatchIn(file.text)
        return if (isTitleSite) UseCaseIndex.UseCaseReference(useCaseId) else null
    }

    /** Shows a marker as its comment text, with the code file name as the container. */
    private object MarkerRenderer : PsiTargetPresentationRenderer<PsiElement>() {
        private const val COMMENT_DELIMITERS = "/*-#<!> \t"

        override fun getElementText(element: PsiElement): String =
            element.text.lineSequence()
                .map { line -> line.trim { it in COMMENT_DELIMITERS } }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()

        override fun getContainerText(element: PsiElement): String? = element.containingFile?.name
    }
}
