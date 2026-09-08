package com.github.gillesbergerp.reviewrelay.backend.mcp

/** Every backend answers through the same endpoint, so whether a reply can come back is one question. */
object ReviewAccess {

    /**
     * Remembered for a few seconds, because this is read from a toolbar update.
     *
     * Answering it opens and parses every agent's configuration file, and the toolbar asks on every
     * refresh - which is now every change to the review.
     */
    @Volatile
    private var answer: Pair<Long, Boolean>? = null

    private const val GOOD_FOR_MS = 5_000L

    val ready: Boolean
        get() {
            val now = System.currentTimeMillis()
            answer?.takeIf { now - it.first < GOOD_FOR_MS }?.let { return it.second }
            return AgentClients.anyConfigured(McpEndpoint.url()).also { answer = now to it }
        }

    val setupHint: String?
        get() = "Point your agent at this IDE so it can answer".takeIf { !ready }
}
