package com.github.gillesbergerp.reviewrelay.backend.opencode

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * OpenCode's payloads as this build reads them.
 *
 * Only what is used is declared, and everything a session can survive without is optional: the
 * server gains fields between releases and a review must not stop working because of one.
 */
@Serializable
internal data class SessionPayload(
    val id: String,
    val title: String = "",
    /** Where a created session answers with it; a listed one carries [location] instead. */
    val directory: String = "",
    val location: Location? = null,
    @SerialName("parentID") val parentId: String? = null,
    val time: Times = Times(),
) {
    @Serializable
    data class Times(val updated: Long? = null, val created: Long? = null)

    @Serializable
    data class Location(val directory: String = "")

    fun asSession() = OpenCodeSession(
        id = id,
        title = title.ifEmpty { id },
        directory = directory.ifEmpty { location?.directory.orEmpty() },
        updatedAt = time.updated ?: time.created ?: 0L,
        parentId = parentId,
    )
}

/** The v2 list, which the OpenChamber proxy flattens to a bare array. */
@Serializable
internal data class SessionPage(
    val data: List<SessionPayload> = emptyList(),
    val cursor: Cursor = Cursor(),
) {
    @Serializable
    data class Cursor(val next: String? = null, val previous: String? = null)
}

@Serializable
internal data class ProjectPayload(
    val worktree: String,
    val sandboxes: List<String> = emptyList(),
)

@Serializable
internal data class SessionStatus(val type: String = "")

/** An event off the SSE stream; the properties vary by type, so each is optional. */
@Serializable
internal data class EventPayload(
    val type: String = "",
    val properties: Properties = Properties(),
) {
    @Serializable
    data class Properties(
        @SerialName("sessionID") val sessionId: String? = null,
        val file: String? = null,
        val status: SessionStatus? = null,
    )
}

/** Lenient by design: an unknown field is the server being newer, not the payload being wrong. */
internal val openCodeJson = Json { ignoreUnknownKeys = true }
