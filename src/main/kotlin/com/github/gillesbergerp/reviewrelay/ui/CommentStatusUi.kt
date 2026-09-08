package com.github.gillesbergerp.reviewrelay.ui

import com.github.gillesbergerp.reviewrelay.review.model.ThreadStatus
import com.github.gillesbergerp.reviewrelay.review.model.ReviewThread
import com.intellij.icons.AllIcons
import javax.swing.Icon

/** How a comment's standing is shown, in one place so the list and the inline comment agree. */
object CommentStatusUi {

    /** A proposal has no standing in the review yet, so its icon says what it is rather than where. */
    val PROPOSAL: Icon = AllIcons.Actions.IntentionBulb

    fun icon(status: ThreadStatus): Icon = when (status) {
        ThreadStatus.PENDING -> AllIcons.General.Modified
        ThreadStatus.SENT -> AllIcons.Vcs.Push
        ThreadStatus.ANSWERED -> AllIcons.General.Balloon
        ThreadStatus.RESOLVED -> AllIcons.General.InspectionsOK
        ThreadStatus.WONT_FIX -> AllIcons.Actions.Cancel
    }

    /**
     * What is true of a thread, as the words a footer strings together.
     *
     * What was observed about the code only means anything while a comment is out.
     */
    fun standing(comment: ReviewThread): List<String> = when {
        comment.unread -> listOf("new reply")
        comment.status != ThreadStatus.SENT -> listOf(comment.status.label)
        comment.codeChanged == true -> listOf("sent", "lines changed")
        comment.codeChanged == false -> listOf("sent", "lines unchanged")
        else -> listOf("sent")
    }
}
