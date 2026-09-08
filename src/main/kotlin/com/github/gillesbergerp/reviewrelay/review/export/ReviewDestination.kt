package com.github.gillesbergerp.reviewrelay.review.export

import com.github.gillesbergerp.reviewrelay.review.model.ReviewSession
import com.github.gillesbergerp.reviewrelay.review.model.ReviewThread
import com.github.gillesbergerp.reviewrelay.review.model.ThreadId
import com.intellij.openapi.project.Project

sealed interface Availability {
    data object Ready : Availability

    /**
     * Said rather than hidden: a destination that quietly disappears reads as a broken plugin.
     *
     * This is the half the reviewer can fix - a CLI not installed, an account not signed in. Whether
     * a destination applies to this project at all is [ReviewDestination.offeredIn], and that half
     * *is* hidden, there being nothing to fix about a repository that is somewhere else.
     */
    data class Unavailable(val reason: String) : Availability
}

sealed interface Sent {
    /** [recorded] names what each comment became there, so a second export can skip it. */
    data class Ok(val said: String, val recorded: Map<ThreadId, String> = emptyMap()) : Sent

    data class Failed(val reason: String) : Sent
}

/**
 * Somewhere a review can go that is not the agent working it.
 *
 * Only [ReviewThread]s cross this line, and a proposal is not one: filing is the single gate
 * everything leaving the IDE has passed, and the signature is where that is enforced rather than
 * in each destination remembering to check.
 */
interface ReviewDestination {

    val id: String

    val displayName: String

    /** Whether this destination is for this project at all. Cheap: asked on every toolbar refresh. */
    fun offeredIn(project: Project): Boolean = true

    fun availability(project: Project): Availability

    /** Which of the review's comments this one is offered, which is not the same question twice. */
    fun selects(review: ReviewSession): List<ReviewThread>

    /** Blocking; call off the EDT. */
    fun deliver(project: Project, review: ReviewSession, comments: List<ReviewThread>): Sent
}
