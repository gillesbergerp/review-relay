package com.github.gillesbergerp.reviewrelay.backend

import com.github.gillesbergerp.reviewrelay.review.model.ReviewThread
import com.github.gillesbergerp.reviewrelay.review.model.SessionId
import com.github.gillesbergerp.reviewrelay.util.Directory
import java.time.Instant

/** How a published review reaches the agent, which is what the Send button has to say honestly. */
enum class Delivery(val verb: String, val gerund: String, val progress: String) {
    /** Handed to a live turn: the agent is working on it before the button comes back. */
    PUSH("Send Review", "Sending...", "Sending the review"),

    /** Parked where an agent has to come and collect it. */
    PULL("Publish Review", "Publishing...", "Publishing the review"),
}

/**
 * What a publish actually achieved.
 *
 * A type rather than a boolean, so nothing can report "Sent" for a review that was only parked, and
 * [Blocked] can leave without the comments being marked as sent.
 */
sealed interface PublishOutcome {
    data class Delivered(val target: String) : PublishOutcome
    data class Published(val hint: String) : PublishOutcome
    data class Blocked(val reason: String) : PublishOutcome
}

/** Always answerable, so the session bar never has a special case for a backend that cannot talk. */
data class BackendStatus(
    val label: String,
    val problem: String? = null,
    val connecting: Boolean = false,
)

/** One round of review, in terms no backend is special about. */
class ReviewRequest(
    val projectDirectory: String,
    val summary: String,
    val threads: List<ReviewThread>,
    /** The session to send to, as the review being sent remembers it. */
    val session: SessionId? = null,
    /**
     * Whether that session has already been told how a review reads, so this round only recaps.
     *
     * Per session rather than per review: picking a different agent mid-review puts the comments in
     * front of one that has never seen the conventions.
     */
    val briefed: Boolean = false,
    /** Whether a thread's file is still on disk, so a deleted one can be marked rather than attached. */
    val inWorkingTree: (ReviewThread) -> Boolean,
    /**
     * The line a thread's reviewed code sits on in the working tree, or null when it is not there.
     *
     * Answers for the code rather than the file: a comment written against a commit is editable
     * wherever that code still stands, and unreachable once it has changed under it.
     */
    val lineInWorkingTree: (ReviewThread) -> Int? = { thread ->
        thread.lines?.start.takeIf { thread.revision == null && inWorkingTree(thread) }
    },
)

/**
 * A conversation with an agent, for backends that have more than one.
 *
 * The backend owns the session; a review only ever holds its [SessionId], which is why that lives
 * with the review's own ids and this does not.
 */
data class AgentSession(
    val id: SessionId,
    val title: String,
    val directory: Directory?,
    val updatedAt: Instant,
)
