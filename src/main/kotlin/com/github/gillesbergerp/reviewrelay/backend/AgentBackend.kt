package com.github.gillesbergerp.reviewrelay.backend

import com.github.gillesbergerp.reviewrelay.review.model.BackendId
import com.github.gillesbergerp.reviewrelay.review.model.SessionId
import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/** One way of getting a review to an agent. */
interface AgentBackend : Disposable {

    val id: BackendId
    val displayName: String

    val delivery: Delivery

    val status: BackendStatus

    /** Blocking; call off the EDT. */
    fun refresh() {}

    /** Blocking; call off the EDT. Returns once the review is as delivered as this backend can tell. */
    fun publish(request: ReviewRequest): PublishOutcome

    val sessions: SessionCapability? get() = null
    val activity: ActivityCapability? get() = null
    val interrupt: InterruptCapability? get() = null
    val reconnect: ReconnectCapability? get() = null

    /** Null where there is no live session to ask, which is what greys Request Review. */
    val prompting: PromptCapability? get() = null

    override fun dispose() {}
}

/**
 * How a backend is registered, so this package names no implementation of itself.
 *
 * A backend needs its host to exist before it does, which an extension the platform instantiates
 * cannot have; the factory is the indirection that buys it. Registration order is the order they
 * are offered in, and the first is what a project falls back to.
 */
interface AgentBackendFactory {

    fun create(host: BackendHost): AgentBackend

    companion object {
        val EP_NAME: ExtensionPointName<AgentBackendFactory> =
            ExtensionPointName.create("com.github.gillesbergerp.reviewrelay.agentBackend")
    }
}

/**
 * What a backend may ask of the rest of the plugin.
 *
 * The consequences of an agent finishing are the same whoever ran it, so they live here rather than
 * in each backend: a future backend that learns to report idle inherits them.
 */
interface BackendHost {
    val project: Project
    fun stateChanged()
    fun agentWentIdle(sessionId: SessionId, sessionLabel: String)
    fun agentNeedsInput(sessionId: SessionId, sessionLabel: String, reason: String)
    fun fileEdited(path: String)
}
