package com.github.gillesbergerp.reviewrelay.ui.actions

import com.github.gillesbergerp.reviewrelay.backend.BackendService
import com.github.gillesbergerp.reviewrelay.backend.BackendTask
import com.github.gillesbergerp.reviewrelay.review.service.ReviewPublisher
import com.github.gillesbergerp.reviewrelay.review.service.ReviewSessionService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

class SendReviewAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ReviewPublisher.publish(project)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = project != null
        val service = project?.let { BackendService.getInstance(it) }
        val sending = service?.isRunning(BackendTask.PUBLISH, service.activeConversation) == true
        val reviews = project?.let { ReviewSessionService.getInstance(it) }
        val pending = reviews?.pendingThreads?.size ?: 0
        val summary = reviews?.currentSession?.summary?.isNotBlank() == true
        // The wording is the backend saying what it will do, not a guess about who is listening.
        val delivery = service?.activeBackend?.delivery
        // The same rule as the button under the summary, which used to be the only one that knew:
        // from Find Action this offered a send that could only answer "nothing to send".
        e.presentation.isEnabled = project != null && !sending && (pending > 0 || summary)
        e.presentation.text = when {
            sending -> delivery?.gerund ?: "Sending..."
            pending > 0 -> (delivery?.verb ?: "Send Review") + " ($pending)"
            else -> delivery?.verb ?: "Send Review"
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
