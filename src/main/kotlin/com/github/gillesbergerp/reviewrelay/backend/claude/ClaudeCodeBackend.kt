package com.github.gillesbergerp.reviewrelay.backend.claude

import com.github.gillesbergerp.reviewrelay.review.model.BackendId
import com.github.gillesbergerp.reviewrelay.review.model.SessionId
import com.github.gillesbergerp.reviewrelay.backend.ActivityCapability
import com.github.gillesbergerp.reviewrelay.backend.AgentBackend
import com.github.gillesbergerp.reviewrelay.backend.AgentBackendFactory
import com.github.gillesbergerp.reviewrelay.backend.AgentSession
import com.github.gillesbergerp.reviewrelay.backend.BackendHost
import com.github.gillesbergerp.reviewrelay.backend.BackendStatus
import com.github.gillesbergerp.reviewrelay.backend.Delivery
import com.github.gillesbergerp.reviewrelay.backend.PromptCapability
import com.github.gillesbergerp.reviewrelay.backend.PublishOutcome
import com.github.gillesbergerp.reviewrelay.backend.ReconnectCapability
import com.github.gillesbergerp.reviewrelay.backend.ReviewRequest
import com.github.gillesbergerp.reviewrelay.backend.SessionCapability
import com.github.gillesbergerp.reviewrelay.util.Directory
import com.github.gillesbergerp.reviewrelay.util.GitDirs
import com.github.gillesbergerp.reviewrelay.util.PathMatch
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.Alarm
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * Claude Code, pushed to over the socket each session advertises.
 *
 * Not through the IDE plugin it connects to: that protocol carries a file reference and nothing that
 * can start a turn. The socket is undocumented, so a session that does not parse is simply absent and
 * the review stays collectable through the review tools.
 */
class ClaudeCodeBackend(private val host: BackendHost) : AgentBackend {

    override val id = BackendId("claude-code")
    override val displayName = "Claude Code"
    override val delivery = Delivery.PUSH

    @Volatile
    private var known: List<ClaudeSessions.Session> = emptyList()

    @Volatile
    private var sessionFileSeen = false

    private val poller = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    private val directory: String? get() = host.project.basePath

    /**
     * This project's own sessions, which are the ones a review is offered without confirming.
     *
     * Stored rather than filtered on demand: deciding it walks the directory tree looking for the
     * repository, and the bar reads this on every repaint.
     */
    @Volatile
    private var here: List<ClaudeSessions.Session> = emptyList()

    /** Started on the first refresh, which is the first sign a review here is worked by Claude. */
    private val polling = AtomicBoolean(false)

    override fun refresh() {
        if (polling.compareAndSet(false, true)) schedule()
        // Every session, this project's first: sending to one standing elsewhere is confirmed before
        // it goes, so the list does not have to do the refusing.
        val repository = GitDirs.repositoryOf(directory)
        val (ours, elsewhere) = ClaudeSessions.all()
            .partition { ClaudeSessions.inProject(it, directory, repository) }
        val found = ours + elsewhere
        // Once a session file has been read, an empty read means there are none rather than that we
        // cannot see them, which is what lets the bar say "idle" instead of staying quiet.
        if (found.isNotEmpty()) sessionFileSeen = true
        known = found
        here = ours
    }

    override val status: BackendStatus
        get() {
            if (here.isEmpty()) return BackendStatus("Claude Code", problem = "no session is running here")
            // The bar says working or idle; there is no endpoint here to name beside it.
            return BackendStatus("")
        }

    override val sessions = object : SessionCapability {
        override val sessions: List<AgentSession>
            get() = known.map { AgentSession(it.id, it.name, it.directory, it.updatedAt) }

        /** Never one in another directory: that has to be chosen rather than offered. */
        override val preferred: SessionId? get() = here.firstOrNull()?.id

        /** Worth saying only when the agent is somewhere other than the project under review. */
        override fun borrowedFrom(sessionId: SessionId?): Directory? =
            session(sessionId)?.directory?.takeIf { it != Directory.of(directory) }
    }

    override val activity = object : ActivityCapability {
        override fun isBusy(sessionId: SessionId?) = session(sessionId)?.busy == true

        override val observed: Boolean get() = sessionFileSeen

        override fun waitingFor(sessionId: SessionId?) = session(sessionId)?.takeIf { it.waiting }?.prompt
    }

    private fun session(sessionId: SessionId?) = sessionId?.let { id -> known.firstOrNull { it.id == id } }

    /** The socket carries no question of its own for some waits, so one has to be supplied. */
    private val ClaudeSessions.Session.prompt: String get() = waitingFor ?: "needs you"

    /** Nothing is cached to throw away here; rereading the session files is the whole of it. */
    override val reconnect = ReconnectCapability {}

    override val prompting = PromptCapability { sessionId, text ->
        refresh()
        val session = session(sessionId)
        when {
            session == null -> PublishOutcome.Blocked("No Claude Code session is running here.")
            else -> try {
                PeerPipe.send(session, text)
                PublishOutcome.Delivered(session.name)
            } catch (e: PeerPipe.Undeliverable) {
                PublishOutcome.Blocked("Could not reach ${session.name}: ${e.message}")
            }
        }
    }

    override fun publish(request: ReviewRequest): PublishOutcome {
        refresh()
        val session = session(request.session) ?: return PublishOutcome.Blocked(
            "No Claude Code session is running in ${request.projectDirectory}. Start one, or " +
                "publish and ask it to address the review."
        )
        return try {
            PeerPipe.send(session, ClaudeMessage.text(request, session.directory.path))
            PublishOutcome.Delivered(session.name)
        } catch (e: PeerPipe.Undeliverable) {
            PublishOutcome.Blocked("Could not reach ${session.name}: ${e.message}")
        }
    }

    /** Polled because the session file is the only signal; there is nothing to subscribe to. */
    private fun schedule() {
        if (poller.isDisposed) return
        poller.addRequest({
            // The next poll is scheduled whatever happened: one throwing callback used to end the
            // polling for the rest of the IDE session, with nothing said about it.
            try {
                poll()
            } catch (e: Exception) {
                LOG.info("Could not read the Claude Code sessions: ${e.message}")
            } finally {
                schedule()
            }
        }, POLL.inWholeMilliseconds.toInt())
    }

    private fun poll() {
        val before = signature()
        // No session is "the" one any more, so a turn is announced for whichever ended it.
        val was = known.associateBy { it.id }
        runCatching { refresh() }
        if (signature() == before) return
        here.forEach { now ->
            val then = was[now.id]
            when {
                now.waiting && then?.waiting != true -> host.agentNeedsInput(now.id, now.name, now.prompt)
                then?.busy == true && !now.busy && !now.waiting -> host.agentWentIdle(now.id, now.name)
            }
        }
        host.stateChanged()
    }

    /** Everything the bar shows, so a session appearing wakes it and not only a turn ending. */
    private fun signature() = known.joinToString { "${it.id}:${it.status}:${it.waitingFor}" }

    override fun dispose() = poller.dispose()

    private companion object {
        val LOG = Logger.getInstance(ClaudeCodeBackend::class.java)
        val POLL = 2.seconds
    }
}

/** Registered in plugin.xml; see [AgentBackendFactory]. */
class ClaudeCodeBackendFactory : AgentBackendFactory {
    override fun create(host: BackendHost) = ClaudeCodeBackend(host)
}
