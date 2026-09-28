package ai.unifiedprocess.tools.ij

import org.intellij.plugins.markdown.extensions.MarkdownBrowserPreviewExtension
import org.intellij.plugins.markdown.ui.preview.MarkdownHtmlPanel
import org.intellij.plugins.markdown.ui.preview.ResourceProvider

/**
 * Renders ` ```bpmn ` fences of the Markdown preview as read-only BPMN diagrams, as the
 * AI Unified Process Studio does: the script loaded into every preview hides each BPMN
 * code block and shows the diagram bpmn-js lays out from it, as SVG. The Markdown source
 * is never touched.
 */
class BpmnPreviewExtension : MarkdownBrowserPreviewExtension, ResourceProvider {

    override val scripts: List<String> = listOf(PREVIEW_SCRIPT)

    override val resourceProvider: ResourceProvider = this

    override fun canProvide(resourceName: String): Boolean = resourceName == PREVIEW_SCRIPT

    override fun loadResource(resourceName: String): ResourceProvider.Resource? =
        javaClass.getResourceAsStream(PREVIEW_SCRIPT_RESOURCE)?.use {
            ResourceProvider.Resource(it.readBytes(), "text/javascript")
        }

    override fun dispose() = Unit

    class Provider : MarkdownBrowserPreviewExtension.Provider {
        override fun createBrowserExtension(panel: MarkdownHtmlPanel): MarkdownBrowserPreviewExtension =
            BpmnPreviewExtension()
    }

    private companion object {
        const val PREVIEW_SCRIPT = "aiup-bpmn-preview.js"
        const val PREVIEW_SCRIPT_RESOURCE = "/bpmn-editor/preview.js"
    }
}
