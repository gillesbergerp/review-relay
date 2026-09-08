package com.github.gillesbergerp.reviewrelay.backend.opencode

import com.github.gillesbergerp.reviewrelay.review.model.ReviewThread
import com.github.gillesbergerp.reviewrelay.review.model.Suggestion
import com.github.gillesbergerp.reviewrelay.review.ReviewPresentation
import com.github.gillesbergerp.reviewrelay.review.ReviewText
import java.nio.file.Paths

/**
 * Turns a review into OpenCode message parts. The words come from [ReviewText]; this is the envelope.
 *
 * A `file://` part carrying `?start=&end=` makes OpenCode run its Read tool over exactly those
 * 1-based lines, so the agent sees the code and the comment about it next to each other. A
 * single-line comment sends `start == end`, which OpenCode widens to the enclosing LSP symbol.
 *
 * Only what the reviewer has written and not yet sent goes out; a thread's earlier rounds are
 * already in the agent's own context.
 */
object ReviewPayload {

    fun parts(
        basePath: String,
        summary: String,
        threads: List<ReviewThread>,
        elsewhere: ReviewText.Elsewhere? = null,
        inWorkingTree: (ReviewThread) -> Boolean,
        lineInWorkingTree: (ReviewThread) -> Int? = { storedLineOf(it, inWorkingTree) },
        briefed: Boolean = false,
    ): List<Map<String, Any?>> = parts(
        basePath,
        summary,
        ReviewPresentation(threads, inWorkingTree, basePath, elsewhere, lineInWorkingTree),
        briefed,
    )

    fun parts(
        basePath: String,
        summary: String,
        review: ReviewPresentation,
        briefed: Boolean = false,
    ): List<Map<String, Any?>> {
        val parts = mutableListOf<Map<String, Any?>>()
        parts.add(
            textPart(
                review.preamble(
                    summary = summary,
                    lines = ReviewText.Lines.ATTACHED,
                    withSuggestions = review.threads.any { it.unsent.any { m -> Suggestion.isPresent(m.text) } },
                    full = !briefed,
                )
            )
        )

        review.manifest()?.let { parts.add(textPart(it)) }

        for (thread in review.threads) {
            if (review.attachable(thread)) {
                parts.add(
                    mapOf(
                        "type" to "file",
                        "mime" to "text/plain",
                        "filename" to thread.file.path,
                        "url" to fileUrl(basePath, thread, review.attachAt(thread)),
                    )
                )
            } else {
                review.quoted(thread)?.let { parts.add(textPart(it)) }
            }
            parts.add(textPart(review.text(thread, thread.unsent)))
        }
        parts.add(textPart(review.closing()))
        return parts
    }

    fun body(
        basePath: String,
        summary: String,
        threads: List<ReviewThread>,
        agent: String?,
        sessionDirectory: String? = null,
        inWorkingTree: (ReviewThread) -> Boolean,
        lineInWorkingTree: (ReviewThread) -> Int? = { storedLineOf(it, inWorkingTree) },
        briefed: Boolean = false,
    ): Map<String, Any?> = mapOf(
        "parts" to parts(
            basePath,
            summary,
            ReviewPresentation(
                threads = threads,
                inWorkingTree = inWorkingTree,
                projectDirectory = basePath,
                elsewhere = ReviewText.elsewhere(basePath, sessionDirectory),
                lineInWorkingTree = lineInWorkingTree,
            ),
            briefed,
        ),
        "agent" to agent?.takeIf { it.isNotBlank() },
    )


    /**
     * The stored line, trusted only for a comment written against the working tree.
     *
     * A caller that cannot read the file cannot know where a commit's code stands now, so such a
     * comment is quoted rather than pointed at - the assumption that lost the reviewed lines before.
     */
    private fun storedLineOf(thread: ReviewThread, inWorkingTree: (ReviewThread) -> Boolean): Int? =
        thread.lines?.start.takeIf { thread.revision == null && inWorkingTree(thread) }

    /** [at] overrides the stored line, which for a comment on a commit is not the file's. */
    fun fileUrl(basePath: String, thread: ReviewThread, at: Int? = null): String {
        val relative = thread.file
        val absolute = Paths.get(basePath).resolve(relative.path).normalize().toUri().toString()
        val lines = thread.lines ?: return absolute
        val at = at?.let { lines.movedTo(it) } ?: lines
        return "$absolute?start=${at.start}&end=${at.end}"
    }

    private fun textPart(text: String): Map<String, Any?> = mapOf("type" to "text", "text" to text)
}

