package ai.unifiedprocess.tools.ij

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Tool window that shows the activity diagram of the Use Case spec in the selected
 * editor: the diagram is regenerated from the Main Success Scenario and the Alternative
 * Flows on every edit (debounced) and rendered in-process, mirroring the live preview
 * of the AI Unified Process Studio's use case editor.
 */
class ActivityDiagramToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ActivityDiagramPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

private class ActivityDiagramPanel(
    private val project: Project,
    parentDisposable: Disposable,
) : JPanel(BorderLayout()), Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    private val diagramLabel = JBLabel().apply {
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.TOP
        border = JBUI.Borders.empty(8)
    }

    private val messageLabel = JBLabel().apply {
        border = JBUI.Borders.empty(8)
    }

    private var trackedDocument: Document? = null

    /** Scopes the document listener to the currently tracked document. */
    private var trackingDisposable: Disposable? = null

    /** Guards against out-of-order results of overlapping background renderings. */
    private var renderSequence = 0L

    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) = scheduleRefresh()
    }

    init {
        Disposer.register(parentDisposable, this)
        add(messageLabel, BorderLayout.NORTH)
        add(JBScrollPane(diagramLabel), BorderLayout.CENTER)
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = trackSelection()
            },
        )
        trackSelection()
    }

    override fun dispose() {
        // trackingDisposable is a child of this panel, so the listener goes with it
        trackedDocument = null
    }

    private fun trackSelection() {
        val document = FileEditorManager.getInstance(project).selectedTextEditor?.document
        if (document !== trackedDocument) {
            trackingDisposable?.let(Disposer::dispose)
            trackingDisposable = null
            trackedDocument = document?.also {
                val disposable = Disposer.newDisposable(this, "AI Unified Process diagram document tracking")
                trackingDisposable = disposable
                it.addDocumentListener(documentListener, disposable)
            }
        }
        scheduleRefresh()
    }

    private fun scheduleRefresh() {
        alarm.cancelAllRequests()
        alarm.addRequest(::refresh, DEBOUNCE_MILLIS)
    }

    private fun refresh() {
        if (project.isDisposed) return
        val document = trackedDocument
        val file = document?.let { FileDocumentManager.getInstance().getFile(it) }
        val text = document?.text
        if (file == null || text == null || !isUseCaseSpec(file, text)) {
            show("Open a Use Case spec (UC-*.md) to see its activity diagram.", null)
            return
        }
        val sequence = ++renderSequence
        ApplicationManager.getApplication().executeOnPooledThread {
            val source = ActivityDiagram.generate(ActivityDiagram.parse(text))
            val rendering = ActivityDiagramRenderer.render(source)
            val icon = (rendering as? ActivityDiagramRenderer.Rendering.Image)
                ?.let { ImageIO.read(ByteArrayInputStream(it.png)) }
                ?.let { ImageIcon(it) }
            ApplicationManager.getApplication().invokeLater {
                if (sequence != renderSequence || project.isDisposed) return@invokeLater
                when (rendering) {
                    is ActivityDiagramRenderer.Rendering.Empty ->
                        show("The Main Success Scenario has no steps yet.", null)
                    is ActivityDiagramRenderer.Rendering.Error ->
                        show("Cannot render the diagram: ${rendering.message}", null)
                    is ActivityDiagramRenderer.Rendering.Image -> show(null, icon)
                }
            }
        }
    }

    private fun show(message: String?, icon: ImageIcon?) {
        messageLabel.text = message.orEmpty()
        messageLabel.isVisible = message != null
        diagramLabel.icon = icon
    }

    private fun isUseCaseSpec(file: VirtualFile, text: String): Boolean =
        file.extension == "md" &&
            (UseCaseIndex.isSpecFileName(file.nameWithoutExtension) ||
                UseCaseIndex.findDeclaredUseCaseId(text) != null)

    private companion object {

        const val DEBOUNCE_MILLIS = 300
    }
}
