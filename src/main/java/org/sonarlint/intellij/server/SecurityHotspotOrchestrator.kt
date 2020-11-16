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
import kotlinx.coroutines.suspendCancellableCoroutine
import org.sonarlint.intellij.config.Settings.getGlobalSettings
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
        val project = getOrBindTargetProject(projectKey, connection) ?: return
        opener.open(hotspotKey, project)
    }

    private suspend fun getOrCreateConnectionTo(serverUrl: String): SonarQubeServer? {
        val connectionsToServer = getGlobalSettings().getAllConnectionsTo(serverUrl)
        // we pick the first connection but this could lead to issues later if there are several matches
        return connectionsToServer.getOrElse(0) { createConnectionTo(serverUrl) }
    }

    private suspend fun createConnectionTo(serverUrl: String): SonarQubeServer? = suspendCancellableCoroutine {
        runInEdt {
            val message = "There is no connection configured to $serverUrl."
            val result = Notifier.showYesNoModalWindow(message, "Create connection")
            if (result == Messages.OK) {
                val server = NewConnectionWizard().open(serverUrl)
                it.resumeWith(Result.success(server))
            } else {
                it.cancel()
            }
        }
    }

    private suspend fun getOrBindTargetProject(projectKey: String, connection: SonarQubeServer): Project? {
        return getTargetProjectAmongOpened(projectKey, connection) ?: selectAndBindTargetProject(projectKey, connection)
    }

    private suspend fun selectAndBindTargetProject(projectKey: String, connection: SonarQubeServer): Project? {
        val selectedProject = Notifier.showProjectNotOpenedWindow()
        if (!getSettingsFor(selectedProject).isBoundTo(projectKey, connection)) {
            val result = bindProject(selectedProject, projectKey, connection)
            if (result == Messages.CANCEL) {
                return null
            }
        }
        return selectedProject
    }

    fun getTargetProjectAmongOpened(projectKey: String, connection: SonarQubeServer): Project? {
        return ProjectManager.getInstance().openProjects
                .find { getSettingsFor(it).isBoundTo(projectKey, connection) }
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

    suspend fun showProjectNotOpenedWindow(): Project = suspendCancellableCoroutine { continuation ->
        runInEdt {
            val openProjectPanel = SelectProjectPanel { continuation.resumeWith(Result.success(it)) }

            val builder = JBPopupFactory.getInstance().createComponentPopupBuilder(openProjectPanel, openProjectPanel)
            val popup = builder
                    .setTitle("SL Open Project")
                    .setMovable(true)
                    .setResizable(true)
                    .setRequestFocus(true)
                    .createPopup()
            popup.show(RelativePoint(Point(300, 300)))

            openProjectPanel.cancelButton.addActionListener {
                continuation.cancel()
                popup.cancel()
            }
        }
    }

}
