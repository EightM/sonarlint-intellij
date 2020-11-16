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

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import org.sonarlint.intellij.config.Settings.getGlobalSettings
import org.sonarlint.intellij.config.Settings.getSettingsFor
import org.sonarlint.intellij.config.global.SonarQubeServer
import org.sonarlint.intellij.config.global.wizard.NewConnectionWizard
import org.sonarlint.intellij.issue.hotspot.SecurityHotspotOpener
import org.sonarlint.intellij.ui.ProjectSelectionDialog
import org.sonarlint.intellij.util.SonarLintUtils
import org.sonarsource.sonarlint.core.WsHelperImpl
import org.sonarsource.sonarlint.core.client.api.connected.GetSecurityHotspotRequestParams
import org.sonarsource.sonarlint.core.client.api.connected.RemoteHotspot

open class SecurityHotspotOrchestrator(private val opener: SecurityHotspotOpener = SecurityHotspotOpener()) {

    open fun open(projectKey: String, hotspotKey: String, serverUrl: String) {
        val connection = getOrCreateConnectionTo(serverUrl) ?: return
        val project = getOrBindTargetProject(projectKey, connection) ?: return
        val remoteHotspot = fetchHotspot(connection, hotspotKey, projectKey) ?: return //TODO show balloon
        opener.open(project, remoteHotspot)
    }

    private fun createConnectionTo(serverUrl: String): SonarQubeServer? {
        val message = "There is no connection configured to $serverUrl."
        val result = Notifier.showYesNoModalWindow(message, "Create connection")
        if (result == Messages.OK) {
            return NewConnectionWizard().open(serverUrl)
        }
        return null
    }

    private fun getOrBindTargetProject(projectKey: String, connection: SonarQubeServer): Project? {
        return getTargetProjectAmongOpened(projectKey, connection) ?: selectAndBindTargetProject(projectKey, connection)
    }

    private fun selectAndBindTargetProject(projectKey: String, connection: SonarQubeServer): Project? {
        val selectedProject = selectProject(projectKey, connection.hostUrl) ?: return null
        if (!getSettingsFor(selectedProject).isBoundTo(projectKey, connection)) {
            val bound = bindProject(selectedProject, projectKey, connection)
            if (!bound) {
                return null
            }
        }
        return selectedProject
    }

    private fun selectProject(projectKey: String, hostUrl: String): Project? {
        return if (shouldSelectProject(projectKey, hostUrl)) Notifier.showProjectNotOpenedWindow() else null
    }

    private fun shouldSelectProject(projectKey: String, hostUrl: String): Boolean {
        val message = "Cannot automatically find a project bound to:\n" +
                "  * Project: $projectKey\n" +
                "  * Server: $hostUrl\n" +
                "Please manually select a project."
        val result = Notifier.showYesNoModalWindow(message, "Select project")
        return result == Messages.OK
    }

    private fun getTargetProjectAmongOpened(projectKey: String, connection: SonarQubeServer): Project? {
        return ProjectManager.getInstance().openProjects
                .find { getSettingsFor(it).isBoundTo(projectKey, connection) }
    }

    private fun bindProject(project: Project, projectKey: String, connection: SonarQubeServer): Boolean {
        val result = Notifier.showYesNoModalWindow("You are going to bind current project to ${connection.hostUrl}. Do you agree?", "Yes")
        if (result == Messages.OK) {
            getSettingsFor(project).bindTo(connection, projectKey)
        }
        return result == Messages.OK
    }

    private fun fetchHotspot(connection: SonarQubeServer, hotspotKey: String, projectKey: String): RemoteHotspot? {
        val optionalRemoteHotspot = WsHelperImpl().getHotspot(SonarLintUtils.getServerConfiguration(connection), GetSecurityHotspotRequestParams(hotspotKey, projectKey))
        return optionalRemoteHotspot.orElse(null)
    }

    private fun getOrCreateConnectionTo(serverUrl: String): SonarQubeServer? {
        val connectionsToServer = getGlobalSettings().getAllConnectionsTo(serverUrl)
        // we pick the first connection but this could lead to issues later if there are several matches
        return connectionsToServer.getOrElse(0) { createConnectionTo(serverUrl) }
    }
}

object Notifier {

    fun showYesNoModalWindow(message: String, yesText: String): Int {
        return Messages.showYesNoDialog(null, message, "Couldn't open security hotspot", yesText, "Cancel", Messages.getWarningIcon())
    }

    fun showProjectNotOpenedWindow(): Project? {
        val wrapper = ProjectSelectionDialog()
        wrapper.show()
        return wrapper.selectedProject
    }

}
