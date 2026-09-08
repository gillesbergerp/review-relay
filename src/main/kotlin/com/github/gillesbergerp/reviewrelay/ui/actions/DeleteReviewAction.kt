package com.github.gillesbergerp.reviewrelay.ui.actions

import com.github.gillesbergerp.reviewrelay.review.service.ReviewSessionService
import com.github.gillesbergerp.reviewrelay.ui.toolwindow.deleteReviewAndItsLog
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowContextMenuActionBase
import com.intellij.ui.content.Content

class DeleteReviewAction : ToolWindowContextMenuActionBase(), DumbAware {

    override fun update(e: AnActionEvent, toolWindow: ToolWindow, content: Content?) {
        e.presentation.isEnabledAndVisible = toolWindow.id == REVIEW_RELAY && content.reviewId() != null
    }

    override fun actionPerformed(e: AnActionEvent, toolWindow: ToolWindow, content: Content?) {
        val project = e.project ?: return
        val id = content.reviewId() ?: return
        val service = ReviewSessionService.getInstance(project)
        val review = service.review(id) ?: return
        val losses = when {
            review.threads.isEmpty() -> "It has no comments."
            else -> "${review.pending} pending, ${review.threads.size - review.pending} already sent."
        }
        val answer = Messages.showYesNoDialog(
            project,
            "Delete \"${review.name}\"? $losses This cannot be undone.",
            "Delete Review",
            Messages.getWarningIcon(),
        )
        if (answer == Messages.YES) deleteReviewAndItsLog(project, id)
    }
}
