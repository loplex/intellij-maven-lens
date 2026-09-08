package cz.loplex.intellijmavenlens

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * Toolbar switch in the Maven tool window turning the plugin-library attachment on and off,
 * next to the Maven plugin's own toggles ("Toggle Offline Mode", "Skip Tests").
 *
 * Switching off detaches the libraries Maven Lens attached rather than merely freezing them:
 * a switched-off plugin that still leaves Maven plugin internals in "Go to Class" would be
 * indistinguishable from one that is still running.
 */
class ToggleMavenLensAction : ToggleAction(), DumbAware {

    // Reads nothing but a settings service, so it must not occupy the EDT.
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project
        e.presentation.isEnabledAndVisible =
            project != null && MavenProjectsManager.getInstance(project).isMavenizedProject
    }

    override fun isSelected(e: AnActionEvent): Boolean =
        e.project?.service<MavenLensSettings>()?.enabled == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        project.service<MavenLensSettings>().enabled = state
        project.service<MavenLensService>().scheduleApplyEnabledState()
    }
}
