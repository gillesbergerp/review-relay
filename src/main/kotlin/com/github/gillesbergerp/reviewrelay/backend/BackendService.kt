package com.github.gillesbergerp.reviewrelay.backend


import com.github.gillesbergerp.reviewrelay.ui.editor.InlineCommentManager
import com.github.gillesbergerp.reviewrelay.review.model.AgentConversation
import com.github.gillesbergerp.reviewrelay.review.model.BackendId
import com.github.gillesbergerp.reviewrelay.review.model.ReviewId
import com.github.gillesbergerp.reviewrelay.review.model.SessionId
import com.github.gillesbergerp.reviewrelay.review.service.ReviewSessionService
import com.github.gillesbergerp.reviewrelay.review.service.notifyReview
import com.github.gillesbergerp.reviewrelay.ui.settings.ReviewRelaySettings
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.github.gillesbergerp.reviewrelay.util.PathMatch
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.messages.Topic
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** What the plugin is doing to the agent right now, so its button cannot be pressed again meanwhile. */
enum class BackendTask { CONNECT, CREATE, PUBLISH, INTERRUPT, EXPORT }

interface BackendStateListener {
    fun backendStateChanged()

    companion object {
        val TOPIC: Topic<BackendStateListener> =
            Topic.create("ReviewRelayStateChanged", BackendStateListener::class.java)
    }
}

/**
 * Every backend this project can drive, and everything true of any of them.
 *
 * Holds the in-flight lock and the change signal so a backend has only its own protocol to worry
 * about, and reacts to an agent finishing on every backend's behalf.
 */
@Service(Service.Level.PROJECT)
class BackendService(override val project: Project) : BackendHost, Disposable {

    val available: List<AgentBackend> = AgentBackendFactory.EP_NAME.extensionList.map { it.create(this) }
        .onEach {
            // Through the Disposer, not just dispose(): a backend holding an Alarm is a root of the
            // tree, and calling dispose() by hand leaves that node - and this project - reachable.
            Disposer.register(this, it)
        }

    /** The agent a review is worked by. Reviews choose separately, so there is no current one. */
    fun backendOf(reviewId: ReviewId): AgentBackend =
        resolve(ReviewSessionService.getInstance(project).review(reviewId)?.conversation?.backendId)

    /** The agent driving the review on screen, which is what the toolbar's buttons act on. */
    val activeBackend: AgentBackend
        get() = backendOf(ReviewSessionService.getInstance(project).activeReviewId)

    /** What work on a review is keyed by: the conversation that review is worked in. */
    fun conversationOf(reviewId: ReviewId): AgentConversation? =
        ReviewSessionService.getInstance(project).review(reviewId)?.conversation

    val activeConversation: AgentConversation?
        get() = conversationOf(ReviewSessionService.getInstance(project).activeReviewId)

    /** A creation has no session yet, so it is keyed by the backend it is happening on. */
    fun creating(backend: AgentBackend) = AgentConversation(backend.id)

    /**
     * Every agent an open review is worked by, which is the set that has to be kept up to date.
     *
     * Refreshing the others would start discovery for agents nobody in this project is using.
     */
    val active: List<AgentBackend>
        get() = ReviewSessionService.getInstance(project).openReviews
            .map { it.conversation?.backendId }
            .distinct()
            .map(::resolve)
            .distinct()

    private fun resolve(id: BackendId?): AgentBackend =
        available.firstOrNull { it.id == id } ?: available.first()

    /**
     * The sessions a review was sent to, one per send in flight.
     *
     * A single slot lost a notification whenever a second review was sent before the first
     * finished, and the loss was silent: nothing reports a notification that never fired.
     */
    private val awaitingIdle: MutableSet<SessionId> = ConcurrentHashMap.newKeySet()

    private val runningLock = Any()

    /**
     * What is in flight, and on which conversation.
     *
     * Keyed rather than one flag per project, because sending review A refused review B outright
     * and greyed its buttons. [Work.on] is null only for work there is genuinely one of.
     */
    @Volatile
    private var running: Set<Work> = emptySet()

    /**
     * [on] is the conversation the work belongs to: a session for a send or an interrupt, a backend
     * with no session yet for a creation, and null for a refresh, which covers every backend at once.
     */
    private data class Work(val task: BackendTask, val on: AgentConversation?)

    fun isRunning(task: BackendTask, on: AgentConversation? = null): Boolean = Work(task, on) in running

    /** Claims [task], or returns false because it is already running. The winner must [end] it. */
    fun begin(task: BackendTask, on: AgentConversation? = null): Boolean = changing {
        val work = Work(task, on)
        if (work in running) return@changing false
        running = running + work
        true
    }

    fun end(task: BackendTask, on: AgentConversation? = null) =
        changing { running = running - Work(task, on) }

    /** What is in flight is what the buttons read, so no change to it may skip telling them. */
    private fun <T> changing(block: () -> T): T {
        val result = synchronized(runningLock, block)
        stateChanged()
        return result
    }

    /**
     * Runs [block] with [task] marked in flight, or not at all because it already is.
     *
     * It used to run the block either way and only skip the release, so the name promised an
     * exclusion the callers reading it as a lock never had.
     */
    fun <T> tracking(task: BackendTask, on: AgentConversation? = null, block: () -> T): T? {
        if (!begin(task, on)) return null
        return try {
            block()
        } finally {
            end(task, on)
        }
    }

    /** Skipped while one is in flight: every tab's bar asks for a refresh as it opens. */
    fun refreshAsync() {
        if (isRunning(BackendTask.CONNECT)) return
        ApplicationManager.getApplication().executeOnPooledThread { refresh() }
    }

    /** Blocking; call off the EDT. */
    fun refresh() {
        tracking(BackendTask.CONNECT) { active.forEach { it.refresh() } }
    }

    /**
     * Arms the "finished" notification for [sessionId], and only for it.
     *
     * One flag for the project fired on whichever agent stopped next, which with several reviews in
     * flight is rarely the one that was sent to.
     */
    fun awaitIdleNotification(backend: AgentBackend, sessionId: SessionId?) {
        if (backend.activity != null && sessionId != null) awaitingIdle.add(sessionId)
    }

    /** For a send that never left, so the next turn that agent runs is not reported as this one. */
    fun stopAwaitingIdle(sessionId: SessionId?) {
        sessionId?.let { awaitingIdle.remove(it) }
    }

    override fun stateChanged() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            project.messageBus.syncPublisher(BackendStateListener.TOPIC).backendStateChanged()
        }
    }

    override fun agentWentIdle(sessionId: SessionId, sessionLabel: String) {
        val sent = ReviewSessionService.getInstance(project).publishedReview()
        InlineCommentManager.getInstance(project).observeSentComments(sent)
        // Nothing to re-diff: a review is of commits now, which the agent editing files does not move.
        if (awaitingIdle.remove(sessionId)) {
            if (ReviewRelaySettings.instance.state.notifyWhenIdle) {
                notifyReview(project, "The agent finished working on \"$sessionLabel\".", NotificationType.INFORMATION)
            }
        }
    }

    /**
     * The agent stopped, but on a question rather than because it is done, so the arming stays.
     */
    override fun agentNeedsInput(sessionId: SessionId, sessionLabel: String, reason: String) {
        if (sessionId !in awaitingIdle) return
        if (!ReviewRelaySettings.instance.state.notifyWhenIdle) return
        notifyReview(
            project,
            "\"$sessionLabel\" is waiting for you: $reason.",
            NotificationType.INFORMATION,
        )
    }

    /** Without this the inlays keep the positions they had before the agent rewrote the file. */
    override fun fileEdited(path: String) {
        val base = project.basePath ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            // PathMatch, not File.separatorChar: off Windows that character is already the slash,
            // so an agent reporting a Windows path went unrecognised and its inlays never moved.
            val normalized = PathMatch.normalize(path)
            val absolute = if (normalized.startsWith(base) || normalized.matches(ABSOLUTE)) {
                normalized
            } else {
                "${base.trimEnd('/')}/$normalized"
            }
            // refreshAndFind, not find: a file the agent has just created is not in the VFS yet, and
            // its comments would have waited for something else to bring it in.
            LocalFileSystem.getInstance().refreshAndFindFileByPath(absolute)?.refresh(true, false)
        }
    }

    /** The backends go with it, each registered above. */
    override fun dispose() = Unit

    companion object {
        private val ABSOLUTE = Regex("^(?:[A-Za-z]:/|/).*")

        fun getInstance(project: Project): BackendService =
            project.getService(BackendService::class.java)
    }
}
