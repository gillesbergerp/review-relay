package com.github.gillesbergerp.reviewrelay.ui.actions

import com.github.gillesbergerp.reviewrelay.backend.BackendService
import com.github.gillesbergerp.reviewrelay.backend.BackendTask
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

class ReconnectAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = BackendService.getInstance(project)
        service.activeBackend.reconnect?.let { reset ->
            reset.reconnect()
            // Off the EDT: discovery can spend seconds pinging candidates that are not there.
            service.refreshAsync()
        }
    }

    // Discovery pings every candidate in turn, so a second one started on top only makes it slower.
    override fun update(e: AnActionEvent) {
        val project = e.project
        val service = project?.let { BackendService.getInstance(it) }
        e.presentation.isVisible = project != null
        e.presentation.isEnabled = service?.activeBackend?.reconnect != null &&
            !service.isRunning(BackendTask.CONNECT)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
