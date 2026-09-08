package com.github.gillesbergerp.reviewrelay.backend.claude

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A session file exactly as Claude Code writes it, and the key file that sits beside it.
 *
 * Every field a session cannot be built without is non-null here, so a file missing one fails to
 * decode rather than producing a half-built session; everything else is optional, because this
 * format is undocumented and gains fields without warning.
 */
@Serializable
internal data class ClaudeSessionFile(
    val pid: Int,
    @SerialName("sessionId") val sessionId: String,
    @SerialName("cwd") val directory: String,
    @SerialName("messagingSocketPath") val pipe: String,
    val peerProtocol: Int = 0,
    val kind: String = "",
    val name: String? = null,
    val status: String? = null,
    val waitingFor: String? = null,
    val updatedAt: Long? = null,
    val startedAt: Long? = null,
)

@Serializable
internal data class ClaudePeerKey(val peerToken: String)

/**
 * Unknown keys are ignored rather than refused: a Claude Code that adds a field must not make every
 * session on the machine unreadable.
 */
internal val claudeJson = Json { ignoreUnknownKeys = true }
