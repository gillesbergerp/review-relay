package com.github.gillesbergerp.reviewrelay.review

import com.github.gillesbergerp.reviewrelay.review.model.ReviewMessage
import com.github.gillesbergerp.reviewrelay.review.model.ReviewThread
import com.github.gillesbergerp.reviewrelay.util.GitDirs

/**
 * What is true of a review before any backend writes it down: where its lines can be read, which of
 * them are only in git history, and what the preamble therefore has to say.
 *
 * Backends differ in how they carry a review - attached parts, one message, a tool to come and ask -
 * not in what is true of one. Deciding this in each of them is how the same question came to be
 * answered three ways, and how a fix in one stayed invisible to the others.
 */
class ReviewPresentation(
    val threads: List<ReviewThread>,
    private val inWorkingTree: (ReviewThread) -> Boolean,
    /** Where the review is, which is what tells the agent where its git commands have to run. */
    private val projectDirectory: String? = null,
    private val elsewhere: ReviewText.Elsewhere? = null,
    /**
     * Defaults to trusting the stored line for a working-tree comment and nothing for a commit's:
     * a caller that cannot read the file cannot know where a commit's code stands now.
     */
    private val lineInWorkingTree: (ReviewThread) -> Int? = { thread ->
        thread.lines?.start.takeIf { thread.revision == null && inWorkingTree(thread) }
    },
) {

    /** [withSuggestions] is the caller's: a pushed review carries what is unsent, a fetched one all of it. */
    fun preamble(
        summary: String,
        lines: ReviewText.Lines,
        withSuggestions: Boolean,
        full: Boolean = true,
    ): String =
        ReviewText.preamble(
            summary = summary,
            withSuggestions = withSuggestions,
            full = full,
            elsewhere = elsewhere,
            lines = lines,
            onDisk = threads.any { attachable(it) },
            fromCommits = threads.any { it.revision != null },
            repositoryRoot = GitDirs.worktreeRootOf(projectDirectory),
        )

    /**
     * Whether the agent can be pointed at this comment's code rather than told what it said.
     *
     * A whole-file comment has no lines to place and is attachable on the file being there alone;
     * asking [attachAt] for both answers is what made such a comment read as missing from disk.
     */
    fun attachable(thread: ReviewThread): Boolean =
        !absent(thread) && (thread.lines == null || attachAt(thread) != null)

    /**
     * The line to point the agent at, or null when the reviewed code is not in the working tree.
     *
     * A commit's own numbers address whatever sits at them today, so what makes a comment editable
     * is its code still being there - which for an amended or rebased commit is a different line.
     */
    fun attachAt(thread: ReviewThread): Int? =
        lineInWorkingTree(thread)

    /** About the file not being on disk, not about whether its lines travelled with it. */
    fun absent(thread: ReviewThread): Boolean = !inWorkingTree(thread)

    /** The reviewed lines themselves, for a thread nothing can point at. */
    fun quoted(thread: ReviewThread): String? =
        if (attachable(thread)) null else ReviewText.quotedCode(thread)

    fun text(thread: ReviewThread, messages: List<ReviewMessage>): String =
        ReviewText.threadText(thread, messages, absent(thread), attachAt(thread), location(thread))

    fun manifest(): String? = ReviewText.manifest(threads, ::location)

    fun closing(): String = ReviewText.closing(threads)

    /** Absolute once the agent is somewhere else, where a project-relative path names its own file. */
    fun location(thread: ReviewThread): String = ReviewText.location(thread, elsewhere?.path)
}
