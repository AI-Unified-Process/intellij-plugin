package ai.unifiedprocess.tools.ij

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.ide.util.PackageChooserDialog
import com.intellij.ide.util.PackageUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import org.jetbrains.jps.model.java.JavaSourceRootType

object CreateUseCaseAnnotationAction {

    private const val FILE_NAME = "UseCase.java"
    private const val TEMPLATE_PATH = "/templates/UseCase.java.template"

    fun run(project: Project) {
        val testRoots = collectSourceRoots(project, JavaSourceRootType.TEST_SOURCE)
        val roots = testRoots.ifEmpty { collectSourceRoots(project, JavaSourceRootType.SOURCE) }
        if (roots.isEmpty()) {
            notifyError(project, "No Java source roots found in this project.")
            return
        }

        chooseRoot(project, roots) { root ->
            val rootDir = PsiManager.getInstance(project).findDirectory(root.virtualFile)
            if (rootDir == null) {
                notifyError(project, "Could not resolve source root ${root.virtualFile.path}.")
                return@chooseRoot
            }

            val packageName = choosePackage(project) ?: return@chooseRoot

            val targetDir = WriteCommandAction.writeCommandAction(project)
                .withName("Create UseCase Annotation Package")
                .compute<PsiDirectory?, RuntimeException> {
                    PackageUtil.findOrCreateDirectoryForPackage(root.module, packageName, rootDir, false)
                }
            if (targetDir == null) {
                notifyError(project, "Could not find or create package directory for $packageName.")
                return@chooseRoot
            }

            if (targetDir.findFile(FILE_NAME) != null) {
                val where = packageName.ifEmpty { "<default package>" }
                notifyError(project, "$FILE_NAME already exists in $where.")
                return@chooseRoot
            }

            val source = renderTemplate(packageName) ?: run {
                notifyError(project, "Could not load $FILE_NAME template from the plugin resources.")
                return@chooseRoot
            }

            val created: PsiFile? = WriteCommandAction.writeCommandAction(project)
                .withName("Create UseCase Annotation")
                .compute<PsiFile?, RuntimeException> {
                    val file = PsiFileFactory.getInstance(project)
                        .createFileFromText(FILE_NAME, JavaFileType.INSTANCE, source)
                    targetDir.add(file) as? PsiFile
                }

            val vFile = created?.virtualFile
            if (vFile != null) {
                FileEditorManager.getInstance(project).openFile(vFile, true)
            } else {
                notifyError(project, "Failed to create $FILE_NAME.")
            }
        }
    }

    private data class SourceRoot(val module: Module, val virtualFile: VirtualFile, val isTest: Boolean) {
        val displayName: String
            get() {
                val kind = if (isTest) "test" else "main"
                return "${module.name}  ($kind: ${virtualFile.path})"
            }
    }

    private fun collectSourceRoots(project: Project, type: JavaSourceRootType): List<SourceRoot> =
        ModuleManager.getInstance(project).modules.flatMap { module ->
            ModuleRootManager.getInstance(module)
                .getSourceRoots(type)
                .map { SourceRoot(module, it, type == JavaSourceRootType.TEST_SOURCE) }
        }

    private fun chooseRoot(
        project: Project,
        roots: List<SourceRoot>,
        onChosen: (SourceRoot) -> Unit,
    ) {
        if (roots.size == 1) {
            onChosen(roots.first())
            return
        }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(roots)
            .setTitle("Choose Source Root for UseCase.java")
            .setItemChosenCallback { onChosen(it) }
            .setRenderer { list, value, index, isSelected, cellHasFocus ->
                javax.swing.JLabel(value.displayName).apply {
                    isOpaque = true
                    border = javax.swing.BorderFactory.createEmptyBorder(2, 8, 2, 8)
                    if (isSelected) {
                        background = list.selectionBackground
                        foreground = list.selectionForeground
                    } else {
                        background = list.background
                        foreground = list.foreground
                    }
                }
            }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    private fun choosePackage(project: Project): String? {
        val dialog = PackageChooserDialog("Choose Target Package for UseCase.java", project)
        if (!dialog.showAndGet()) return null
        return dialog.selectedPackage?.qualifiedName.orEmpty()
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
