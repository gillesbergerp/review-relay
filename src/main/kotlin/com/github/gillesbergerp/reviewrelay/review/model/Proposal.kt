package com.github.gillesbergerp.reviewrelay.review.model

import com.github.gillesbergerp.reviewrelay.util.PathMatch
import java.security.MessageDigest
import java.time.Instant

/** What became of a proposal. A dismissed one is kept, because [Proposal.fingerprint] is what
 * stops the next scan proposing it all over again. */
sealed interface ProposalStanding {

    data object Open : ProposalStanding

    data object Dismissed : ProposalStanding

    data class Filed(val thread: ThreadId) : ProposalStanding
}

/**
 * A comment nobody has signed yet: what an agent found, or what you jotted without committing to it.
 *
 * It carries no conversation and no status. Filing it writes a [ReviewThread] in your name, which is
 * the only way anything reaches an agent or a pull request - so nothing here can be sent by accident.
 */
data class Proposal(
    override val id: ProposalId = ProposalId.fresh(),
    val origin: Origin,
    override val type: CommentType = CommentType.FIX,
    override val target: CommentTarget,
    override val text: String,
    override val reviewedCode: Snippet? = null,
    val madeAt: Instant = Instant.now(),
    val standing: ProposalStanding = ProposalStanding.Open,
) : ReviewItem {

    val isOpen: Boolean get() = standing == ProposalStanding.Open

    /**
     * What makes two proposals the same one, so a second scan repeats nothing.
     *
     * Derived rather than stored so it cannot drift from the text, and keyed on the code rather
     * than the line number, which the agent's own edits move.
     */
    val fingerprint: String
        get() = digest(
            PathMatch.normalize(file.path).lowercase(),
            reviewedCode?.text?.trim() ?: lineLabel(),
            WHITESPACE.replace(text.trim().lowercase(), " "),
        )
}

private val WHITESPACE = Regex("\\s+")

private fun digest(vararg parts: String): String =
    MessageDigest.getInstance("SHA-256")
        // Length-prefixed rather than joined: no separator is safe inside a path or a comment.
        .digest(parts.joinToString("") { "${it.length}:$it" }.toByteArray())
        .joinToString("") { "%02x".format(it) }
