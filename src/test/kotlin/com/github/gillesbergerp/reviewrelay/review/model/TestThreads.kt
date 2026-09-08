package com.github.gillesbergerp.reviewrelay.review.model

import java.util.UUID
import java.time.Instant

/** The fence a reviewer's suggestion is normalised to, for building the text a test expects. */
fun suggestionBlock(code: String): String = buildString {
    append("```").appendLine("suggestion")
    appendLine(code.trimEnd())
    append("```")
}


/** A proposal on the same targets [thread] builds, since triage is what tests talk about. */
fun proposal(
    id: ProposalId = ProposalId.fresh(),
    origin: Origin = Origin.Agent("claude"),
    type: CommentType = CommentType.FIX,
    text: String = "",
    filePath: String = "src/Foo.kt",
    lineStart: Int? = null,
    lineEnd: Int? = null,
    revision: Revision? = null,
    reviewedCode: Snippet? = null,
    madeAt: Instant = Instant.now(),
    standing: ProposalStanding = ProposalStanding.Open,
): Proposal = Proposal(
    id = id,
    origin = origin,
    type = type,
    target = LineRange.of(lineStart, lineEnd)
        ?.let { CommentTarget.Line(ReviewedFile(filePath), it, revision) }
        ?: CommentTarget.File(ReviewedFile(filePath), revision),
    text = text,
    reviewedCode = reviewedCode,
    madeAt = madeAt,
    standing = standing,
)

/** A single-message thread, which is what most tests are really talking about. */
fun thread(
    id: ThreadId = ThreadId.fresh(),
    type: CommentType = CommentType.FIX,
    text: String = "",
    filePath: String = "src/Foo.kt",
    lineStart: Int? = null,
    lineEnd: Int? = null,
    revision: Revision? = null,
    suggestionBase: Snippet? = null,
    reviewedCode: Snippet? = null,
    sentAt: Instant? = null,
    writtenAt: Instant = Instant.now(),
    outcome: ThreadOutcome? = null,
): ReviewThread = ReviewThread(
    id = id,
    type = type,
    // Lines or no lines is the whole difference, so a test says which by giving one or not.
    target = LineRange.of(lineStart, lineEnd)
        ?.let { CommentTarget.Line(ReviewedFile(filePath), it, revision) }
        ?: CommentTarget.File(ReviewedFile(filePath), revision),
    reviewedCode = reviewedCode,
    outcome = outcome,
    messages = listOf(
        ReviewMessage(
            author = MessageAuthor.REVIEWER,
            text = text,
            writtenAt = writtenAt,
            sentAt = sentAt,
            suggestionBase = suggestionBase,
        ),
    ),
)
