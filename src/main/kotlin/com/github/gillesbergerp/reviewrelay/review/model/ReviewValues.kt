package com.github.gillesbergerp.reviewrelay.review.model

import java.util.UUID

/**
 * A review, a thread in one, and a message in a thread.
 *
 * Three kinds of UUID that read alike, meeting in the same call: `update(reviewId, threadId, ...)`,
 * `editMessage(threadId, messageId, ...)`, and a `Map` keyed by whichever of them the writer meant.
 */
@JvmInline
value class ReviewId(val value: String) {
    override fun toString(): String = value

    companion object {
        fun fresh() = ReviewId(UUID.randomUUID().toString())
    }
}

/**
 * The id of anything in a review, so one map and one selection can hold both kinds.
 *
 * Two implementations rather than one type: a handle an agent replies on must never name a
 * proposal, and keeping the id spaces apart is what makes that unsayable.
 */
sealed interface ItemId {
    val value: String
}

@JvmInline
value class ThreadId(override val value: String) : ItemId {

    /** What an agent is given to answer on: short enough to type back in a tool call. */
    val handle: String get() = value.take(8)

    override fun toString(): String = value

    companion object {
        fun fresh() = ThreadId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class ProposalId(override val value: String) : ItemId {
    override fun toString(): String = value

    companion object {
        fun fresh() = ProposalId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class MessageId(val value: String) {
    override fun toString(): String = value

    companion object {
        fun fresh() = MessageId(UUID.randomUUID().toString())
    }
}

/**
 * One agent conversation, issued by whichever backend owns it.
 *
 * It sits with the review's ids rather than with the backend's session because a review holds one
 * and a backend must be told which - never the reverse.
 */
@JvmInline
value class SessionId(val value: String) {
    override fun toString(): String = value
}

/** Which agent: `claude-code`, `opencode`, or the review tools. */
@JvmInline
value class BackendId(val value: String) {
    override fun toString(): String = value
}

/**
 * The conversation a review is worked in.
 *
 * The backend travels with the session because a session id means nothing without the agent that
 * issued it - handed to another backend it names a session that one does not have. Choosing a
 * backend therefore drops the session rather than carrying it across.
 */
data class AgentConversation(val backendId: BackendId, val sessionId: SessionId? = null)

/**
 * What a comment is about: a file, or lines in one.
 *
 * The two used to be a `scope` enum beside four nullable fields, which let a line comment exist with
 * no lines and made every reader re-derive the rule with `lines?.start ?: something`.
 */
sealed interface CommentTarget {

    /** A comment with no file is a remark about the review, which the summary carries instead. */
    val file: ReviewedFile

    /** The commit the file was read at. Null means the working tree, where it can be edited. */
    val revision: Revision?

    data class File(
        override val file: ReviewedFile,
        override val revision: Revision? = null,
    ) : CommentTarget

    data class Line(
        override val file: ReviewedFile,
        val lines: LineRange,
        override val revision: Revision? = null,
    ) : CommentTarget

    /** The same place on other lines. A whole-file comment has none to move and is left alone. */
    fun movedTo(lines: LineRange): CommentTarget = if (this is Line) copy(lines = lines) else this
}

/**
 * The lines a comment is about.
 *
 * One value with the rule that made it two: a comment on a single line has no end of its own, and
 * every reader used to re-derive that with `lineEnd ?: lineStart`.
 */
data class LineRange(val start: Int, val end: Int = start) {

    init {
        require(end >= start) { "A range ends where it starts or after it: $start..$end" }
    }

    val single: Boolean get() = end == start

    /** How many lines it covers, which is what survives the code moving. */
    val span: Int get() = end - start

    /** The same range found again at [line], for code that has moved but not changed. */
    fun movedTo(line: Int): LineRange = LineRange(line, line + span)

    /** As a reviewer reads it: one number, or two. */
    fun label(): String = if (single) start.toString() else "$start-$end"

    companion object {
        /** The stored pair, where the end is written only for a range. */
        fun of(start: Int?, end: Int?): LineRange? {
            if (start == null) return null
            return LineRange(start, (end ?: start).coerceAtLeast(start))
        }
    }
}

/**
 * A commit, as the id of the thing rather than a string that happens to look like one.
 *
 * Null everywhere means the working tree, which is the only place a comment can be edited.
 */
@JvmInline
value class Revision(val hash: String) {

    /** Long enough to `git show`, short enough to read in a comment header. */
    val short: String get() = hash.take(8)

    override fun toString(): String = hash
}

/**
 * Lines captured at a moment: what a comment was written against, what it read when it was sent,
 * and what a suggestion proposes replacing.
 *
 * A type of its own because all three are code rather than prose, and passing one where another was
 * meant is the mistake that shows up as a diff against the wrong text.
 */
@JvmInline
value class Snippet(val text: String) {

    val isBlank: Boolean get() = text.isBlank()

    override fun toString(): String = text

    companion object {
        /** Null and empty mean the same thing here: nothing was captured. */
        fun of(text: String?): Snippet? = text?.takeIf { it.isNotBlank() }?.let { Snippet(it) }
    }
}
