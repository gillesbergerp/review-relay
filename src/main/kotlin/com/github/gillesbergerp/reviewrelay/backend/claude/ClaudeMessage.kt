package com.github.gillesbergerp.reviewrelay.backend.claude

import com.github.gillesbergerp.reviewrelay.backend.ReviewRequest
import com.github.gillesbergerp.reviewrelay.review.model.Suggestion
import com.github.gillesbergerp.reviewrelay.review.ReviewPresentation
import com.github.gillesbergerp.reviewrelay.review.ReviewText

/**
 * A review as one message, which is all this channel carries.
 *
 * The lines go by path: there is nothing to attach them to, so the agent is asked to read them. That
 * is not the same as the file being gone, which is what [ReviewText.threadText] marks.
 */
internal object ClaudeMessage {

    fun text(request: ReviewRequest, workingDirectory: String): String {
        val review = ReviewPresentation(
            threads = request.threads,
            inWorkingTree = request.inWorkingTree,
            projectDirectory = request.projectDirectory,
            elsewhere = ReviewText.elsewhere(request.projectDirectory, workingDirectory),
            lineInWorkingTree = request.lineInWorkingTree,
        )
        return buildString {
            appendLine(
                review.preamble(
                    summary = request.summary,
                    lines = ReviewText.Lines.BY_PATH,
                    withSuggestions = review.threads.any { it.unsent.any { m -> Suggestion.isPresent(m.text) } },
                    full = !request.briefed,
                )
            )
            review.manifest()?.let { appendLine(); appendLine(it) }
            review.threads.forEach { thread ->
                appendLine()
                // Nothing here can attach the lines, so a comment nothing can point at carries them.
                review.quoted(thread)?.let { appendLine(it) }
                appendLine(review.text(thread, thread.unsent))
            }
            appendLine()
            appendLine(review.closing())
        }.trimEnd()
    }
}
