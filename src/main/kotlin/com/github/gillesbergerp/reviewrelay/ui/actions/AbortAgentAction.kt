package com.github.gillesbergerp.reviewrelay.ui.actions

import com.github.gillesbergerp.reviewrelay.backend.BackendService
import com.github.gillesbergerp.reviewrelay.backend.BackendTask
import com.github.gillesbergerp.reviewrelay.review.service.ReviewPublisher
import com.github.gillesbergerp.reviewrelay.review.service.ReviewSessionService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

class AbortAgentAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ReviewPublisher.abort(project)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val service = project?.let { BackendService.getInstance(it) }
        val backend = service?.activeBackend
        val reviews = project?.let { ReviewSessionService.getInstance(it) }
        val session = reviews?.let { it.review(it.activeReviewId)?.conversation?.sessionId }
        // Disabled rather than hidden: a button that comes and goes with the chosen agent moved the
        // whole row, and the picker that had just been clicked with it.
        e.presentation.isVisible = project != null
        e.presentation.isEnabled = service != null && backend?.interrupt != null &&
            backend.activity?.isBusy(session) == true &&
            !service.isRunning(BackendTask.INTERRUPT, service.activeConversation)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
