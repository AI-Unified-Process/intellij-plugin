package ai.unifiedprocess.tools.ij

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class UseCaseAnnotationSetupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val properties = PropertiesComponent.getInstance(project)
        if (properties.getBoolean(DISMISSED_KEY)) return

        val hasAnnotation = smartReadAction(project) { UseCaseIndex.hasUseCaseAnnotation(project) }
        if (hasAnnotation) return

        val hasSpecs = smartReadAction(project) { UseCaseIndex.hasAnyUseCaseSpec(project) }
        if (!hasSpecs) return

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("AIUP")
            .createNotification(
                "AIUP: @UseCase annotation missing",
                "This project has Use Case specs but no <code>UseCase</code> annotation type. " +
                    "The plugin needs a Java annotation called <code>UseCase</code> with " +
                    "<code>id</code>, <code>scenario</code>, and <code>businessRules</code> attributes " +
                    "to wire Java tests to Markdown specs.",
                NotificationType.INFORMATION,
            )

        notification.addAction(object : NotificationAction("Create UseCase.java") {
            override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                CreateUseCaseAnnotationAction.run(project)
                properties.setValue(DISMISSED_KEY, true)
                notification.expire()
            }
        })
        notification.addAction(object : NotificationAction("Don't show again") {
            override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                properties.setValue(DISMISSED_KEY, true)
                notification.expire()
            }
        })

        notification.notify(project)
    }

    companion object {
        private const val DISMISSED_KEY = "aiup.useCase.scaffoldNotificationDismissed"
    }
}
