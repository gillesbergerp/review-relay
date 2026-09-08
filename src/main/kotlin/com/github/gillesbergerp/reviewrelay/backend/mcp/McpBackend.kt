package com.github.gillesbergerp.reviewrelay.backend.mcp

import com.github.gillesbergerp.reviewrelay.review.model.BackendId

import com.github.gillesbergerp.reviewrelay.backend.AgentBackend
import com.github.gillesbergerp.reviewrelay.backend.AgentBackendFactory
import com.github.gillesbergerp.reviewrelay.backend.BackendHost
import com.github.gillesbergerp.reviewrelay.backend.BackendStatus
import com.github.gillesbergerp.reviewrelay.backend.Delivery
import com.github.gillesbergerp.reviewrelay.backend.PublishOutcome
import com.github.gillesbergerp.reviewrelay.backend.ReviewRequest

/**
 * Any agent that speaks MCP.
 *
 * It has no session to pick, nothing to interrupt and no way to know the agent is working, because
 * nothing here talks to the agent: the review is put where its tools can reach it and the agent
 * comes when it is asked to. Publishing is therefore the whole of this backend.
 */
class McpBackend : AgentBackend {

    override val id = BackendId("mcp")
    override val displayName = "Any MCP client"
    override val delivery = Delivery.PULL

    override val status: BackendStatus
        get() = BackendStatus("Review tools at ${McpEndpoint.url()}")

    override fun publish(request: ReviewRequest): PublishOutcome {
        // Nothing to transmit: the tools read the review where it already lives.
        return PublishOutcome.Published("Ask your agent to address the review.")
    }
}

/** Registered in plugin.xml; see [AgentBackendFactory]. */
class McpBackendFactory : AgentBackendFactory {
    override fun create(host: BackendHost) = McpBackend()
}
