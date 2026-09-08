package com.github.gillesbergerp.reviewrelay.ui.actions

import com.github.gillesbergerp.reviewrelay.ui.editor.relativePath
import com.github.gillesbergerp.reviewrelay.ui.editor.revisionOf
import com.github.gillesbergerp.reviewrelay.review.model.CommentTarget
import com.github.gillesbergerp.reviewrelay.review.model.ReviewedFile
import com.github.gillesbergerp.reviewrelay.review.model.MessageAuthor
import com.github.gillesbergerp.reviewrelay.review.model.ReviewMessage
import com.github.gillesbergerp.reviewrelay.review.model.ReviewThread
import com.github.gillesbergerp.reviewrelay.review.service.ReviewSessionService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vcs.VcsDataKeys

class AddFileCommentAction : DumbAwareAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val path = changedFile(e) ?: return
        val change = e.getData(VcsDataKeys.CHANGES)?.firstOrNull()

        val dialog = CommentDialog(project, "Add File Comment", file = path)
        if (dialog.showAndGet()) {
            val comment = ReviewThread(
                type = dialog.selectedType,
                target = CommentTarget.File(path, change?.let { revisionOf(it) }),
                messages = listOf(
                    ReviewMessage(author = MessageAuthor.REVIEWER, text = dialog.commentText),
                ),
            )
            ReviewSessionService.getInstance(project).addThread(comment)
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = changedFile(e) != null
    }

    /** Null outside the project: the agent cannot be pointed at a file this checkout does not hold. */
    private fun changedFile(e: AnActionEvent): ReviewedFile? {
        val project = e.project ?: return null
        val change = e.getData(VcsDataKeys.CHANGES)?.firstOrNull() ?: return null
        val file = change.afterRevision?.file ?: change.beforeRevision?.file ?: return null
        return relativePath(project, file.path)
    }
}
