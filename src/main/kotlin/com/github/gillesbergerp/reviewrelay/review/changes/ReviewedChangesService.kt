package com.github.gillesbergerp.reviewrelay.review.changes

import com.intellij.openapi.components.Service
import com.github.gillesbergerp.reviewrelay.review.model.ReviewId
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * What the review on screen is of, in words.
 *
 * All that is left of a comparison model: the log owns what is being reviewed and renders its own
 * files for it, so the only thing worth keeping is the label, which is what a new review is named
 * after and what it records having been started against.
 */
@Service(Service.Level.PROJECT)
class ReviewedChangesService {

    /**
     * Per review, not per project: every tab describes its own log as it is built, so one slot was
     * left holding whichever tab came up last rather than the one being looked at.
     */
    private val labels = ConcurrentHashMap<ReviewId, String>()

    fun comparedWith(reviewId: ReviewId): String = labels[reviewId].orEmpty()

    fun compareWith(reviewId: ReviewId, label: String) {
        labels[reviewId] = label
    }

    fun forget(reviewId: ReviewId) {
        labels.remove(reviewId)
    }

    companion object {
        fun getInstance(project: Project): ReviewedChangesService =
            project.getService(ReviewedChangesService::class.java)
    }
}
