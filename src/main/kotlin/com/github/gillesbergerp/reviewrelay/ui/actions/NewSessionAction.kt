package com.github.gillesbergerp.reviewrelay.ui.actions

import com.github.gillesbergerp.reviewrelay.backend.BackendService
import com.github.gillesbergerp.reviewrelay.backend.BackendTask
import com.github.gillesbergerp.reviewrelay.review.service.notifyReview
import com.github.gillesbergerp.reviewrelay.review.service.reason
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages

class NewSessionAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val title = Messages.showInputDialog(
            project,
            "Title for the new session:",
            "New Session",
            null,
            "Review: ${project.name}",
            null,
        )?.trim()?.takeIf { it.isNotEmpty() } ?: return

        val service = BackendService.getInstance(project)
        object : Task.Backgroundable(project, "Creating a session", false) {
            override fun run(indicator: ProgressIndicator) {
                // The claim used to live inside createSession; it has to stay somewhere.
                val backend = service.activeBackend
                    service.tracking(BackendTask.CREATE, service.creating(backend)) {
                        backend.sessions?.creation?.create(title)
                    }
                service.refreshAsync()
            }

            override fun onThrowable(error: Throwable) {
                notifyReview(
                    project,
                    "Could not create the session: ${reason(error)}",
                    NotificationType.ERROR,
                )
            }
        }.queue()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val service = project?.let { BackendService.getInstance(it) }
        e.presentation.isVisible = project != null
        e.presentation.isEnabled = service?.activeBackend?.sessions?.creation != null &&
            !service.isRunning(BackendTask.CREATE, service.creating(service.activeBackend))
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
