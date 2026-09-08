package com.github.gillesbergerp.reviewrelay.review.export

import com.github.gillesbergerp.reviewrelay.review.model.ReviewSession
import com.github.gillesbergerp.reviewrelay.review.model.ReviewThread
import com.github.gillesbergerp.reviewrelay.review.service.comments
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import java.awt.datatransfer.StringSelection

/** The review as text, for pasting into an agent that cannot reach the review tools. */
class ClipboardDestination : ReviewDestination {

    override val id = "clipboard"

    override val displayName = "Clipboard"

    override fun availability(project: Project): Availability = Availability.Ready

    /** What a send would carry, which is what someone pasting a review into an agent means by it. */
    override fun selects(review: ReviewSession): List<ReviewThread> = review.threads.filter { it.isPending }

    override fun deliver(project: Project, review: ReviewSession, comments: List<ReviewThread>): Sent {
        val text = MarkdownExporter.export(review.summary, comments)
        if (text.isBlank()) return Sent.Failed("Nothing to copy")
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        // Records nothing: the clipboard cannot say what became of what was pasted into it, so a
        // second copy is a second copy rather than a repeat.
        return Sent.Ok("Copied ${comments(count = comments.size)}.")
    }
}
