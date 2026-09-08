package com.github.gillesbergerp.reviewrelay.ui

import com.github.gillesbergerp.reviewrelay.review.model.ThreadStatus
import com.intellij.icons.AllIcons
import javax.swing.Icon

/**
 * What can be done to a thread from outside its text, as an icon, in the comment and in the list.
 *
 * Editing is not here: it belongs to one message rather than to the thread, and replying is the
 * box at the foot of the comment rather than a button.
 */
enum class CommentAction(val label: String, val icon: Icon) {
    // Not Vcs.Push, which CommentStatusUi uses for a comment that has been sent: on a sent card the
    // same arrow appeared twice in one row, once meaning a state and once meaning a button.
    SEND("Send this comment on its own", AllIcons.Actions.Upload),
    RESOLVE("Resolve", AllIcons.Actions.Checked),
    WONT_FIX("Won't fix", AllIcons.Actions.Cancel),
    DELETE("Delete", AllIcons.Actions.GC);

    companion object {

        val EDIT_ICON: Icon = AllIcons.Actions.Edit

        /** All of them, always: an icon that comes and goes is harder to find than a dim one. */
        fun forStatus(status: ThreadStatus): List<CommentAction> = entries

        /** Closing means accepting an answer, so there has to be one out there to accept. */
        fun enabledFor(action: CommentAction, status: ThreadStatus): Boolean = when (action) {
            DELETE -> true
            SEND -> status == ThreadStatus.PENDING
            RESOLVE, WONT_FIX -> status == ThreadStatus.SENT || status == ThreadStatus.ANSWERED
        }
    }
}

/**
 * What can be done to a proposal, which is neither more nor less than deciding about it.
 *
 * Its own enum rather than more of [CommentAction]: nothing here is ever offered on a comment, and
 * an action that is always dim on every card of one kind is worse than an action that is not there.
 */
enum class ProposalAction(val label: String, val icon: Icon) {
    FILE("Add to the review", AllIcons.General.Add),
    EDIT("Reword and add", CommentAction.EDIT_ICON),

    // Not Actions.Cancel, which means Won't fix, and not Actions.GC, which means delete: dismissing
    // is neither a judgement on the code nor the loss of anything the reviewer wrote.
    DISMISS("Dismiss", AllIcons.Actions.Close);
}
