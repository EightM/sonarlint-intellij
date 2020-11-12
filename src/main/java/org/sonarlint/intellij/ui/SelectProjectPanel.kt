package org.sonarlint.intellij.ui

import com.intellij.ide.IdeBundle
import com.intellij.ide.actions.OpenProjectFileChooserDescriptor
import com.intellij.ide.highlighter.ProjectFileType
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.impl.FileChooserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.PlatformProjectOpenProcessor
import com.intellij.projectImport.ProjectAttachProcessor
import com.intellij.util.ui.cloneDialog.VcsCloneDialog
import org.jetbrains.concurrency.AsyncPromise
import java.nio.file.Paths
import javax.swing.JButton
import javax.swing.JPanel


class SelectProjectPanel(val projectPromise: AsyncPromise<Project>) : JPanel(), Disposable {

    private val recentProjectPanel = SonarLintRecentProjectPanel()
    private val openProjectButton = JButton("Open Project")
    private val newProjectFromVcsButton = JButton("New Project from VCS...")
    val cancelButton = JButton("Cancel")

    init {
        add(recentProjectPanel)
        add(openProjectButton)
        add(newProjectFromVcsButton)
        add(cancelButton)

        recentProjectPanel.projectPromise = projectPromise

        openProjectButton.addActionListener {
            val descriptor: FileChooserDescriptor = OpenProjectFileChooserDescriptor(false)

            FileChooser.chooseFile(descriptor, null, VfsUtil.getUserHomeDir()) { file: VirtualFile ->
                if (!descriptor.isFileSelectable(file)) {
                    val message = IdeBundle.message("error.dir.contains.no.project", file.presentableUrl)
                    Messages.showInfoMessage(null as Project?, message, IdeBundle.message("title.cannot.open.project"))
                    return@chooseFile
                }
                val project = doOpenFile(file) ?: return@chooseFile
                projectPromise.setResult(project)
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
        val answer = shouldOpenNewProject(file)
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

private fun shouldOpenNewProject(file: VirtualFile): Int {
    if (file.fileType is ProjectFileType) {
        return Messages.YES
    }
    return askConfirmationForOpeningProject(file)
}

fun askConfirmationForOpeningProject(file: VirtualFile): Int {
    return Messages.showYesNoCancelDialog(null,
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
