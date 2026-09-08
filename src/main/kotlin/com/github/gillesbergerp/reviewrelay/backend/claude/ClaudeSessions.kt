package com.github.gillesbergerp.reviewrelay.backend.claude

import com.github.gillesbergerp.reviewrelay.review.model.SessionId

import com.github.gillesbergerp.reviewrelay.util.Directory
import com.github.gillesbergerp.reviewrelay.util.GitDirs
import com.github.gillesbergerp.reviewrelay.util.PathMatch
import java.io.File
import java.time.Instant

/**
 * The Claude Code sessions running on this machine, as each one advertises itself on disk.
 *
 * Undocumented, so every field is checked rather than assumed and a session whose shape has moved is
 * dropped instead of guessed at.
 */
object ClaudeSessions {

    /** The peer protocol this was written against; a session announcing another is left alone. */
    private const val PROTOCOL = 1

    /** The one kind of session that can be sent to; the rest cannot start a turn. */
    private const val INTERACTIVE = "interactive"

    enum class Status {
        BUSY,

        /** Stopped, but on the reviewer rather than because it is done. */
        WAITING,

        /** Every other state, including one a later Claude Code writes that this build predates. */
        OTHER,
        ;

        internal companion object {
            fun of(wire: String?) = when (wire) {
                "busy" -> BUSY
                "waiting" -> WAITING
                else -> OTHER
            }
        }
    }

    data class Session(
        val pid: Int,
        val id: SessionId,
        val name: String,
        val directory: Directory,
        val pipe: String,
        val token: String,
        val status: Status,
        val waitingFor: String?,
        val updatedAt: Instant,
    ) {
        val busy: Boolean get() = status == Status.BUSY

        val waiting: Boolean get() = status == Status.WAITING
    }

    fun sessionsDirectory(): File = File(System.getProperty("user.home"), ".claude/sessions")

    /** Interactive sessions working in [projectDirectory], newest first. */
    fun inProject(projectDirectory: String?, from: File = sessionsDirectory()): List<Session> =
        all(from).filter { inProject(it, projectDirectory) }

    /**
     * Whether [session] is working on this project: in its directory, below it, or in another
     * worktree of the same repository.
     */
    fun inProject(session: Session, projectDirectory: String?): Boolean =
        inProject(session, projectDirectory, GitDirs.repositoryOf(projectDirectory))

    /**
     * The same question with the project's repository already in hand.
     *
     * Finding it walks the tree to the drive root, and asking it of every session on every poll is
     * that walk once per session per two seconds.
     */
    fun inProject(session: Session, projectDirectory: String?, repository: String?): Boolean =
        belongsTo(session.directory, projectDirectory, repository)

    fun all(from: File = sessionsDirectory()): List<Session> {
        val files = from.listFiles { f -> f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { read(it) }.sortedByDescending { it.updatedAt }
    }

    private fun belongsTo(directory: Directory, project: String?, repository: String?): Boolean {
        val here = Directory.of(project) ?: return false
        if (here.contains(directory)) return true
        // A worktree sits beside its checkout rather than inside it, so only the repository they
        // share identifies them as the same code.
        return repository != null && PathMatch.same(GitDirs.repositoryOf(directory.path), repository)
    }

    /** Null for anything this build cannot drive: the wrong protocol, a non-interactive session,
     *  a file whose shape has moved, or one whose token is missing. */
    private fun read(file: File): Session? {
        val payload = decode<ClaudeSessionFile>(file) ?: return null
        if (payload.peerProtocol != PROTOCOL || payload.kind != INTERACTIVE) return null
        val token = tokenFor(file.parentFile, payload.pid) ?: return null

        return Session(
            pid = payload.pid,
            id = SessionId(payload.sessionId),
            name = payload.name ?: payload.sessionId.take(8),
            directory = Directory(payload.directory),
            pipe = payload.pipe,
            token = token,
            status = Status.of(payload.status),
            waitingFor = payload.waitingFor,
            updatedAt = Instant.ofEpochMilli(payload.updatedAt ?: payload.startedAt ?: 0),
        )
    }

    /** The token sits beside the session under a name ending in a hash of the pipe path. */
    private fun tokenFor(directory: File, pid: Int): String? {
        val keys = directory.listFiles { f -> f.name.startsWith("$pid.") && f.extension == "key" }
        return keys?.firstNotNullOfOrNull { key -> decode<ClaudePeerKey>(key)?.peerToken }
    }

    /** A file that cannot be read or does not decode is one session missing, never a failure. */
    private inline fun <reified T> decode(file: File): T? = runCatching {
        claudeJson.decodeFromString<T>(file.readText())
    }.getOrNull()
}
