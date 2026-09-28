package ai.unifiedprocess.tools.ij

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Opens `*.bpmn` files in the BPMN editor of the AI Unified Process Studio (the bpmn-js
 * modeler with the properties panel), placed before the XML text editor so both are
 * available as editor tabs. Needs the IDE's embedded browser (JCEF); without it the file
 * opens in the text editor only.
 */
class BpmnEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        file.extension.equals(BPMN_EXTENSION, ignoreCase = true) && JBCefApp.isSupported()

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = BpmnFileEditor(project, file)

    override fun getEditorTypeId(): String = "aiup-bpmn-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR

    companion object {
        const val BPMN_EXTENSION = "bpmn"
    }
}

/**
 * The diagram and the text tab share the IDE document: an edit in the diagram replaces
 * the document text in an undoable command, an edit of the text is imported into the
 * diagram (debounced). Opening a file imports it unchanged, so it is never marked
 * modified until the user edits it. The toolbar exports the shown diagram, unsaved edits
 * included, as SVG or as PNG (twice the size, on white), as in the studio.
 */
private class BpmnFileEditor(
    private val project: Project,
    private val file: VirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val document: Document? = FileDocumentManager.getInstance().getDocument(file)

    private val browser = JBCefBrowser.createBuilder().build()

    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    private val component: JComponent

    /** The XML the diagram shows, so neither side echoes a change back to its origin. */
    private var syncedText: String? = null

    private var applyingDiagramEdit = false

    /** Where the export the page is producing goes. */
    private var pendingExport: Path? = null

    init {
        Disposer.register(this, browser)
        Disposer.register(this, query)
        val html = javaClass.getResource(EDITOR_PAGE)?.readText()
        component = if (html == null) {
            JBLabel("The BPMN editor is missing from the plugin distribution.").apply {
                border = JBUI.Borders.empty(8)
            }
        } else {
            query.addHandler { message ->
                ApplicationManager.getApplication().invokeLater({ onMessage(message) }) { browser.isDisposed }
                null
            }
            val bridge = "window.aiupBpmnSend = function(message) { ${query.inject("message")} };"
            browser.loadHTML(html.replace(BRIDGE_PLACEHOLDER, bridge))
            JPanel(BorderLayout()).apply {
                add(createToolbar(browser.component), BorderLayout.NORTH)
                add(browser.component, BorderLayout.CENTER)
            }
        }
        document?.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (applyingDiagramEdit) return
                alarm.cancelAllRequests()
                alarm.addRequest(::pushDocument, TEXT_SYNC_DELAY_MS)
            }
        }, this)
    }

    private fun onMessage(message: String) {
        when {
            message == "ready" -> {
                val readOnly = document == null || !document.isWritable
                syncedText = document?.text.orEmpty()
                execute("window.aiupBpmn.setXml(${jsString(syncedText!!)}); window.aiupBpmn.init($readOnly);")
            }

            message.startsWith(CHANGED_PREFIX) -> applyDiagramEdit(message.removePrefix(CHANGED_PREFIX))
            message.startsWith(SVG_PREFIX) -> writeExport(message.removePrefix(SVG_PREFIX).toByteArray())
            message.startsWith(PNG_PREFIX) -> writeExport(Base64.getDecoder().decode(message.removePrefix(PNG_PREFIX)))
            message.startsWith(EXPORT_FAILED_PREFIX) -> {
                pendingExport = null
                notify("The BPMN diagram could not be exported: ${message.removePrefix(EXPORT_FAILED_PREFIX)}",
                    NotificationType.ERROR)
            }
        }
    }

    private fun createToolbar(target: JComponent): JComponent {
        val group = DefaultActionGroup(ExportAction(ExportFormat.SVG), ExportAction(ExportFormat.PNG))
        val toolbar = ActionManager.getInstance().createActionToolbar("AiupBpmnEditor", group, true)
        toolbar.targetComponent = target
        return toolbar.component
    }

    private fun export(format: ExportFormat) {
        val descriptor = FileSaverDescriptor("Export BPMN Diagram", "Save the diagram as ${format.name}", format.extension)
        val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save(file.parent, "${file.nameWithoutExtension}.${format.extension}") ?: return
        pendingExport = target.file.toPath()
        execute("window.aiupBpmn.${format.pageFunction}();")
    }

    private fun writeExport(content: ByteArray) {
        val target = pendingExport ?: return
        pendingExport = null
        try {
            Files.write(target, content)
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
            notify("Exported the BPMN diagram to ${target.fileName}.", NotificationType.INFORMATION)
        } catch (exception: java.io.IOException) {
            notify("The BPMN diagram could not be written to $target: ${exception.message}", NotificationType.ERROR)
        }
    }

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(content, type)
            .notify(project)
    }

    private inner class ExportAction(private val format: ExportFormat) :
        DumbAwareAction("Export ${format.name}…", "Export the diagram as ${format.name}", AllIcons.ToolbarDecorator.Export) {

        init {
            templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            // the page reads the document once it is ready
            event.presentation.isEnabled = syncedText != null && pendingExport == null
        }

        override fun actionPerformed(event: AnActionEvent) = export(format)
    }

    private enum class ExportFormat(val extension: String, val pageFunction: String) {
        SVG("svg", "exportSvg"),
        PNG("png", "exportPng"),
    }

    private fun applyDiagramEdit(xml: String) {
        val document = document ?: return
        syncedText = xml
        if (!document.isWritable || document.text == xml) return
        WriteCommandAction.runWriteCommandAction(project, "Edit BPMN Diagram", null, {
            applyingDiagramEdit = true
            try {
                document.setText(xml)
            } finally {
                applyingDiagramEdit = false
            }
        })
    }

    private fun pushDocument() {
        val text = document?.text ?: return
        // before the page is ready it reads the document itself
        if (syncedText == null || text == syncedText) return
        syncedText = text
        execute("window.aiupBpmn.setXml(${jsString(text)});")
    }

    private fun execute(script: String) {
        if (browser.isDisposed) return
        browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
    }

    override fun getComponent(): JComponent = component

    override fun getPreferredFocusedComponent(): JComponent = component

    override fun getName(): String = "Diagram"

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun getFile(): VirtualFile = file

    override fun dispose() = Unit

    private companion object {
        const val EDITOR_PAGE = "/bpmn-editor/editor.html"
        const val BRIDGE_PLACEHOLDER = "/*AIUP_BRIDGE*/"
        const val CHANGED_PREFIX = "changed:"
        const val SVG_PREFIX = "svg:"
        const val PNG_PREFIX = "png:"
        const val EXPORT_FAILED_PREFIX = "export-failed:"
        const val NOTIFICATION_GROUP = "AI Unified Process"
        const val TEXT_SYNC_DELAY_MS = 300
    }
}

/** Encodes [value] as a JavaScript string literal that is also safe inside an HTML script. */
internal fun jsString(value: String): String = buildString(value.length + 16) {
    append('"')
    for (char in value) {
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '<' -> append("\\u003c")
            ' ' -> append("\\u2028")
            ' ' -> append("\\u2029")
            else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
        }
    }
    append('"')
}
