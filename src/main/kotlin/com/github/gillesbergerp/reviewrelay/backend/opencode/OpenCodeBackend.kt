package com.github.gillesbergerp.reviewrelay.backend.opencode

import com.github.gillesbergerp.reviewrelay.review.model.BackendId
import com.github.gillesbergerp.reviewrelay.review.model.SessionId
import com.github.gillesbergerp.reviewrelay.backend.ActivityCapability
import com.github.gillesbergerp.reviewrelay.backend.AgentBackend
import com.github.gillesbergerp.reviewrelay.backend.AgentBackendFactory
import com.github.gillesbergerp.reviewrelay.backend.AgentSession
import com.github.gillesbergerp.reviewrelay.backend.BackendHost
import com.github.gillesbergerp.reviewrelay.backend.BackendStatus
import com.github.gillesbergerp.reviewrelay.backend.Delivery
import com.github.gillesbergerp.reviewrelay.backend.InterruptCapability
import com.github.gillesbergerp.reviewrelay.backend.PromptCapability
import com.github.gillesbergerp.reviewrelay.backend.PublishOutcome
import com.github.gillesbergerp.reviewrelay.backend.ReconnectCapability
import com.github.gillesbergerp.reviewrelay.backend.ReviewRequest
import com.github.gillesbergerp.reviewrelay.backend.SessionCapability
import com.github.gillesbergerp.reviewrelay.backend.SessionCreation
import com.github.gillesbergerp.reviewrelay.review.ReviewText
import com.github.gillesbergerp.reviewrelay.ui.settings.ReviewRelaySettings
import com.github.gillesbergerp.reviewrelay.util.Directory
import java.time.Instant
import com.github.gillesbergerp.reviewrelay.util.PathMatch
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.TimeoutException

/**
 * A running OpenCode server, reached over its HTTP API.
 *
 * The one backend that can start a turn rather than wait to be asked, which is why it is also the
 * only one that can say whether the agent is working and stop it.
 */
class OpenCodeBackend(private val host: BackendHost) : AgentBackend {

    override val id = BackendId("opencode")
    override val displayName = "OpenCode"
    override val delivery = Delivery.PUSH

    @Volatile
    private var endpoint: OpenCodeEndpoint? = null

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var loaded: List<AgentSession> = emptyList()

    @Volatile
    private var busy: Set<String> = emptySet()

    @Volatile
    private var seenActivity = false

    /** What a review with no session of its own is offered: this project's newest. */
    @Volatile
    private var preferredId: SessionId? = null

    /** Coalesces the diff events a single turn produces into one refresh of the bar. */
    private val diffs = MergingUpdateQueue("ReviewRelayOpenCodeDiff", DIFF_QUIET.inWholeMilliseconds.toInt(), true, null, this)

    private val subscriptionLock = Any()
    private var subscription: EventSubscription? = null

    private val directory: String? get() = host.project.basePath

    override val status: BackendStatus
        get() = BackendStatus(
            label = endpoint?.label ?: "not connected",
            problem = lastError,
            connecting = endpoint == null && lastError == null,
        )

    override val sessions = object : SessionCapability {
        override val sessions: List<AgentSession> get() = loaded

        /** Never one in an unrelated directory: that needs confirming before a send, not offering. */
        override val preferred: SessionId? get() = preferredId

        override fun borrowedFrom(sessionId: SessionId?): Directory? =
            sessionOf(sessionId)?.directory?.takeIf { it != Directory.of(directory) }

        override val creation = SessionCreation { title -> create(title) }
    }

    override val activity = object : ActivityCapability {
        override fun isBusy(sessionId: SessionId?) = sessionId != null && sessionId.value in busy
        override val observed: Boolean get() = seenActivity
    }

    override val interrupt = InterruptCapability { sessionId -> abort(sessionId) }

    override val reconnect = ReconnectCapability {
        closeSubscription()
        endpoint = null
        lastError = null
    }
    override fun refresh() {
        val directory = directory ?: return
        try {
            val client = ensureClient() ?: return
            // OpenCode keys sessions to the exact directory they were created in, so a worktree
            // opened in the IDE finds nothing of its own even though the agent has been working on
            // the same branch from the checkout it is a sandbox of.
            val group = listOfNotNull(directory, mainCheckoutOf(client, directory))
            val here = group.flatMap { client.sessions(it) }.distinctBy { it.id }.pickable()
            val known = here.mapTo(HashSet()) { it.id }
            val elsewhere = runCatching { client.sessions(null, pages = ALL_PAGES) }.getOrDefault(emptyList())
                .pickable()
                .filterNot { it.id in known }

            loaded = here + elsewhere
            preferredId = here.firstOrNull()?.id

            val working = runCatching { client.busySessions(directory) }.getOrNull()
            if (working != null) {
                busy = working
                seenActivity = true
            }
            lastError = null
            ensureSubscribed()
            // Anything, not only OpenCodeException: a payload this build cannot decode and a URL
            // that will not parse both reached the pooled thread as an IDE error on every refresh.
        } catch (e: Exception) {
            // OpenChamber restarts its managed server on a health check failure, which moves the port.
            endpoint = null
            closeSubscription()
            lastError = e.message ?: e.javaClass.simpleName
            LOG.info("OpenCode refresh failed: ${e.message}")
        } finally {
            host.stateChanged()
        }
    }

    /** The directory an API call about [sessionId] has to be scoped to. */
    private fun sessionDirectory(sessionId: SessionId?): String? =
        sessions.sessionOf(sessionId)?.directory?.path?.takeIf { it.isNotBlank() } ?: directory


    override val prompting = PromptCapability { sessionId, text ->
        val client = ensureClient()
        when {
            sessionId == null -> PublishOutcome.Blocked("Pick an OpenCode session in the tool window first.")
            client == null -> PublishOutcome.Blocked(lastError ?: "No OpenCode server found.")
            else -> {
                val where = sessionDirectory(sessionId) ?: directory.orEmpty()
                val body = mapOf(
                    "parts" to listOf(mapOf("type" to "text", "text" to text)),
                    "agent" to ReviewRelaySettings.instance.state.agent.takeIf { it.isNotBlank() },
                )
                // The prompt's own first line, so the session can be asked whether it landed.
                val handles = listOfNotNull(text.lineSequence().firstOrNull { it.isNotBlank() })
                runCatching { deliverAndConfirm(client, sessionId, where, body, handles) }
                    .fold(
                        onSuccess = { PublishOutcome.Delivered(sessions.sessionOf(sessionId)?.title ?: "OpenCode") },
                        onFailure = { PublishOutcome.Blocked("Could not reach OpenCode: ${it.message ?: it::class.simpleName}") },
                    )
            }
        }
    }

    override fun publish(request: ReviewRequest): PublishOutcome {
        val sessionId = request.session
            ?: return PublishOutcome.Blocked("Pick an OpenCode session in the tool window first.")
        val directory = sessionDirectory(sessionId) ?: request.projectDirectory
        val client = ensureClient()
            ?: return PublishOutcome.Blocked(lastError ?: "No OpenCode server found.")

        val body = ReviewPayload.body(
            basePath = request.projectDirectory,
            summary = request.summary,
            threads = request.threads,
            agent = ReviewRelaySettings.instance.state.agent,
            sessionDirectory = directory,
            inWorkingTree = request.inWorkingTree,
            lineInWorkingTree = request.lineInWorkingTree,
            briefed = request.briefed,
        )
        val handles = request.threads.map { ReviewText.handle(it) }
        deliverAndConfirm(client, sessionId, directory, body, handles)
        return PublishOutcome.Delivered(sessions.sessionOf(sessionId)?.title ?: "OpenCode")
    }

    /**
     * Posts [body] and returns once the session has it, rather than once the post answers.
     *
     * OpenCode answers the post only after reading every attached range, long after the message is
     * in the session. Two things say so sooner: the agent picking up work, which a session that was
     * already busy cannot show, and the message turning up in its own messages.
     */
    private fun deliverAndConfirm(
        client: OpenCodeClient,
        sessionId: SessionId,
        directory: String,
        body: Map<String, Any?>,
        handles: List<String>,
    ) {
        val wasBusy = activity.isBusy(sessionId)
        // What the session already said about these handles, so only a new mention counts as arrival.
        val mentioned = mentions(client, sessionId.value, directory, handles)
        val posted = ApplicationManager.getApplication().executeOnPooledThread<Throwable?> {
            runCatching { deliver(client, sessionId.value, directory, body, handles, mentioned) }
                .exceptionOrNull()
        }

        var nextLook = System.currentTimeMillis() + CONFIRM_INTERVAL.inWholeMilliseconds
        while (true) {
            if (!wasBusy && activity.isBusy(sessionId)) return
            try {
                posted.get(200, TimeUnit.MILLISECONDS)?.let { throw it }
                return
            } catch (_: TimeoutException) {
            }
            if (System.currentTimeMillis() >= nextLook) {
                nextLook = System.currentTimeMillis() + CONFIRM_INTERVAL.inWholeMilliseconds
                if (arrived(client, sessionId.value, directory, handles, mentioned)) return
            }
        }
    }

    /** Whether the message is in the session already, whatever the post it came from is still doing. */
    private fun arrived(
        client: OpenCodeClient,
        sessionId: String,
        directory: String,
        handles: List<String>,
        mentioned: Int,
    ): Boolean = handles.isNotEmpty() && mentions(client, sessionId, directory, handles) > mentioned

    private fun mentions(
        client: OpenCodeClient,
        sessionId: String,
        directory: String,
        handles: List<String>,
    ): Int = runCatching { client.recentMessageMentions(sessionId, directory, handles) }.getOrDefault(0)

    private fun deliver(
        client: OpenCodeClient,
        sessionId: String,
        directory: String,
        body: Map<String, Any?>,
        handles: List<String>,
        mentioned: Int,
    ) {
        try {
            client.promptAsync(sessionId, directory, body)
        } catch (timeout: OpenCodeTimeout) {
            // Ambiguous by nature, so ask the session what it actually received.
            if (!arrived(client, sessionId, directory, handles, mentioned)) throw timeout
        }
    }

    private fun abort(sessionId: SessionId?) {
        if (sessionId == null) return
        val directory = sessionDirectory(sessionId) ?: return
        ensureClient()?.abort(sessionId.value, directory)
    }

    private fun create(title: String): AgentSession? {
        val directory = directory ?: return null
        val client = ensureClient() ?: return null
        val session = try {
            client.createSession(directory, title)
        } catch (timeout: OpenCodeTimeout) {
            // It usually did get made, so ask rather than report a failure the user can see is wrong.
            LOG.info("Session creation timed out; looking for it anyway")
            client.sessions(directory).firstOrNull { it.title == title } ?: throw timeout
        }.asAgentSession()

        // Show it and finish: the reload that reconciles the list costs two more calls, and the
        // session is usable before they answer.
        loaded = listOf(session) + loaded.filterNot { it.id == session.id }
        host.stateChanged()
        return session
    }

    /** Blocking: may run server discovery. Call off the EDT. */
    private fun ensureClient(): OpenCodeClient? {
        endpoint?.let { return OpenCodeClient(it) }
        val settings = ReviewRelaySettings.instance
        val discovered = ServerDiscovery.discover(
            DiscoveryOptions(
                explicitUrl = settings.state.serverUrl,
                username = settings.state.username,
                password = settings.password,
            )
        )
        if (discovered == null) {
            // "opencode serve" alone picks its own port, which nothing can then find.
            lastError = "No OpenCode server found. Start OpenChamber, or run 'opencode serve --mdns', " +
                "or set the server URL in Settings | Tools | Review Relay."
            return null
        }
        endpoint = discovered
        return OpenCodeClient(discovered)
    }

    /** The checkout this directory is a registered sandbox of, when it is one. */
    private fun mainCheckoutOf(client: OpenCodeClient, directory: String): String? = runCatching {
        client.projects()
            .firstOrNull { project ->
                !PathMatch.same(project.worktree, directory) &&
                    project.sandboxes.any { PathMatch.same(it, directory) }
            }
            ?.worktree
    }.getOrNull()

    /** Subagent conversations end with the task they were spawned for, so nothing can be sent to one. */
    private fun List<OpenCodeSession>.pickable(): List<AgentSession> =
        filter { it.parentId == null }
            .sortedByDescending { it.updatedAt }
            .map { it.asAgentSession() }

    private fun ensureSubscribed() {
        val directory = directory ?: return
        val client = endpoint?.let { OpenCodeClient(it) } ?: return
        synchronized(subscriptionLock) {
            // A reader that has ended leaves the field set, and nothing else would ever replace it.
            if (subscription?.isAlive == true) return
            subscription?.close()
            subscription = try {
                client.subscribe(directory) { handleEvent(it) }
            } catch (e: OpenCodeException) {
                LOG.info("Could not subscribe to OpenCode events: ${e.message}")
                null
            }
        }
    }

    private fun handleEvent(event: OpenCodeEvent) {
        when (event) {
            is OpenCodeEvent.SessionBusy -> {
                busy = if (event.busy) busy + event.sessionId else busy - event.sessionId
                seenActivity = true
                host.stateChanged()
            }
            is OpenCodeEvent.SessionIdle -> {
                busy = busy - event.sessionId
                seenActivity = true
                host.agentWentIdle(
                    SessionId(event.sessionId),
                    loaded.firstOrNull { it.id.value == event.sessionId }?.title ?: event.sessionId,
                )
                host.stateChanged()
            }
            is OpenCodeEvent.FileEdited -> host.fileEdited(event.file)
            // Per changed file while the agent writes, and each one rebuilt the session combo on the
            // EDT, closing an open popup: the bar only has to catch up, not keep pace.
            is OpenCodeEvent.SessionDiff -> diffs.queue(Update.create(this) { host.stateChanged() })
            OpenCodeEvent.Connected -> Unit
        }
    }

    private fun closeSubscription() {
        synchronized(subscriptionLock) {
            subscription?.close()
            subscription = null
        }
    }

    override fun dispose() = closeSubscription()

    private fun OpenCodeSession.asAgentSession() =
        AgentSession(SessionId(id), title, Directory.of(directory), Instant.ofEpochMilli(updatedAt))

    private companion object {
        val LOG = Logger.getInstance(OpenCodeBackend::class.java)

        val CONFIRM_INTERVAL = 2.seconds

        /** Long enough to swallow a burst of file writes, short enough not to feel like a lag. */
        val DIFF_QUIET = 300.milliseconds

        /** Enough for a few thousand sessions; a server with more is not worth the wait. */
        const val ALL_PAGES = 10
    }
}

/** Registered in plugin.xml; see [AgentBackendFactory]. */
class OpenCodeBackendFactory : AgentBackendFactory {
    override fun create(host: BackendHost) = OpenCodeBackend(host)
}
