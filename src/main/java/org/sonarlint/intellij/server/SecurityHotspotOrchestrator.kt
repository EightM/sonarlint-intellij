/*
 * SonarLint for IntelliJ IDEA
 * Copyright (C) 2015-2020 SonarSource
 * sonarlint@sonarsource.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonarlint.intellij.server

import com.intellij.ide.IdeBundle
import com.intellij.ide.actions.OpenProjectFileChooserDescriptor
import com.intellij.ide.highlighter.ProjectFileType
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.impl.FileChooserUtil
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.PlatformProjectOpenProcessor
import com.intellij.projectImport.ProjectAttachProcessor
import com.intellij.projectImport.ProjectOpenProcessor
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.cloneDialog.VcsCloneDialog
import org.sonarlint.intellij.config.Settings
import org.sonarlint.intellij.config.project.SonarLintProjectConfigurable
import org.sonarlint.intellij.core.ProjectBindingManager
import org.sonarlint.intellij.config.global.wizard.NewConnectionWizard
import org.sonarlint.intellij.issue.hotspot.SecurityHotspotOpener
import org.sonarlint.intellij.issue.hotspot.SecurityHotspotOpeningResult
import org.sonarlint.intellij.ui.SonarLintRecentProjectPanel
import org.sonarlint.intellij.util.SonarLintUtils
import java.awt.Point
import java.nio.file.Paths
import javax.swing.JButton
import javax.swing.JPanel

open class SecurityHotspotOrchestrator(private val opener: SecurityHotspotOpener = SecurityHotspotOpener()) {

    private lateinit var projectKey: String
    private lateinit var hotspotKey: String
    private lateinit var serverUrl: String

    open fun open(projectKey: String, hotspotKey: String, serverUrl: String) {
        this.projectKey = projectKey
        this.hotspotKey = hotspotKey
        this.serverUrl = serverUrl
        do {
            val result = open()
            val shouldRetry = handleOpeningResult(result, projectKey, serverUrl)
        } while (shouldRetry)
    }

    private fun handleOpeningResult(result: SecurityHotspotOpeningResult, projectKey: String, serverUrl: String): Boolean {
        when (result) {
            SecurityHotspotOpeningResult.NO_MATCHING_CONNECTION -> {
                val message = "There is no connection configured to $serverUrl."
                return Notifier.showYesNoModalWindow(message, "Create connection") {
                    return@showYesNoModalWindow NewConnectionWizard().open(serverUrl)
                }
            }
            SecurityHotspotOpeningResult.PROJECT_NOT_FOUND -> {
                selectProject(projectKey, serverUrl)
                // return Notifier.showProjectNotBoundModalWindow(project, projectKey)
                return false

            }
        }
        return false
    }

    fun open(): SecurityHotspotOpeningResult {
        return opener.open(projectKey, hotspotKey, serverUrl)
    }

    private fun selectProject(projectKey: String, serverUrl: String) {
        Notifier.showProjectNotOpenedWindow { ensureProjectIsBound(it, projectKey, serverUrl) }
    }

    fun ensureProjectIsBound(project: Project, projectKey: String, serverUrl: String) {
        val projectBindingManager = SonarLintUtils.getService(project, ProjectBindingManager::class.java)
        if (!projectBindingManager.isBoundTo(projectKey, serverUrl)) {
            val connection = Settings.getGlobalSettings().sonarQubeServers.first { it.hostUrl == serverUrl }
            val bindProject = {
                val projectSettings = Settings.getSettingsFor(project)
                projectSettings.isBindingEnabled = true
                projectSettings.serverId = connection.name
                projectSettings.projectKey = projectKey
                open()
                true
            }
            Notifier.showYesNoModalWindow("You are going to bind current project to $serverUrl. Do you agree?", "Yes", bindProject)

        } else {
            open()
        }
    }
}

object Notifier {

    fun showYesNoModalWindow(message: String, yesText: String, callback: () -> Boolean): Boolean {
        val result = Messages.showYesNoDialog(null, message, "Couldn't open security hotspot", yesText, "Cancel", Messages.getWarningIcon())
        if (result == Messages.OK) {
            return callback()
        }
        return false
    }

    fun showProjectNotBoundModalWindow(project: Project, projectKey: String): Boolean {
        val projectBindingManager = SonarLintUtils.getService(project, ProjectBindingManager::class.java)
        val bindingButton = if (projectBindingManager.isBound) "Edit binding" else "Create binding"
        val message = "This project is not bound to the SonarQube server trying to open the hotspot"
        return showYesNoModalWindow(message, bindingButton) {
            val configurable = SonarLintProjectConfigurable(project)
            configurable.prefillProjectKey(projectKey)
            return@showYesNoModalWindow ShowSettingsUtil.getInstance().editConfigurable(project, configurable)
        }
    }

    fun showProjectNotOpenedWindow(listener: (project: Project) -> Unit) {

        val openProjectPanelNoWrapper = SelectProjectPanel(listener)

        val builder = JBPopupFactory.getInstance().createComponentPopupBuilder(openProjectPanelNoWrapper, openProjectPanelNoWrapper)
        val popup = builder
                .setTitle("SL Open Project")
                .setMovable(true)
                .setResizable(true)
                .setRequestFocus(true)
                .createPopup()
        popup.show(RelativePoint(Point(300, 300)))

        openProjectPanelNoWrapper.cancelButton.addActionListener {
            popup.cancel()
        }
    }

}

class SelectProjectPanel(val onProjectSelected: (project: Project) -> Unit) : JPanel(), Disposable {

    private val recentProjectPanel = SonarLintRecentProjectPanel()
    private val openProjectButton = JButton("Open Project")
    private val newProjectFromVcsButton = JButton("New Project from VCS...")
    val cancelButton = JButton("Cancel")

    init {
        add(recentProjectPanel)
        add(openProjectButton)
        add(newProjectFromVcsButton)
        add(cancelButton)

        recentProjectPanel.onProjectSelected = onProjectSelected

        openProjectButton.addActionListener {
            val descriptor: FileChooserDescriptor = OpenProjectFileChooserDescriptor(false)

            FileChooser.chooseFile(descriptor, null, VfsUtil.getUserHomeDir()) { file: VirtualFile ->
                if (!descriptor.isFileSelectable(file)) {
                    val message = IdeBundle.message("error.dir.contains.no.project", file.presentableUrl)
                    Messages.showInfoMessage(null as Project?, message, IdeBundle.message("title.cannot.open.project"))
                    return@chooseFile
                }
                val project = doOpenFile(file) ?: return@chooseFile
                onProjectSelected(project)
            }
        }

        newProjectFromVcsButton.addActionListener {
            val project = ProjectManager.getInstance().defaultProject
            val cloneDialog = VcsCloneDialog.Builder(project).forExtension()
            if (cloneDialog.showAndGet()) {
                cloneDialog.doClone(ProjectLevelVcsManager.getInstance(project).compositeCheckoutListener)
            }
        }

    }

    fun getProject(): Project {
        val openProjects = ProjectManager.getInstance().openProjects
        return if (openProjects.isNotEmpty()) openProjects[0]
        else ProjectManager.getInstance().defaultProject

    }

    override fun dispose() {

    }

}

private fun doOpenFile(file: VirtualFile): Project? {
    if (file.isDirectory) {
        val filePath = Paths.get(file.path)
        val canAttach = ProjectAttachProcessor.canAttachToProject()
        val openedProject = if (canAttach) {
            PlatformProjectOpenProcessor.doOpenProject(filePath, withProjectToClose(null))
        } else {
            ProjectUtil.openOrImport(file.path, null, false)
        }
        FileChooserUtil.setLastOpenedFile(openedProject, file)
        return openedProject
    }

    // try to open as a project - unless the file is an .ipr of the current one
    if (OpenProjectFileChooserDescriptor.isProjectFile(file)) {
        val answer = shouldOpenNewProject(null, file)
        if (answer == Messages.CANCEL) return null
        if (answer == Messages.YES) {
            val openedProject = ProjectUtil.openOrImport(file.path, null, false)
            if (openedProject != null) {
                FileChooserUtil.setLastOpenedFile(openedProject, file)
            }
            return openedProject
        }
    }
    return null
}

private fun shouldOpenNewProject(project: Project?, file: VirtualFile): Int {
    if (file.fileType is ProjectFileType) {
        return Messages.YES
    }
    val provider = ProjectOpenProcessor.getImportProvider(file) ?: return Messages.CANCEL
    return askConfirmationForOpeningProject(file, project)
}

fun askConfirmationForOpeningProject(file: VirtualFile, project: Project?): Int {
    return Messages.showYesNoCancelDialog(project,
            IdeBundle.message("message.open.file.is.project", file.name),
            IdeBundle.message("title.open.project"),
            IdeBundle.message("message.open.file.is.project.open.as.project"),
            IdeBundle.message("message.open.file.is.project.open.as.file"),
            IdeBundle.message("button.cancel"),
            Messages.getQuestionIcon())
}


fun withProjectToClose(projectToClose: Project?, forceOpenInNewFrame: Boolean = false): OpenProjectTask {
    return OpenProjectTask(projectToClose = projectToClose, forceOpenInNewFrame = forceOpenInNewFrame)
}
