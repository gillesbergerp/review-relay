package com.github.gillesbergerp.reviewrelay.review.export

import com.github.gillesbergerp.reviewrelay.review.model.CommentTarget
import com.github.gillesbergerp.reviewrelay.review.model.ReviewThread
import com.github.gillesbergerp.reviewrelay.review.model.ReviewSession
import com.github.gillesbergerp.reviewrelay.review.ReviewText
import com.github.gillesbergerp.reviewrelay.review.model.Suggestion

/**
 * The review as text to paste somewhere.
 *
 * Says what a pushed one says, in the same words: a review pasted into an agent that was given ids
 * and the rules for them can be answered through the review tools, and one without them cannot.
 */
object MarkdownExporter {

    /** A blank line between messages, as a pushed review puts one. */
    private const val BETWEEN = "\n\n"

    /** What a send would carry: what the reviewer has written and not yet sent. */
    fun export(session: ReviewSession): String = export(session.summary, session.threads.filter { it.isPending })

    fun export(summary: String, comments: List<ReviewThread>): String {
        if (comments.isEmpty() && summary.isEmpty()) return ""

        val sb = StringBuilder()
        if (summary.isNotEmpty()) {
            sb.appendLine(summary)
        } else {
            sb.appendLine("I reviewed your code and have the following comments. Please address them.")
        }
        sb.appendLine()

        if (comments.isNotEmpty()) {
            sb.appendLine(ReviewText.LEGEND)
            sb.appendLine(ReviewText.answering(ReviewText.Lines.BY_PATH))
            sb.appendLine()

            val sorted = comments.sortedWith(
                compareBy<ReviewThread> { if (it.target is CommentTarget.Line) 1 else 0 }
                    .thenBy { it.file }
                    .thenBy { it.lines?.start ?: Int.MAX_VALUE }
            )

            sorted.forEachIndexed { index, comment ->
                val location = ReviewText.location(comment, null)
                // What this round carries, not the comment it opened with: a thread is pending again
                // once a follow-up is written, and the opening has already been sent.
                val saying = comment.unsent.joinToString(BETWEEN) { Suggestion.normalized(it.text.trim()) }
                sb.appendLine("${index + 1}. **${ReviewText.tag(comment)}** `$location` - $saying")
                // Pasted where the file cannot be read, a comment naming lines nobody can see is
                // not answerable, and there is no attachment here to carry them.
                ReviewText.quotedCode(comment)?.let { sb.appendLine(it.prependIndent("   ")) }
            }
        }

        return sb.toString().trimEnd() + "\n"
    }
}
