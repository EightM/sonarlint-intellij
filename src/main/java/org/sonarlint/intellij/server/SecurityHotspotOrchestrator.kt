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

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.sonarlint.intellij.config.Settings
import org.sonarlint.intellij.config.Settings.getSettingsFor
import org.sonarlint.intellij.config.global.SonarQubeServer
import org.sonarlint.intellij.config.global.wizard.NewConnectionWizard
import org.sonarlint.intellij.issue.hotspot.SecurityHotspotOpener
import org.sonarlint.intellij.ui.SelectProjectPanel
import java.awt.Point
import kotlin.coroutines.suspendCoroutine

open class SecurityHotspotOrchestrator(private val opener: SecurityHotspotOpener = SecurityHotspotOpener()) {

    open suspend fun open(projectKey: String, hotspotKey: String, serverUrl: String) {
        val connection = getOrCreateConnectionTo(serverUrl) ?: return
        val project = getOrBindTargetProject(projectKey, connection)
        opener.open(hotspotKey, project)
    }

    private suspend fun getOrBindTargetProject(projectKey: String, connection: SonarQubeServer): Project = suspendCoroutine { continuation ->
        val project = getTargetProjectAmongOpened(projectKey, connection)
        if (project != null) {
            // TODO better to return here
            continuation.resumeWith(Result.success(project))
        } else {
            // TODO and use suspend here
            GlobalScope.launch {
                val selectedProject = Notifier.showProjectNotOpenedWindow()
                if (!getSettingsFor(selectedProject).isBoundTo(projectKey, connection)) {
                    val shouldContinue = bindProject(selectedProject, projectKey, connection)
                    if (shouldContinue == Messages.OK) continuation.resumeWith(Result.success(selectedProject))
                }
            }
        }
    }

    fun getTargetProjectAmongOpened(projectKey: String, connection: SonarQubeServer): Project? {
        return ProjectManager.getInstance().openProjects
                .find { getSettingsFor(it).isBoundTo(projectKey, connection) }
    }

    private suspend fun getOrCreateConnectionTo(serverUrl: String): SonarQubeServer? = suspendCoroutine {
        val listOfConnectionsToServer = Settings.getGlobalSettings().getAllConnectionsTo(serverUrl)
        if (listOfConnectionsToServer.isEmpty()) {
            val message = "There is no connection configured to $serverUrl."
            runInEdt {
                val result = Notifier.showYesNoModalWindow(message, "Create connection")
                if (result == Messages.OK) {
                    val server = NewConnectionWizard().open(serverUrl)
                    it.resumeWith(Result.success(server))
                }
            }
        } else {
            it.resumeWith(Result.success(listOfConnectionsToServer.first()))
        }
    }

    suspend fun bindProject(project: Project, projectKey: String, connection: SonarQubeServer): Int = suspendCoroutine {
        runInEdt {
            val result = Notifier.showYesNoModalWindow("You are going to bind current project to ${connection.hostUrl}. Do you agree?", "Yes")
            if (result == Messages.OK) {
                getSettingsFor(project).bindTo(connection, projectKey)
            }
            it.resumeWith(Result.success(result))
        }
    }
}

object Notifier {

    fun showYesNoModalWindow(message: String, yesText: String): Int {
        return Messages.showYesNoDialog(null, message, "Couldn't open security hotspot", yesText, "Cancel", Messages.getWarningIcon())
    }

    suspend fun showProjectNotOpenedWindow(): Project = suspendCoroutine {
        runInEdt {
            val openProjectPanel = SelectProjectPanel(it)

            val builder = JBPopupFactory.getInstance().createComponentPopupBuilder(openProjectPanel, openProjectPanel)
            val popup = builder
                    .setTitle("SL Open Project")
                    .setMovable(true)
                    .setResizable(true)
                    .setRequestFocus(true)
                    .createPopup()
            popup.show(RelativePoint(Point(300, 300)))

            openProjectPanel.cancelButton.addActionListener {
                popup.cancel()
            }
        }
    }

}
