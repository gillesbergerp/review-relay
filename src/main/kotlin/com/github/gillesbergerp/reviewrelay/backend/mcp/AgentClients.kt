package com.github.gillesbergerp.reviewrelay.backend.mcp

import java.io.File

/** The agent CLIs a reviewer is likely to point at this IDE, and how each is told where it is. */
object AgentClients {

    enum class State { CONFIGURED, STALE, ABSENT }

    data class Client(
        val name: String,
        val config: File,
        val snippet: String,
        val state: State,
    )

    fun anyConfigured(url: String): Boolean = all(url).any { it.state == State.CONFIGURED }

    fun all(url: String): List<Client> = DEFINITIONS.map { definition ->
        val config = definition.config()
        Client(
            name = definition.name,
            config = config,
            snippet = definition.snippet(url),
            state = stateOf(config.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }, url),
        )
    }

    /**
     * Stale is worth its own answer: the built-in server takes the next free port when a second IDE
     * holds the usual one, and a config left pointing at the old one fails silently.
     */
    internal fun stateOf(config: String?, url: String): State = when {
        config == null -> State.ABSENT
        config.contains(url) -> State.CONFIGURED
        config.contains(McpEndpoint.PATH) -> State.STALE
        else -> State.ABSENT
    }

    private class Definition(
        val name: String,
        val config: () -> File,
        val snippet: (String) -> String,
    )

    private fun home(vararg parts: String) = File(System.getProperty("user.home"), parts.joinToString("/"))

    private val DEFINITIONS = listOf(
        Definition(
            name = "Claude Code",
            config = { home(".claude.json") },
            snippet = { url ->
                """
                "mcpServers": {
                  "review-relay": { "type": "http", "url": "$url" }
                }
                """.trimIndent()
            },
        ),
        Definition(
            name = "OpenCode",
            config = { home(".config/opencode/opencode.json") },
            snippet = { url ->
                """
                "mcp": {
                  "review-relay": { "type": "remote", "url": "$url", "enabled": true }
                }
                """.trimIndent()
            },
        ),
        Definition(
            name = "Codex",
            config = { home(".codex/config.toml") },
            snippet = { url ->
                """
                [mcp_servers.review-relay]
                url = "$url"
                """.trimIndent()
            },
        ),
        Definition(
            name = "Cursor",
            config = { home(".cursor/mcp.json") },
            snippet = { url ->
                """
                "mcpServers": {
                  "review-relay": { "url": "$url" }
                }
                """.trimIndent()
            },
        ),
    )
}
