package com.github.gillesbergerp.reviewrelay.ui.actions

import com.github.gillesbergerp.reviewrelay.review.service.ReviewSessionService
import com.intellij.ide.actions.ToolWindowTabRenameActionBase
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.ui.content.Content

/**
 * Renaming, on the tab rather than in the strip: it is about one review, and the tab is the one thing
 * on screen that names it.
 */
class RenameReviewAction : ToolWindowTabRenameActionBase(REVIEW_RELAY, "New review name:"), DumbAware {

    /** The tab label carries a pending count; the name being edited is the review's own. */
    override fun getContentDisplayNameToEdit(content: Content, project: Project): String {
        val id = content.reviewId() ?: return content.displayName.orEmpty()
        return ReviewSessionService.getInstance(project).review(id)?.name ?: content.displayName.orEmpty()
    }

    override fun applyContentDisplayName(content: Content, project: Project, newContentName: String) {
        val id = content.reviewId() ?: return
        ReviewSessionService.getInstance(project).renameReview(id, newContentName)
    }
}
