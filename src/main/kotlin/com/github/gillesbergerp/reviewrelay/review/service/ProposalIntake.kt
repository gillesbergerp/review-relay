package com.github.gillesbergerp.reviewrelay.review.service

import com.github.gillesbergerp.reviewrelay.review.model.CommentTarget
import com.github.gillesbergerp.reviewrelay.review.model.CommentType
import com.github.gillesbergerp.reviewrelay.review.model.LineRange
import com.github.gillesbergerp.reviewrelay.review.model.Origin
import com.github.gillesbergerp.reviewrelay.review.model.Proposal
import com.github.gillesbergerp.reviewrelay.review.model.ProposalId
import com.github.gillesbergerp.reviewrelay.review.model.ReviewedFile
import com.github.gillesbergerp.reviewrelay.review.model.Snippet
import com.github.gillesbergerp.reviewrelay.ui.editor.relativePath
import com.github.gillesbergerp.reviewrelay.ui.editor.reviewedFile
import com.github.gillesbergerp.reviewrelay.ui.editor.reviewedLines
import com.github.gillesbergerp.reviewrelay.util.PathMatch
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project

sealed interface Proposed {
    data class Ok(val id: ProposalId) : Proposed
    data object AlreadyKnown : Proposed
    data class NoSuchFile(val path: String) : Proposed
    data class Enough(val cap: Int) : Proposed
}

/**
 * Records what an agent found, where the reviewer will decide about it.
 *
 * The door an agent writes proposals through, beside [recordReply]: the protocol layer parses, this
 * anchors and stores. Nothing here can reach another agent or a pull request - only filing can.
 */
fun recordProposal(
    project: Project,
    path: String,
    lineStart: Int?,
    lineEnd: Int?,
    type: CommentType,
    text: String,
    agent: String,
): Proposed {
    val service = ReviewSessionService.getInstance(project)
    // The review an agent was given, not the tab the reviewer has since clicked on - the same rule
    // that decides where a reply lands.
    val review = service.publishedReview()
    if (review.openProposals.size >= CAP) return Proposed.Enough(CAP)

    val file = resolve(project, path) ?: return Proposed.NoSuchFile(path)
    val lines = LineRange.of(lineStart, lineEnd)
    val proposal = Proposal(
        origin = Origin.Agent(agent),
        type = type,
        target = lines?.let { CommentTarget.Line(file, it) } ?: CommentTarget.File(file),
        text = text,
        reviewedCode = lines?.let { Snippet.of(codeAt(project, file, it)) },
    )

    // Asked before the write, which has to wait for the EDT: an agent retrying is told it already
    // has this one rather than being answered with a promise.
    if (review.proposals.any { it.fingerprint == proposal.fingerprint }) return Proposed.AlreadyKnown

    ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed) return@invokeLater
        service.addProposal(review.id, proposal)
    }
    return Proposed.Ok(proposal.id)
}

/**
 * An agent spells a path either way, and only a file the reviewer's project holds can be shown.
 *
 * Resolved against the project before it is believed: the VFS walks `..` like anything else, so a
 * relative path was free to climb out of the project and have its lines read back.
 */
private fun resolve(project: Project, path: String): ReviewedFile? {
    val base = project.basePath ?: return null
    val absolute = PathMatch.within(base, path) ?: return null
    val relative = relativePath(project, absolute) ?: return null
    return relative.takeIf { reviewedFile(project, it, refresh = true) != null }
}

private fun codeAt(project: Project, file: ReviewedFile, lines: LineRange): String? = runReadActionBlocking {
    val virtual = reviewedFile(project, file) ?: return@runReadActionBlocking null
    val document = FileDocumentManager.getInstance().getDocument(virtual) ?: return@runReadActionBlocking null
    reviewedLines(document, lines.start, lines.end)
}

/** Enough for any one pass. Past this the reviewer has an inbox rather than a review. */
private const val CAP = 50
