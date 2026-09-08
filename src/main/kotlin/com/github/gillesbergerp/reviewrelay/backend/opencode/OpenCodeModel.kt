package com.github.gillesbergerp.reviewrelay.backend.opencode


data class OpenCodeEndpoint(
    val baseUrl: String,
    val authHeader: String?,
    val label: String,
)

data class OpenCodeSession(
    val id: String,
    val title: String,
    val directory: String,
    val updatedAt: Long,
    val parentId: String? = null,
)

sealed interface OpenCodeEvent {
    data object Connected : OpenCodeEvent
    data class SessionIdle(val sessionId: String) : OpenCodeEvent
    data class SessionBusy(val sessionId: String, val busy: Boolean) : OpenCodeEvent
    data class FileEdited(val file: String) : OpenCodeEvent
    data class SessionDiff(val sessionId: String) : OpenCodeEvent
}

open class OpenCodeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The request may still have been delivered, so the caller has to check rather than assume. */
class OpenCodeTimeout(message: String, cause: Throwable? = null) : OpenCodeException(message, cause)

data class OpenCodeProject(
    val worktree: String,
    val sandboxes: List<String>,
)
