package org.sonarlint.intellij.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import javax.swing.JComponent

class ProjectSelectionDialog : DialogWrapper(true) {
    var selectedProject: Project? = null

    init {
        super.init()
        title = "Select a project"
    }

    override fun createActions() = arrayOf(cancelAction)

    override fun createCenterPanel(): JComponent? {
        return SelectProjectPanel(::onProjectSelected)
    }

    private fun onProjectSelected(project: Project) {
        selectedProject = project
        close(OK_EXIT_CODE)
    }
}
