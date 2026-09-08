package com.github.gillesbergerp.reviewrelay.review.service

import com.github.gillesbergerp.reviewrelay.ui.editor.InlineCommentManager
import com.github.gillesbergerp.reviewrelay.review.model.MessageAuthor
import com.github.gillesbergerp.reviewrelay.review.model.ReviewMessage
import com.github.gillesbergerp.reviewrelay.review.ReviewText
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

sealed interface Recorded {
    data object Ok : Recorded
    data class NoSuchComment(val handle: String) : Recorded
}

/**
 * Records an agent answer against the comment it is about, whichever door it arrived through.
 *
 * Knowing a handle is itself evidence the agent was sent this comment, so it is the credential that
 * decides. A thread the IDE never managed to mark as sent is a bookkeeping failure on this side, not
 * a reason to throw the answer away: record it and repair.
 */
fun recordReply(project: Project, handle: String, note: String): Recorded {
    val service = ReviewSessionService.getInstance(project)
    val (review, thread) = service.threadAnywhere(ReviewText::handle, handle)
        ?: return Recorded.NoSuchComment(handle)

    val unrecorded = thread.messages.none { it.sentAt != null }
    ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed) return@invokeLater
        if (unrecorded) service.markSent(review.id, mapOf(thread.id to thread.sentBase))
        service.addMessage(review.id, thread.id, ReviewMessage(author = MessageAuthor.AGENT, text = note))
        InlineCommentManager.getInstance(project).redraw(thread.id)
    }
    return Recorded.Ok
}
