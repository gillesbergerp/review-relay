package com.github.gillesbergerp.reviewrelay.ui.actions

import com.github.gillesbergerp.reviewrelay.ui.editor.InlineCommentManager
import com.github.gillesbergerp.reviewrelay.review.service.ReviewSessionService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

class ClearClosedCommentsAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = ReviewSessionService.getInstance(project)
        val inline = InlineCommentManager.getInstance(project)
        service.currentSession.threads
            .filter { !it.status.open }
            .forEach { inline.removeInlineComment(it.id) }
        service.removeClosedThreads()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = project != null
        e.presentation.isEnabled = project != null &&
            ReviewSessionService.getInstance(project).currentSession.threads.any { !it.status.open }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
