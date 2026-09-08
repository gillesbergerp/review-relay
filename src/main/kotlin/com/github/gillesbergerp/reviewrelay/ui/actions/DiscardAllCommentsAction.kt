package com.github.gillesbergerp.reviewrelay.ui.actions

import com.github.gillesbergerp.reviewrelay.ui.editor.InlineCommentManager
import com.github.gillesbergerp.reviewrelay.review.service.ReviewSessionService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages

/** Named for the comments, not the session: the session picker sits right above it. */
class DiscardAllCommentsAction : DumbAwareAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val result = Messages.showYesNoDialog(
            project,
            "Delete every review comment and the summary? This does not affect the agent.",
            "Discard All Comments",
            Messages.getQuestionIcon()
        )
        if (result == Messages.YES) {
            InlineCommentManager.getInstance(project).clearAllInlineComments()
            ReviewSessionService.getInstance(project).clearSession()
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /** Nothing to discard is not worth a confirmation dialog about deleting nothing. */
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = project != null
        e.presentation.isEnabled = project != null && with(ReviewSessionService.getInstance(project).currentSession) {
            threads.isNotEmpty() || summary.isNotBlank()
        }
    }
}
