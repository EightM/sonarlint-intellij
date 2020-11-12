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
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import org.sonarlint.intellij.config.Settings
import org.sonarlint.intellij.config.global.SonarQubeServer
import org.sonarlint.intellij.config.global.wizard.NewConnectionWizard
import org.sonarlint.intellij.core.ProjectBindingManager
import org.sonarlint.intellij.issue.hotspot.SecurityHotspotOpener
import org.sonarlint.intellij.ui.SelectProjectPanel
import org.sonarlint.intellij.util.SonarLintUtils
import java.awt.Point

open class SecurityHotspotOrchestrator(private val opener: SecurityHotspotOpener = SecurityHotspotOpener()) {

    open fun openNew(projectKey: String, hotspotKey: String, serverUrl: String) {
        ensureConnectionConfigured(serverUrl)
                .then { connection ->
                    ensureProjectIsBound(projectKey, serverUrl)
                            .then {
                                opener.open(hotspotKey, it)
                            }
                }
    }

    private fun ensureProjectIsBound(projectKey: String, serverUrl: String): Promise<Project> {
        val project = opener.getProject(projectKey, serverUrl)
        val projectPromise = AsyncPromise<Project>()
        val boundProjectPromise = AsyncPromise<Project>()
        if (project != null) {
            boundProjectPromise.setResult(project)
        } else {
            selectProject(projectPromise)
            projectPromise.then { selectedProject ->
                afterProjectIsBound(selectedProject, projectKey, serverUrl)
                        .then { boundProjectPromise.setResult(it) }
            }
        }
        return boundProjectPromise
    }

    private fun ensureConnectionConfigured(serverUrl: String): Promise<SonarQubeServer> {
        val listOfConnectionsToServer = Settings.getGlobalSettings().getAllConnectionsTo(serverUrl)
        val promise = AsyncPromise<SonarQubeServer>()
        if (listOfConnectionsToServer.isEmpty()) {
            val message = "There is no connection configured to $serverUrl."
            Notifier.showYesNoModalWindow(message, "Create connection") {
                promise.setResult(NewConnectionWizard().open(serverUrl))
            }
        } else {
            promise.setResult(listOfConnectionsToServer.first())
        }
        return promise
    }

    private fun selectProject(projectPromise: AsyncPromise<Project>) {
        Notifier.showProjectNotOpenedWindow(projectPromise)
    }

    fun afterProjectIsBound(project: Project, projectKey: String, serverUrl: String): Promise<Project> {
        val projectBindingManager = SonarLintUtils.getService(project, ProjectBindingManager::class.java)
        val projectPromise = AsyncPromise<Project>()
        if (!projectBindingManager.isBoundTo(projectKey, serverUrl)) {
            val connection = Settings.getGlobalSettings().sonarQubeServers.first { it.hostUrl == serverUrl }
            val bindProject = {
                val projectSettings = Settings.getSettingsFor(project)
                projectSettings.isBindingEnabled = true
                projectSettings.serverId = connection.name
                projectSettings.projectKey = projectKey
                projectPromise.setResult(project)
            }
            Notifier.showYesNoModalWindow("You are going to bind current project to $serverUrl. Do you agree?", "Yes", bindProject)
        } else {
            projectPromise.setResult(project)
        }
        return projectPromise
    }
}

object Notifier {

    fun showYesNoModalWindow(message: String, yesText: String, callback: () -> Unit) {
        val result = Messages.showYesNoDialog(null, message, "Couldn't open security hotspot", yesText, "Cancel", Messages.getWarningIcon())
        if (result == Messages.OK) {
            callback()
        }
    }

    fun showProjectNotOpenedWindow(projectPromise: AsyncPromise<Project>) {
        val openProjectPanel = SelectProjectPanel(projectPromise)

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
