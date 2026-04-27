package ai.unifiedprocess.tools.ij

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaDirectoryService
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager

object CreateUseCaseAnnotationAction {

    private const val FILE_NAME = "UseCase.java"
    private const val TEMPLATE_PATH = "/templates/UseCase.java.template"

    fun run(project: Project) {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Choose Target Source Root")
            .withDescription("Pick the directory where UseCase.java should be created.")
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return

        val psiDir = PsiManager.getInstance(project).findDirectory(chosen)
        if (psiDir == null) {
            notifyError(project, "Could not resolve the chosen directory.")
            return
        }
        if (!psiDir.isWritable) {
            notifyError(project, "The chosen directory is not writable.")
            return
        }
        if (psiDir.findFile(FILE_NAME) != null) {
            notifyError(project, "$FILE_NAME already exists in the chosen directory.")
            return
        }

        val packageName = JavaDirectoryService.getInstance().getPackage(psiDir)?.qualifiedName.orEmpty()
        val source = renderTemplate(packageName) ?: run {
            notifyError(project, "Could not load $FILE_NAME template from the plugin resources.")
            return
        }

        val created: PsiFile? = WriteCommandAction.writeCommandAction(project)
            .withName("Create UseCase Annotation")
            .compute<PsiFile?, RuntimeException> {
                val file = PsiFileFactory.getInstance(project)
                    .createFileFromText(FILE_NAME, JavaFileType.INSTANCE, source)
                psiDir.add(file) as? PsiFile
            }

        val vFile = created?.virtualFile
        if (vFile != null) {
            FileEditorManager.getInstance(project).openFile(vFile, true)
        } else {
            notifyError(project, "Failed to create $FILE_NAME.")
        }
    }

    private fun renderTemplate(packageName: String): String? {
        val raw = CreateUseCaseAnnotationAction::class.java.getResourceAsStream(TEMPLATE_PATH)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return null
        val packageLine = if (packageName.isEmpty()) "" else "package $packageName;\n\n"
        return raw.replace("__PACKAGE__", packageLine)
    }

    private fun notifyError(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("AIUP")
            .createNotification("AIUP", message, NotificationType.ERROR)
            .notify(project)
    }
}
