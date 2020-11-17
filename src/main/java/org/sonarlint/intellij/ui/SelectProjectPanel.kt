package org.sonarlint.intellij.ui

import com.intellij.ide.IdeBundle
import com.intellij.ide.actions.OpenProjectFileChooserDescriptor
import com.intellij.ide.highlighter.ProjectFileType
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.impl.FileChooserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.PlatformProjectOpenProcessor
import com.intellij.projectImport.ProjectAttachProcessor
import java.nio.file.Paths
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel


class SelectProjectPanel(private val onProjectSelected: (Project) -> Unit) : JPanel() {

    init {
        val openProjectButton = JButton("Open or import")

        layout = VerticalFlowLayout(VerticalFlowLayout.TOP, 5, 15, false, false)
        add(openProjectButton)
        add(JLabel("or"))
        add(SonarLintRecentProjectPanel(onProjectSelected))

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