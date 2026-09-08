package com.github.gillesbergerp.reviewrelay.review

import com.github.gillesbergerp.reviewrelay.review.model.CommentTarget
import com.github.gillesbergerp.reviewrelay.review.model.ReviewMessage
import com.github.gillesbergerp.reviewrelay.review.model.MessageId
import com.github.gillesbergerp.reviewrelay.review.model.ReviewId
import com.github.gillesbergerp.reviewrelay.review.model.ThreadId
import com.github.gillesbergerp.reviewrelay.review.model.ReviewThread
import com.github.gillesbergerp.reviewrelay.review.model.Suggestion
import com.github.gillesbergerp.reviewrelay.util.GitDirs
import com.github.gillesbergerp.reviewrelay.util.PathMatch

/**
 * How a review reads to an agent, in words rather than in any wire format.
 *
 * Every backend describes the same review the same way; only the envelope around this differs, so a
 * comment means the same thing whether it is pushed as a prompt or fetched through a tool.
 */
object ReviewText {

    /** Short enough to read in a prompt, long enough not to collide within one review. */
    fun handle(thread: ReviewThread): String = thread.id.handle

    /** What each kind of comment asks for, which is the whole of what the three types mean. */
    const val LEGEND = "FIX means change it. CONSIDER is your judgement - say what you decided. " +
        "QUESTION wants an answer, not a change."

    /** How an answer comes back, on the id [Lines.identity] has just described. */
    fun answering(lines: Lines): String =
        "${lines.identity} If the review_reply tool is available, answer every comment with it, " +
            "passing that id, saying what you changed or why you did not."

    /** The tag a comment is addressed by, which is also how a reply names it. */
    fun tag(thread: ReviewThread): String = "[${thread.type.name} ${handle(thread)}]"

    /**
     * How the reviewed lines reach the agent, and the words that follow from it.
     *
     * [STRUCTURED] is handed fields rather than prose, so every sentence naming a marker has to
     * name a field instead: describing a `(not in the working tree)` tag to an agent reading JSON
     * sends it looking for something that is not there.
     */
    enum class Lines(
        /** How the reviewed code arrives, and what says it did not. */
        val delivery: String,
        /** How a comment says its file is gone, mid-sentence. */
        val absent: String,
        /** How a comment says which commit it was written against, mid-sentence. */
        val fromCommit: String,
        /** How a comment carries the id a reply comes back on. */
        val identity: String,
        /** Where to find the lines of a comment nothing could be pointed at. */
        val quoted: String,
    ) {
        ATTACHED(
            delivery = "Every comment is preceded by an attachment of the exact lines it refers to, " +
                "unless it is marked (not in the working tree), in which case the lines are only in " +
                "git history - it was deleted, or belongs to a commit you have not checked out.",
            absent = "marked (not in the working tree)",
            fromCommit = "marked (in commit X)",
            identity = "Each comment is tagged [TYPE id].",
            quoted = "the lines are quoted with each comment",
        ),
        BY_PATH(
            delivery = "Each comment names the file and the lines it is about. Read those lines before " +
                "answering. A comment marked (not in the working tree) has its lines only in git " +
                "history: read them there, and say so rather than editing a file that is absent.",
            absent = "marked (not in the working tree)",
            fromCommit = "marked (in commit X)",
            identity = "Each comment is tagged [TYPE id].",
            quoted = "the lines are quoted with each comment",
        ),
        STRUCTURED(
            delivery = "Each comment gives absolutePath with lineStart and lineEnd to read for " +
                "itself, and lineNow where that code has since moved. Read those lines before " +
                "answering. A comment with deleted true has its lines only in git history: read " +
                "them there, and say so rather than editing a file that is absent.",
            absent = "with deleted true",
            fromCommit = "with a revision",
            identity = "Each comment carries an id.",
            quoted = "reviewedCode holds them",
        ),
    }

    /** Where the reviewed files are, when the agent is not running there. */
    data class Elsewhere(val path: String, val sameRepository: Boolean)

    /** Null when the agent is already running where the review is, so nothing needs saying. */
    fun elsewhere(reviewed: String, agent: String?): Elsewhere? {
        if (agent == null || PathMatch.same(agent, reviewed)) return null
        return Elsewhere(reviewed, PathMatch.same(GitDirs.repositoryOf(agent), GitDirs.repositoryOf(reviewed)))
    }

    /**
     * The reviewed lines themselves, for a comment the agent cannot simply be pointed at.
     *
     * The working tree holds different lines at those numbers whenever the review is of a commit
     * that is not checked out, so the text written against is the only faithful copy.
     */
    fun quotedCode(thread: ReviewThread): String? {
        val code = thread.reviewedCode?.takeIf { !it.isBlank } ?: return null
        val whence = thread.revision?.let { "as they read in ${it.short}" } ?: "as they read when it was written"
        return buildString {
            append("The lines ").append(handle(thread)).append(" is about, ").appendLine("$whence:")
            appendLine("```")
            appendLine(code.text.trimEnd())
            append("```")
        }
    }

    /**
     * [full] explains the review from scratch; without it only what this round could have changed.
     *
     * A session already holding the conventions does not need them again, but every paragraph that
     * depends on the threads being sent does: this round may be the first to carry a suggestion, or
     * a comment on a commit, and the recap still has to say so.
     */
    fun preamble(
        summary: String,
        withSuggestions: Boolean = false,
        elsewhere: Elsewhere? = null,
        lines: Lines = Lines.ATTACHED,
        onDisk: Boolean = true,
        fromCommits: Boolean = false,
        repositoryRoot: String? = null,
        full: Boolean = true,
    ): String = buildString {
        appendLine(
            if (full) {
                "Code review of your changes. Please address each comment below."
            } else {
                "More review comments on the same changes."
            }
        )
        appendLine()
        if (full) appendLine(lines.delivery)
        if (fromCommits) {
            // git show takes a path relative to the repository root, which the header's is not.
            val where = repositoryRoot
                ?.let { " run in $it, where <path> is relative to that directory," }
                ?: ","
            appendLine(
                "A comment ${lines.fromCommit} numbers its lines in that commit, which is not what " +
                    "is checked out: read them with git show X:<path>$where and do not trust those " +
                    "numbers against the working tree."
            )
        }
        if (full) appendLine(LEGEND)
        appendLine(answering(lines))
        if (withSuggestions) {
            appendLine(
                "A suggestion is the exact replacement for the lines that comment is about: apply it " +
                    "verbatim unless it is wrong, and say so if it is."
            )
        }
        if (elsewhere != null) {
            val relation = if (elsewhere.sameRepository) {
                ", a worktree of the checkout you are running in. "
            } else {
                ", a checkout separate from the one you are running in. "
            }
            appendLine(
                "These files are in " + elsewhere.path + relation +
                    "Make every change there, at the absolute paths given, and leave your own checkout alone."
            )
        }
        if (!onDisk) {
            val recover = if (fromCommits) "read them with git show" else lines.quoted
            appendLine(
                "None of the reviewed lines are in the working tree of " +
                    (elsewhere?.path ?: "the repository you are running in") +
                    ": $recover, and where the point still holds against the file as it stands now, " +
                    "make the change there and say the lines have moved. Where it no longer applies, " +
                    "answer without editing."
            )
        }
        // A reviewer is reading this tree while you work in it; moving it under them loses their place.
        appendLine(
            "Do not check out, switch branches, stash, or reset: the reviewer is working in this " +
                "tree. Read any other revision with git show."
        )
        if (full) {
            appendLine("When you are done, say what you changed for each comment. Make no unrelated changes.")
        }
        if (summary.isNotBlank()) {
            appendLine()
            appendLine("Reviewer summary:")
            append(summary.trim())
        }
    }.trimEnd()

    /**
     * The comments at a glance, so the agent can see the size of the work before reading it.
     *
     * Null for a review small enough to hold in the head, where an index is only more to read.
     */
    fun manifest(threads: List<ReviewThread>, location: (ReviewThread) -> String): String? {
        if (threads.size < 2) return null
        val width = threads.maxOf { it.type.name.length }
        return buildString {
            appendLine("The ${threads.size} comments, in the order they follow:")
            threads.forEach {
                appendLine("  ${handle(it)}  ${it.type.name.padEnd(width)}  ${location(it)}")
            }
        }.trimEnd()
    }

    /** Said after the comments, where the last thing read is the thing acted on. */
    fun closing(threads: List<ReviewThread>): String =
        "That is all ${threads.size} comment(s). Answer every one, and make no unrelated changes."

    /**
     * One thread as the agent reads it: where it is, then everything unanswered said about it.
     *
     * [absent] is about the file not being on disk, not about whether the lines travelled with it.
     *
     * [nowAt] is where the reviewed code stands in the working tree, which is where it is attached:
     * a header still naming the line it was written at contradicts the attachment beside it.
     */
    fun threadText(
        thread: ReviewThread,
        messages: List<ReviewMessage>,
        absent: Boolean = false,
        nowAt: Int? = null,
        location: String = location(thread, null),
    ): String = messages.joinToString(
        separator = "\n\n",
        prefix = header(thread, absent, nowAt, location) + "\n",
    ) { Suggestion.normalized(it.text.trim()) }

    private fun header(
        thread: ReviewThread,
        absent: Boolean,
        nowAt: Int?,
        location: String,
    ): String {
        val followUp = thread.messages.any { it.sentAt != null }
        // Where the code was found, which is where it is attached and so what the header must say.
        val moved = nowAt?.takeIf { it != thread.lines?.start }
        return buildString {
            append(tag(thread)).append(" ")
            append(location)
            val revision = thread.revision
            when {
                revision != null -> {
                    append(" (in commit ${revision.short}")
                    moved?.let { append(", now line $it") }
                    append(")")
                }
                absent -> append(" (not in the working tree)")
                moved != null -> append(" (now line $moved)")
            }
            if (followUp) append(" (follow-up)")
        }
    }

    /**
     * Where a comment is, written from wherever the agent will read it.
     *
     * [base] is the reviewed project, given only when the agent is not running in it: a relative
     * path resolves against the agent's own directory, which is then a different file or none.
     */
    fun location(thread: ReviewThread, base: String?): String {
        val path = base?.let { thread.file.absoluteIn(it) } ?: thread.file.path
        return when (thread.target) {
            is CommentTarget.File -> "$path (whole file)"
            is CommentTarget.Line -> "$path:${thread.lineLabel()}"
        }
    }
}
