package com.github.gillesbergerp.reviewrelay.backend.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentClientsTest {

    private val url = "http://127.0.0.1:63342/api/review-relay/mcp"

    @Test
    fun `no config file at all is absent`() {
        assertEquals(AgentClients.State.ABSENT, AgentClients.stateOf(null, url))
    }

    @Test
    fun `a config naming another server is absent`() {
        val config = """{"mcpServers":{"datadog":{"url":"https://mcp.datadoghq.eu/mcp"}}}"""
        assertEquals(AgentClients.State.ABSENT, AgentClients.stateOf(config, url))
    }

    @Test
    fun `a config naming this address is configured`() {
        assertEquals(
            AgentClients.State.CONFIGURED,
            AgentClients.stateOf("""{"mcpServers":{"review-relay":{"url":"$url"}}}""", url),
        )
    }

    @Test
    fun `a config left on a port the IDE no longer holds is stale`() {
        val old = """{"mcpServers":{"review-relay":{"url":"http://127.0.0.1:63343/api/review-relay/mcp"}}}"""
        assertEquals(AgentClients.State.STALE, AgentClients.stateOf(old, url))
    }

    @Test
    fun `every client offers a snippet carrying the address`() {
        val clients = AgentClients.all(url)
        assertEquals(listOf("Claude Code", "OpenCode", "Codex", "Cursor"), clients.map { it.name })
        clients.forEach { assertTrue(it.name, it.snippet.contains(url)) }
    }
}
