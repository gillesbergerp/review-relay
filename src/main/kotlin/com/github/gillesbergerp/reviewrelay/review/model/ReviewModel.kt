package com.github.gillesbergerp.reviewrelay.review.model

import java.time.Instant

enum class CommentType(val label: String) {
    FIX("Fix"),
    CONSIDER("Consider"),
    QUESTION("Question");

    /** So one key can reach all three. */
    val next: CommentType get() = entries[(ordinal + 1) % entries.size]

    override fun toString(): String = label
}

/**
 * A type named at the head of a comment, which is the other way to pick one.
 *
 * [until] is exclusive and takes in the colon and the blanks after it, so the save and the keystroke
 * that drops it from the box cut at the same place.
 */
data class TypePrefix(val type: CommentType, val from: Int, val until: Int) {

    companion object {

        fun of(text: String): TypePrefix? {
            val from = text.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return null
            val type = CommentType.entries.firstOrNull {
                text.startsWith(it.label + ":", from, ignoreCase = true)
            } ?: return null
            var until = from + type.label.length + 1
            while (until < text.length && text[until].isWhitespace()) until++
            return TypePrefix(type, from, until)
        }
    }
}

enum class MessageAuthor { REVIEWER, AGENT }

/** How a thread was closed. Absent while it is still being worked. */
enum class ThreadOutcome(val label: String) {
    RESOLVED("resolved"),
    WONT_FIX("won't fix"),
}

enum class ThreadStatus(val label: String) {
    PENDING("pending"),
    SENT("sent"),
    ANSWERED("answered"),
    RESOLVED("resolved"),
    WONT_FIX("won't fix");

    val open: Boolean get() = this != RESOLVED && this != WONT_FIX
}

data class ReviewMessage(
    val id: MessageId = MessageId.fresh(),
    val author: MessageAuthor,
    val text: String,
    val writtenAt: Instant = Instant.now(),
    /** When this went to the agent. Null on a reviewer message means it is still queued. */
    val sentAt: Instant? = null,
    /** The lines a suggestion in this message was written against, so its diff stays put. */
    val suggestionBase: Snippet? = null,
)

/** A comment as it exists somewhere that is not this IDE, named the way that place names it. */
data class Delivery(val destination: String, val externalId: String)

/** What the reviewer is about to add to a thread. */
data class CommentDraft(
    val type: CommentType,
    val text: String,
    val suggestionBase: Snippet?,
)

fun CommentDraft.asMessage(): ReviewMessage =
    ReviewMessage(author = MessageAuthor.REVIEWER, text = text, suggestionBase = suggestionBase)

data class ReviewThread(
    override val id: ThreadId = ThreadId.fresh(),
    override val type: CommentType = CommentType.FIX,
    override val target: CommentTarget,
    val messages: List<ReviewMessage> = emptyList(),
    val outcome: ThreadOutcome? = null,
    override val reviewedCode: Snippet? = null,
    /** The reviewed lines as they were when the thread was last sent. */
    val sentBase: Snippet? = null,
    /** Whether those lines have changed since; null until the agent has finished a turn. */
    val codeChanged: Boolean? = null,
    /** When the reviewer last looked at the thread, against which a reply counts as new. */
    val readAt: Instant? = null,
    /** Who proposed it, when it was filed from a proposal rather than written here. */
    val provenance: Origin? = null,
    /** Where this has already been sent besides the agent, so a second export is not a duplicate. */
    val deliveries: List<Delivery> = emptyList(),
) : ReviewItem {

    /**
     * Per place, not per destination: the same comment belongs on two pull requests as readily as on
     * one, and only a second copy on the *same* one is a duplicate.
     */
    fun deliveredTo(destination: String, externalId: String): Boolean =
        deliveries.any { it.destination == destination && it.externalId == externalId }
    /**
     * Derived rather than stored, so it cannot drift from the messages. An unsent reply of the
     * reviewer's outranks everything, which is what makes answering a closed thread reopen it.
     */
    val status: ThreadStatus
        get() = when {
            messages.any { it.author == MessageAuthor.REVIEWER && it.sentAt == null } -> ThreadStatus.PENDING
            outcome == ThreadOutcome.RESOLVED -> ThreadStatus.RESOLVED
            outcome == ThreadOutcome.WONT_FIX -> ThreadStatus.WONT_FIX
            messages.lastOrNull()?.author == MessageAuthor.AGENT -> ThreadStatus.ANSWERED
            else -> ThreadStatus.SENT
        }

    /** Written but not sent, which is what most of the plugin asks of a thread. */
    val isPending: Boolean get() = status == ThreadStatus.PENDING

    val isOpen: Boolean get() = status.open

    /**
     * An answer the reviewer has not looked at. Per round rather than per thread, so the next reply
     * is new again; a message with no recorded time reads as seen, which keeps old reviews quiet.
     */
    val unread: Boolean
        get() = status == ThreadStatus.ANSWERED &&
                messages.lastOrNull()?.writtenAt?.isAfter(readAt ?: Instant.EPOCH) == true

    /** The comment the thread started from. */
    val opening: ReviewMessage? get() = messages.firstOrNull { it.author == MessageAuthor.REVIEWER }

    override val text: String get() = opening?.text.orEmpty()

    /** What goes out in the next round: everything the reviewer has written but not sent. */
    val unsent: List<ReviewMessage>
        get() = messages.filter { it.author == MessageAuthor.REVIEWER && it.sentAt == null }

    /** The reviewer writing after an answer is what starts a round; nothing else does. */
    fun startsRound(index: Int): Boolean = index > 0 &&
        messages[index].author == MessageAuthor.REVIEWER &&
        messages[index - 1].author == MessageAuthor.AGENT

    /** How many times the thread has been round the agent, the first one included. */
    val rounds: Int get() = 1 + messages.indices.count { startsRound(it) }
}

/**
 * One review: what it is about, and the threads written in it.
 *
 * Nothing here is mutable: the endpoint reads a review on a netty thread and a backend on its own
 * while the reviewer writes on the EDT.
 *
 * [name] and [startedAgainst] are shown and restored, never matched on — a review keyed by a commit
 * would be orphaned the moment an agent amended it.
 */
data class ReviewSession(
    val id: ReviewId = ReviewId.fresh(),
    val name: String = "",
    val startedAgainst: String = "",
    val createdAt: Instant = Instant.now(),
    /** Whether it has a tab. A closed review is put away, not deleted. */
    val open: Boolean = true,
    val summary: String = "",
    /** The commits the log had selected, so reopening a project restores what this is about. */
    val selection: List<Revision> = emptyList(),
    /** The agent conversation this review is worked in. Null until one is picked for it. */
    val conversation: AgentConversation? = null,
    /**
     * The session this review's rules have been explained to, which a later round only recaps.
     *
     * Held per session rather than as a flag: moving the review to another agent puts the comments
     * in front of one that has never been told how to read them.
     */
    val briefedSession: SessionId? = null,
    val threads: List<ReviewThread> = emptyList(),
    /**
     * Candidates, kept apart from [threads] rather than marked within them.
     *
     * Everything that decides what an agent is given reads [threads]: a proposal in there would be
     * sent or published by a filter forgetting to exclude it, and there are two such filters.
     */
    val proposals: List<Proposal> = emptyList(),
) {
    val pending: Int get() = threads.count { it.isPending }
    val unread: Int get() = threads.count { it.unread }
    val proposed: Int get() = openProposals.size

    val openProposals: List<Proposal> get() = proposals.filter { it.isOpen }

    /**
     * Everything with a place in the code to be drawn at.
     *
     * A filed proposal is here as the thread it became and a dismissed one not at all, so this is
     * the whole of what a reviewer still has in front of them.
     */
    val items: List<ReviewItem> get() = threads + openProposals
}

/**
 * A concrete replacement for the reviewed lines.
 *
 * Any fenced code block counts, because that is what a reviewer reaches for; tag a fence `example`
 * to keep it as illustration. Whatever was typed is normalised to GitHub's `suggestion` fence
 * before it reaches the agent, so the agent always sees one unambiguous convention.
 */
object Suggestion {

    private const val EXPLICIT = "suggestion"
    private const val EXAMPLE = "example"

    private val FENCE = Regex("""^\s*`{3,}\s*(\S*)\s*$""")
    private val CLOSING_FENCE = Regex("""^\s*`{3,}\s*$""")

    data class Parsed(
        val prose: String,
        val code: String?,
    )

    fun isPresent(text: String): Boolean = parse(text).code != null

    /** Splits a comment into its prose and the suggested replacement, if it carries one. */
    fun parse(text: String): Parsed {
        val lines = text.lines()
        val open = openingFence(lines) ?: return Parsed(text.trim(), null)
        val close = closingFence(lines, open + 1)

        val prose = (lines.take(open) + lines.drop(minOf(close + 1, lines.size)))
            .joinToString("\n")
            .trim()
        val code = lines.subList(minOf(open + 1, lines.size), minOf(close, lines.size))
            .joinToString("\n")
        return Parsed(prose, code)
    }

    /** Rewrites the suggestion's opening fence to the explicit marker. */
    fun normalized(text: String): String {
        val lines = text.lines().toMutableList()
        val open = openingFence(lines) ?: return text
        lines[open] = "```$EXPLICIT"
        return lines.joinToString("\n")
    }

    private fun openingFence(lines: List<String>): Int? {
        var i = 0
        while (i < lines.size) {
            val info = FENCE.matchEntire(lines[i])?.groupValues?.get(1)
            if (info == null) {
                i++
                continue
            }
            if (info.equals(EXAMPLE, ignoreCase = true)) {
                i = closingFence(lines, i + 1) + 1
                continue
            }
            return i
        }
        return null
    }

    private fun closingFence(
        lines: List<String>,
        from: Int,
    ): Int {
        for (j in from until lines.size) if (CLOSING_FENCE.matches(lines[j])) return j
        return lines.size
    }
}
