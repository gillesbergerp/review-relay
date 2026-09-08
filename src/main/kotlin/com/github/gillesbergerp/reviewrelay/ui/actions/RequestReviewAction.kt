package com.github.gillesbergerp.reviewrelay.ui.actions

import com.github.gillesbergerp.reviewrelay.backend.BackendService
import com.github.gillesbergerp.reviewrelay.backend.BackendTask
import com.github.gillesbergerp.reviewrelay.backend.PublishOutcome
import com.github.gillesbergerp.reviewrelay.review.ReviewText
import com.github.gillesbergerp.reviewrelay.review.changes.ReviewedChangesService
import com.github.gillesbergerp.reviewrelay.review.service.ReviewSessionService
import com.github.gillesbergerp.reviewrelay.review.service.notifyReview
import com.github.gillesbergerp.reviewrelay.review.service.reason
import com.github.gillesbergerp.reviewrelay.backend.mcp.ReviewAccess
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project

/**
 * Asks the review's own agent to go over the changes and propose what it finds.
 *
 * Nothing it proposes is part of the review: it lands in the list for the reviewer to add or
 * dismiss, which is why this can be pressed without committing to anything.
 */
class RequestReviewAction : DumbAwareAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = BackendService.getInstance(project)
        val reviews = ReviewSessionService.getInstance(project)
        val backend = service.backendOf(reviews.activeReviewId)
        val prompting = backend.prompting ?: return
        val conversation = service.activeConversation
        val session = conversation?.sessionId
        if (!service.begin(BackendTask.PUBLISH, conversation)) return

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Asking ${backend.displayName} to review", false) {
                private var outcome: PublishOutcome? = null

                override fun run(indicator: ProgressIndicator) {
                    outcome = prompting.ask(session, prompt(project))
                }

                override fun onFinished() = service.end(BackendTask.PUBLISH, conversation)

                override fun onSuccess() = when (val said = outcome) {
                    is PublishOutcome.Delivered -> {
                        // The progress ends when the prompt lands, so only this says when it is done.
                        service.awaitIdleNotification(backend, session)
                        notifyReview(project, "Asked ${said.target} to review. Anything it finds arrives as a proposal.", NotificationType.INFORMATION)
                    }
                    is PublishOutcome.Published -> notifyReview(project, said.hint, NotificationType.INFORMATION)
                    is PublishOutcome.Blocked -> notifyReview(project, said.reason, NotificationType.WARNING)
                    null -> Unit
                }

                override fun onThrowable(error: Throwable) = notifyReview(
                    project,
                    "Could not ask for a review: ${reason(error)}",
                    NotificationType.ERROR,
                )
            },
        )
    }

    /** Names what is under review and the one tool the answer comes back through. */
    private fun prompt(project: Project): String {
        val active = ReviewSessionService.getInstance(project).activeReviewId
        val about = ReviewedChangesService.getInstance(project).comparedWith(active).takeIf { it.isNotBlank() }
        return buildString {
            append("Review the code ")
            append(about?.let { "$it " } ?: "you have changed ")
            appendLine("and report what you find as review comments.")
            appendLine()
            appendLine(
                "Call review_propose once per finding, with the file, the lines and what is wrong. " +
                    "Do not change any code and do not answer here: each proposal goes in front of " +
                    "the reviewer, who decides whether it becomes a comment."
            )
            appendLine()
            append(ReviewText.LEGEND)
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val service = project?.let { BackendService.getInstance(it) }
        val reviews = project?.let { ReviewSessionService.getInstance(it) }
        val backend = reviews?.let { service?.backendOf(it.activeReviewId) }
        // Disabled rather than hidden, like the rest of this row: a button that comes and goes with
        // the chosen agent moves everything beside it.
        e.presentation.isVisible = project != null
        // Pointless without the tool to answer through, whatever the backend can be told.
        e.presentation.isEnabled = service != null && backend?.prompting != null && ReviewAccess.ready &&
            !service.isRunning(BackendTask.PUBLISH, service.activeConversation)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
