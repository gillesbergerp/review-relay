package com.github.gillesbergerp.reviewrelay.backend

import com.github.gillesbergerp.reviewrelay.review.model.SessionId

import com.github.gillesbergerp.reviewrelay.util.Directory

/**
 * What a backend can do beyond taking a review.
 *
 * Facets rather than a set of flags: a null facet cannot be called, so the capability check and the
 * call are the same expression and cannot drift apart. The bugs this prevents are real ones - the
 * status line used to read "idle" for a backend that had never been told anything.
 */
/**
 * The sessions a backend has, none of them selected.
 *
 * Which session a review is worked in belongs to the review: two reviews can be worked in two
 * sessions of the same backend, and a slot here would let the last one drawn overwrite the other.
 */
interface SessionCapability {
    val sessions: List<AgentSession>

    /** What to offer a review that has not chosen yet, or null when none of them suits. */
    val preferred: SessionId?

    fun sessionOf(sessionId: SessionId?): AgentSession? = sessions.firstOrNull { it.id == sessionId }

    /** Set when [sessionId] runs in a directory other than the one under review. */
    fun borrowedFrom(sessionId: SessionId?): Directory? = null

    /** Null when the backend cannot make one, which hides the New Session button. */
    val creation: SessionCreation? get() = null
}

fun interface SessionCreation {
    /** Blocking; call off the EDT. */
    fun create(title: String): AgentSession?
}

interface ActivityCapability {
    fun isBusy(sessionId: SessionId?): Boolean

    /** False until something has actually been observed, so idle is never a guess. */
    val observed: Boolean

    /** What the agent is blocked on, when it is stopped but not finished. Null when it is neither. */
    fun waitingFor(sessionId: SessionId?): String? = null
}

fun interface InterruptCapability {
    /** Blocking; call off the EDT. */
    fun interrupt(sessionId: SessionId?)
}

/**
 * Throws away what discovery found, so the next refresh looks again.
 *
 * Called on the EDT, so it may not reconnect anything itself: the refresh that follows is the
 * caller's, off the EDT.
 */
fun interface ReconnectCapability {
    fun reconnect()
}

/**
 * Asking the agent for something that is not a review of the reviewer's own.
 *
 * Separate from publish because a request is not a review: it carries no threads, marks nothing
 * sent, and a backend that only serves a review when asked has nobody to ask.
 */
fun interface PromptCapability {
    /** Blocking; call off the EDT. */
    fun ask(sessionId: SessionId?, text: String): PublishOutcome
}
