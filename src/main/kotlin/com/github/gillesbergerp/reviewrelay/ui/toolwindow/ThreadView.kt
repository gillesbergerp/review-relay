package com.github.gillesbergerp.reviewrelay.ui.toolwindow

import com.github.gillesbergerp.reviewrelay.review.model.Proposal
import com.github.gillesbergerp.reviewrelay.review.model.ReviewItem
import com.github.gillesbergerp.reviewrelay.review.model.ReviewThread
import com.github.gillesbergerp.reviewrelay.review.model.ReviewedFile
import com.github.gillesbergerp.reviewrelay.review.model.ThreadStatus
import com.github.gillesbergerp.reviewrelay.util.PathMatch

/**
 * Which threads the pane shows and in what order.
 *
 * Here rather than with the model: a review is the same review however it is listed, and both of
 * these are answers about the list on screen - one carries display strings, the other a sort order.
 */
enum class ThreadFilter(val label: String, val hint: String) {
    ALL("All", "Every comment in this review, and everything proposed"),
    OPEN("Open", "Everything not yet resolved or marked won't fix"),
    NEEDS_YOU("Needs you", "Answers to read, comments written but not sent, and proposals to decide"),
    PROPOSALS("Proposals", "Only what has been proposed and not yet added to the review");

    fun accepts(item: ReviewItem): Boolean = when (item) {
        // An open proposal is nothing but something to decide, so every one of these wants it -
        // and the last one wants nothing else.
        is Proposal -> true
        is ReviewThread -> when (this) {
            ALL -> true
            OPEN -> item.isOpen
            NEEDS_YOU -> item.isOpen && item.status != ThreadStatus.SENT
            PROPOSALS -> false
        }
    }
}

/**
 * The review of record first, then the inbox: work still open, then by where it is.
 *
 * Proposals sort under the comments rather than over them. They are the reader's to decide about,
 * not the review's, and putting unsigned findings above what the reviewer wrote inverts that.
 */
val reviewOrder: Comparator<ReviewItem> = compareBy(
    { if (it is Proposal) 1 else 0 },
    { (it as? ReviewThread)?.status?.ordinal ?: 0 },
    { it.file },
    { it.lines?.start ?: 0 },
)

/** A row of the column: a card, or the heading a run of them sits under. */
sealed interface ThreadRow {

    data class Group(val label: String, val count: Int) : ThreadRow

    data class Card(val thread: ReviewThread) : ThreadRow

    data class Proposed(val proposal: Proposal) : ThreadRow
}

/** What the column puts a heading between, the way the changes tree groups what it lists. */
enum class ThreadGrouping(val label: String, val hint: String) {
    NONE("None", "One flat list"),
    DIRECTORY("Directory", "By the directory the file is in"),
    MODULE("Module", "By the module the file belongs to"),
    ROUND("Round", "By how many times the comment has been round the agent"),
    STATUS("Status", "Pending, sent, answered, and the ones closed");

    /**
     * The threads in the order they were given, with a heading before each run.
     *
     * Groups come out in the order the sort found them, so grouping never fights [reviewOrder]:
     * it only says where the runs it already produced begin.
     */
    fun rows(items: List<ReviewItem>, moduleOf: (ReviewedFile) -> String?): List<ThreadRow> {
        if (this == NONE) return items.map { card(it) }
        // Keyed without regard to how the path was spelled and labelled with the first spelling
        // seen: the same directory reaches us from the VFS, from git and from a stored review.
        return items.groupBy { heading(it, moduleOf).lowercase() }.flatMap { (_, run) ->
            listOf(ThreadRow.Group(heading(run.first(), moduleOf), run.size)) + run.map { card(it) }
        }
    }

    private fun card(item: ReviewItem): ThreadRow = when (item) {
        is Proposal -> ThreadRow.Proposed(item)
        is ReviewThread -> ThreadRow.Card(item)
    }

    private fun heading(item: ReviewItem, moduleOf: (ReviewedFile) -> String?): String = when (this) {
        NONE -> ""
        DIRECTORY -> PathMatch.normalize(item.file.path).substringBeforeLast('/', "")
            .ifEmpty { "Project root" }
        MODULE -> moduleOf(item.file) ?: "Outside the project"
        // A proposal has been nowhere and stands nowhere, so both of these say only that.
        ROUND -> if (item is ReviewThread) "Round ${item.rounds}" else PROPOSED
        STATUS -> if (item is ReviewThread) {
            item.status.label.replaceFirstChar { it.uppercase() }
        } else {
            PROPOSED
        }
    }

    private companion object {
        const val PROPOSED = "Proposed"
    }
}
