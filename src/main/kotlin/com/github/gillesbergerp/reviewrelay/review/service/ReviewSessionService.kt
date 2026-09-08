package com.github.gillesbergerp.reviewrelay.review.service

import com.github.gillesbergerp.reviewrelay.review.model.CommentDraft
import com.github.gillesbergerp.reviewrelay.review.model.CommentTarget
import com.github.gillesbergerp.reviewrelay.review.model.CommentType
import com.github.gillesbergerp.reviewrelay.review.model.ItemId
import com.github.gillesbergerp.reviewrelay.review.model.Delivery
import com.github.gillesbergerp.reviewrelay.review.model.MessageAuthor
import com.github.gillesbergerp.reviewrelay.review.model.Origin
import com.github.gillesbergerp.reviewrelay.review.model.Proposal
import com.github.gillesbergerp.reviewrelay.review.model.ProposalId
import com.github.gillesbergerp.reviewrelay.review.model.ProposalStanding
import com.github.gillesbergerp.reviewrelay.review.model.Suggestion
import com.github.gillesbergerp.reviewrelay.review.model.ReviewMessage
import com.github.gillesbergerp.reviewrelay.review.model.ReviewSession
import com.github.gillesbergerp.reviewrelay.review.model.MessageId
import com.github.gillesbergerp.reviewrelay.review.model.ReviewId
import com.github.gillesbergerp.reviewrelay.review.model.ThreadId
import com.github.gillesbergerp.reviewrelay.review.model.AgentConversation
import com.github.gillesbergerp.reviewrelay.review.model.BackendId
import com.github.gillesbergerp.reviewrelay.review.model.SessionId
import com.github.gillesbergerp.reviewrelay.review.model.ReviewThread
import com.github.gillesbergerp.reviewrelay.review.model.LineRange
import com.github.gillesbergerp.reviewrelay.review.model.ReviewedFile
import com.github.gillesbergerp.reviewrelay.review.model.Revision
import com.github.gillesbergerp.reviewrelay.review.model.Snippet
import com.github.gillesbergerp.reviewrelay.review.model.ThreadOutcome
import com.github.gillesbergerp.reviewrelay.review.model.ThreadStatus
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.annotations.Tag
import java.time.Instant
import com.intellij.util.xmlb.annotations.XCollection

interface ReviewThreadListener {
    fun commentsChanged()

    /** The set of reviews, or which one is active, rather than what is written in one. */
    fun reviewsChanged() {}

    companion object {
        val TOPIC: Topic<ReviewThreadListener> =
            Topic.create("ReviewThreadChanged", ReviewThreadListener::class.java)
    }
}

@State(
    name = "ReviewRelay",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
@Service(Service.Level.PROJECT)
class ReviewSessionService(private val project: Project?) : PersistentStateComponent<ReviewSessionService.State> {

    @Tag("message")
    class MessageState {
        @Tag("id") var id: String = ""
        @Tag("author") var author: String = "REVIEWER"
        @Tag("text") var text: String = ""
        @Tag("writtenAt") var writtenAt: String? = null
        @Tag("sentAt") var sentAt: String? = null
        @Tag("suggestionBase") var suggestionBase: String? = null
    }

    @Tag("thread")
    class ThreadState {
        @Tag("id") var id: String = ""
        @Tag("type") var type: String = "FIX"
        @Tag("filePath") var filePath: String? = null
        @Tag("lineStart") var lineStart: Int? = null
        @Tag("lineEnd") var lineEnd: Int? = null
        @Tag("revision") var revision: String? = null
        @Tag("outcome") var outcome: String? = null
        @Tag("reviewedCode") var reviewedCode: String? = null
        @Tag("sentBase") var sentBase: String? = null
        @Tag("codeChanged") var codeChanged: Boolean? = null
        @Tag("readAt") var readAt: String? = null
        @XCollection(style = XCollection.Style.v2) var messages: MutableList<MessageState> = mutableListOf()
        @XCollection(style = XCollection.Style.v2) var deliveries: MutableList<DeliveryState> = mutableListOf()
    }

    @Tag("delivery")
    class DeliveryState {
        @Tag("destination") var destination: String = ""
        @Tag("externalId") var externalId: String = ""
    }

    @Tag("proposal")
    class ProposalState {
        @Tag("id") var id: String = ""
        /** The agent that proposed it; blank means the reviewer did. */
        @Tag("agent") var agent: String = ""
        @Tag("type") var type: String = "FIX"
        @Tag("filePath") var filePath: String? = null
        @Tag("lineStart") var lineStart: Int? = null
        @Tag("lineEnd") var lineEnd: Int? = null
        @Tag("revision") var revision: String? = null
        @Tag("text") var text: String = ""
        @Tag("reviewedCode") var reviewedCode: String? = null
        @Tag("madeAt") var madeAt: String? = null
        @Tag("standing") var standing: String = OPEN
        @Tag("filedAs") var filedAs: String = ""
    }

    /** One review. Every stored element is named by a @Tag, so no class name here is the format. */
    @Tag("review")
    class ReviewState {
        @Tag("id") var id: String = ""
        @Tag("name") var name: String = ""
        @Tag("startedAgainst") var startedAgainst: String = ""
        @Tag("createdAt") var createdAt: String = ""
        @Tag("open") var open: Boolean = true
        @Tag("summary") var summary: String = ""
        @Tag("selection") var selection: String = ""
        @Tag("agentBackend") var agentBackend: String = ""
        @Tag("agentSession") var agentSession: String = ""
        @Tag("briefedSession") var briefedSession: String = ""
        @XCollection(style = XCollection.Style.v2) var threads: MutableList<ThreadState> = mutableListOf()
        @XCollection(style = XCollection.Style.v2) var proposals: MutableList<ProposalState> = mutableListOf()
    }

    class State {
        @XCollection(style = XCollection.Style.v2) var reviews: MutableList<ReviewState> = mutableListOf()
        @Tag("activeId") var activeId: String = ""

        /** The review an agent was last given, which is the one its tools must keep serving. */
        @Tag("publishedId") var publishedId: String = ""
    }

    @Volatile
    private var reviews: List<ReviewSession> = listOf(ReviewSession(name = DEFAULT_NAME))
    private var activeId: ReviewId = reviews.first().id
        set(value) {
            if (field == value) return
            field = value
            // The comment on screen belongs to the review on screen, and every mover used to have
            // to remember to say so.
            focusedThreadId = null
        }
    private var publishedId: ReviewId? = null

    /**
     * The active review. Settable so every mutator below can keep saying `session = session.copy(..)`
     * without knowing there is more than one.
     */
    private var session: ReviewSession
        get() = reviews.firstOrNull { it.id == activeId } ?: reviews.first()
        set(value) {
            val at = reviews.indexOfFirst { it.id == value.id }
            reviews = if (at >= 0) reviews.replacing(at, value) else reviews + value
        }

    /**
     * Rewrites the review [id] names, and says whether that changed anything.
     *
     * Every write goes through here, so the published list is only ever swapped for a whole new one:
     * a reader on another thread sees the review before or the review after, never one being edited.
     */
    private fun mutate(id: ReviewId, change: (ReviewSession) -> ReviewSession): Boolean {
        val at = reviews.indexOfFirst { it.id == id }
        if (at < 0) return false
        val was = reviews[at]
        val now = change(was)
        if (now == was) return false
        reviews = reviews.replacing(at, now)
        return true
    }

    /** Mutates and tells the comment list, which is what all but a handful of the writes here do. */
    private fun mutateAndFire(id: ReviewId, change: (ReviewSession) -> ReviewSession): Boolean =
        mutate(id, change).also { if (it) fireCommentsChanged() }

    val currentSession: ReviewSession get() = session

    val openReviews: List<ReviewSession> get() = reviews.filter { it.open }

    val closedReviews: List<ReviewSession> get() = reviews.filterNot { it.open }

    val activeReviewId: ReviewId get() = session.id

    fun review(id: ReviewId): ReviewSession? = reviews.firstOrNull { it.id == id }

    /** The review whose threads an agent is working from, which a click elsewhere must not move. */
    fun publishedReview(): ReviewSession = reviews.firstOrNull { it.id == publishedId } ?: session

    fun markPublished(id: ReviewId = activeId) {
        publishedId = id
        fireCommentsChanged()
    }

    /** Every review, so a reply can find the thread it names wherever the reviewer has moved on to. */
    fun threadAnywhere(handle: (ReviewThread) -> String, wanted: String): Pair<ReviewSession, ReviewThread>? {
        val ordered = listOf(publishedReview()) + reviews.filterNot { it.id == publishedId }
        for (review in ordered) {
            val thread = review.threads.firstOrNull { handle(it) == wanted } ?: continue
            return review to thread
        }
        return null
    }

    fun createReview(name: String, startedAgainst: String): ReviewSession {
        val fresh = ReviewSession(name = name.ifBlank { DEFAULT_NAME }, startedAgainst = startedAgainst)
        reviews = reviews + fresh
        activeId = fresh.id
        fireReviewsChanged()
        return fresh
    }

    fun renameReview(id: ReviewId, name: String) {
        if (name.isBlank()) return
        if (mutate(id) { it.copy(name = name) }) fireReviewsChanged()
    }

    fun activateReview(id: ReviewId) {
        if (id == activeId || reviews.none { it.id == id }) return
        activeId = id
        fireReviewsChanged()
    }

    /**
     * Put away, not thrown away.
     *
     * Closing the last one opens a fresh review rather than refusing: the tab is already gone by the
     * time this is asked, so a refusal leaves the strip and the model saying different things.
     */
    fun closeReview(id: ReviewId) {
        if (reviews.none { it.id == id && it.open }) return
        mutate(id) { it.copy(open = false) }
        if (reviews.none { it.open }) reviews = reviews + ReviewSession(name = DEFAULT_NAME)
        if (activeId == id) activeId = reviews.first { it.open }.id
        fireReviewsChanged()
    }

    fun reopenReview(id: ReviewId) {
        if (!mutate(id) { it.copy(open = true) } && reviews.none { it.id == id }) return
        activeId = id
        fireReviewsChanged()
    }

    fun deleteReview(id: ReviewId) {
        if (reviews.none { it.id == id }) return
        reviews = reviews.filterNot { it.id == id }
        if (reviews.none { it.open }) reviews = reviews + ReviewSession(name = DEFAULT_NAME)
        if (reviews.none { it.id == activeId }) activeId = reviews.first { it.open }.id
        if (publishedId == id) publishedId = null
        fireReviewsChanged()
    }

    private fun fireReviewsChanged() {
        project?.messageBus?.syncPublisher(ReviewThreadListener.TOPIC)?.reviewsChanged()
        fireCommentsChanged()
    }

    /** Threads carrying something the reviewer has written but not sent, in reading order. */
    val pendingThreads: List<ReviewThread>
        get() = session.threads
            .filter { it.isPending }
            .sortedWith(compareBy({ it.file }, { it.lines?.start ?: 0 }))

    private fun fireCommentsChanged() {
        project?.messageBus?.syncPublisher(ReviewThreadListener.TOPIC)?.commentsChanged()
    }

    /** The comment the reviewer is on. Transient: it means nothing after a restart. */
    @Volatile
    var focusedThreadId: ThreadId? = null

    fun addThread(thread: ReviewThread) {
        mutateAndFire(activeId) { it.copy(threads = it.threads + thread) }
    }

    fun removeThread(threadId: ThreadId) {
        mutateAndFire(activeId) { review ->
            review.copy(threads = review.threads.filterNot { it.id == threadId })
        }
    }

    fun thread(threadId: ThreadId): ReviewThread? = session.threads.firstOrNull { it.id == threadId }

    /**
     * Records a proposal unless this review already holds it.
     *
     * The same by [Proposal.fingerprint] whatever became of it: a scan run twice must not put back
     * what was dismissed the first time.
     */
    fun addProposal(reviewId: ReviewId, proposal: Proposal): Boolean {
        val review = reviews.firstOrNull { it.id == reviewId } ?: return false
        if (review.proposals.any { it.fingerprint == proposal.fingerprint }) return false
        return mutateAndFire(reviewId) { it.copy(proposals = it.proposals + proposal) }
    }

    fun proposal(id: ProposalId): Proposal? = reviews.firstNotNullOfOrNull { review ->
        review.proposals.firstOrNull { it.id == id }
    }

    fun dismissProposal(id: ProposalId): Boolean =
        updateProposal(id) { it.copy(standing = ProposalStanding.Dismissed) }

    /**
     * Signs a proposal into the review, in the reviewer's name and their words where they changed them.
     *
     * Written unsent, so it goes out with the next round like anything else written here - which is
     * what makes filing the one gate everything leaving the IDE has passed.
     */
    fun fileProposal(id: ProposalId, edited: CommentDraft? = null): ThreadId? {
        val review = reviews.firstOrNull { r -> r.proposals.any { it.id == id && it.isOpen } } ?: return null
        val proposal = review.proposals.first { it.id == id }
        val text = edited?.text ?: proposal.text
        val thread = ReviewThread(
            type = edited?.type ?: proposal.type,
            target = proposal.target,
            reviewedCode = proposal.reviewedCode,
            provenance = proposal.origin,
            messages = listOf(
                ReviewMessage(
                    author = MessageAuthor.REVIEWER,
                    text = text,
                    suggestionBase = if (edited != null) {
                        edited.suggestionBase
                    } else {
                        proposal.reviewedCode?.takeIf { Suggestion.isPresent(text) }
                    },
                ),
            ),
        )
        mutate(review.id) { it.copy(threads = it.threads + thread) }
        updateProposal(id) { it.copy(standing = ProposalStanding.Filed(thread.id)) }
        return thread.id
    }

    /**
     * What a destination made of each comment, so exporting the same review twice does not repeat.
     *
     * One record per place rather than per destination: posting a comment to a second pull request
     * must not erase that it is already on the first, or a repeat there would go unnoticed.
     */
    fun recordDeliveries(reviewId: ReviewId, destination: String, recorded: Map<ThreadId, String>) {
        if (recorded.isEmpty()) return
        mutateAndFire(reviewId) { review ->
            review.copy(
                threads = review.threads.map { thread ->
                    val externalId = recorded[thread.id] ?: return@map thread
                    thread.copy(
                        deliveries = thread.deliveries
                            .filterNot { it.destination == destination && it.externalId == externalId } +
                            Delivery(destination, externalId),
                    )
                },
            )
        }
    }

    private fun updateProposal(id: ProposalId, updater: (Proposal) -> Proposal): Boolean {
        val review = reviews.firstOrNull { r -> r.proposals.any { it.id == id } } ?: return false
        return mutateAndFire(review.id) { held ->
            held.copy(proposals = held.proposals.map { if (it.id == id) updater(it) else it })
        }
    }

    private fun update(threadId: ThreadId, updater: (ReviewThread) -> ReviewThread): ReviewThread? =
        update(activeId, threadId, updater)

    private fun update(
        reviewId: ReviewId,
        threadId: ThreadId,
        updater: (ReviewThread) -> ReviewThread,
    ): ReviewThread? {
        if (reviews.firstOrNull { it.id == reviewId }?.threads?.none { it.id == threadId } != false) return null
        mutateAndFire(reviewId) { review ->
            review.copy(threads = review.threads.map { if (it.id == threadId) updater(it) else it })
        }
        return reviews.firstOrNull { it.id == reviewId }?.threads?.firstOrNull { it.id == threadId }
    }

    /** Adds a reply, which by itself puts the thread back in the queue whatever its outcome was. */
    fun addMessage(threadId: ThreadId, message: ReviewMessage): ReviewThread? =
        addMessage(activeId, threadId, message)

    fun addMessage(reviewId: ReviewId, threadId: ThreadId, message: ReviewMessage): ReviewThread? =
        update(reviewId, threadId) { it.copy(messages = it.messages + message) }

    /** Drops one message. Only an unsent reply is offered this way, so the thread always survives. */
    fun removeMessage(threadId: ThreadId, messageId: MessageId): ReviewThread? =
        update(threadId) { thread -> thread.copy(messages = thread.messages.filterNot { it.id == messageId }) }

    fun editMessage(threadId: ThreadId, messageId: MessageId, text: String, suggestionBase: Snippet?): ReviewThread? =
        update(threadId) { thread ->
            thread.copy(
                messages = thread.messages.map { message ->
                    if (message.id == messageId) {
                        message.copy(text = text, suggestionBase = suggestionBase)
                    } else {
                        message
                    }
                }
            )
        }

    fun setType(threadId: ThreadId, type: CommentType): ReviewThread? = update(threadId) { it.copy(type = type) }

    fun resolve(threadId: ThreadId): ReviewThread? = close(threadId, ThreadOutcome.RESOLVED)

    fun wontFix(threadId: ThreadId): ReviewThread? = close(threadId, ThreadOutcome.WONT_FIX)

    /**
     * Closing accepts an outcome, so there has to be one out there to accept.
     *
     * A comment still being written is deleted rather than closed: an outcome cannot outrank the
     * unsent message that would drag the thread straight back to pending, and dropping that message
     * to make it stick would take the only words on the card with it.
     */
    private fun close(threadId: ThreadId, outcome: ThreadOutcome): ReviewThread? =
        update(threadId) { thread -> if (thread.isPending) thread else thread.copy(outcome = outcome) }

    /** [snapshots] records the reviewed lines per thread, to compare against once the agent stops. */
    fun markSent(snapshots: Map<ThreadId, Snippet?>) = markSent(activeId, snapshots)

    fun markSent(reviewId: ReviewId, snapshots: Map<ThreadId, Snippet?>) {
        if (snapshots.isEmpty()) return
        val now = Instant.now()
        mutateAndFire(reviewId) { review ->
            review.copy(
                threads = review.threads.map { thread ->
                    if (!snapshots.containsKey(thread.id)) {
                        thread
                    } else {
                        thread.copy(
                            messages = thread.messages.map {
                                if (it.author == MessageAuthor.REVIEWER && it.sentAt == null) {
                                    it.copy(sentAt = now)
                                } else {
                                    it
                                }
                            },
                            // A file that cannot be read now must not erase the last base we had.
                            sentBase = snapshots[thread.id] ?: thread.sentBase,
                            codeChanged = null,
                        )
                    }
                },
            )
        }
    }

    /** Stamped with now rather than the reply's time: what matters is that a later one is new again. */
    fun markRead(threadId: ThreadId) {
        if (session.threads.none { it.id == threadId && it.unread }) return
        val now = Instant.now()
        mutateAndFire(activeId) { review ->
            review.copy(threads = review.threads.map { if (it.id == threadId) it.copy(readAt = now) else it })
        }
    }

    fun recordObservation(threadId: ThreadId, changed: Boolean) =
        recordObservation(activeId, threadId, changed)

    fun recordObservation(reviewId: ReviewId, threadId: ThreadId, changed: Boolean) {
        mutateAndFire(reviewId) { review ->
            review.copy(
                threads = review.threads.map { if (it.id == threadId) it.copy(codeChanged = changed) else it },
            )
        }
    }

    /** A proposal's lines move too: filing copies them into the comment it becomes. */
    fun updateLines(itemId: ItemId, moved: LineRange) {
        mutate(activeId) { review ->
            when (itemId) {
                is ThreadId -> review.copy(
                    threads = review.threads.map {
                        if (it.id == itemId) it.copy(target = it.target.movedTo(moved)) else it
                    },
                )

                is ProposalId -> review.copy(
                    proposals = review.proposals.map {
                        if (it.id == itemId) it.copy(target = it.target.movedTo(moved)) else it
                    },
                )
            }
        }
    }

    /** Drops everything already dealt with, leaving what is still open. */
    fun removeClosedThreads() {
        mutateAndFire(activeId) { review ->
            review.copy(threads = review.threads.filter { it.status.open })
        }
    }

    /** What the log has selected in this review, remembered so a restart does not lose it. */
    fun rememberSelection(reviewId: ReviewId, commits: List<Revision>) {
        mutate(reviewId) { it.copy(selection = commits) }
    }

    /** Whether [sessionId] has already had this review's rules, and so needs only a recap. */
    fun briefed(reviewId: ReviewId, sessionId: SessionId?): Boolean =
        sessionId != null && review(reviewId)?.briefedSession == sessionId

    fun markBriefed(reviewId: ReviewId, sessionId: SessionId?) {
        if (sessionId == null) return
        mutate(reviewId) { it.copy(briefedSession = sessionId) }
    }

    /** The agent conversation this review is worked in, which outlives a reconnect and a restart. */
    fun rememberConversation(reviewId: ReviewId, conversation: AgentConversation?) {
        mutate(reviewId) { it.copy(conversation = conversation) }
    }

    /**
     * The review an agent was actually given, or null before that has happened.
     *
     * Not [publishedReview], which falls back to the active one so a tool always has a review to
     * serve: marking a tab with that fallback claimed a review had been published on first launch.
     */
    val publishedReviewId: ReviewId? get() = publishedId

    fun updateSummary(text: String) = updateSummary(activeId, text)

    fun updateSummary(reviewId: ReviewId, text: String) {
        mutate(reviewId) { it.copy(summary = text) }
    }

    /** Empties the active review rather than replacing it: the tab and its name stay. */
    fun clearSession() {
        session = session.copy(summary = "", threads = emptyList())
        fireCommentsChanged()
    }

    override fun getState(): State {
        val state = State()
        state.activeId = activeId.value
        state.publishedId = publishedId?.value.orEmpty()
        state.reviews = reviews.map { review ->
            ReviewState().apply {
                id = review.id.value
                name = review.name
                startedAgainst = review.startedAgainst
                createdAt = review.createdAt.toString()
                open = review.open
                summary = review.summary
                selection = review.selection.joinToString(",") { it.hash }
                agentBackend = review.conversation?.backendId?.value.orEmpty()
                agentSession = review.conversation?.sessionId?.value.orEmpty()
                briefedSession = review.briefedSession?.value.orEmpty()
                threads = review.threads.map { thread -> persist(thread) }.toMutableList()
                proposals = review.proposals.map { proposal -> persist(proposal) }.toMutableList()
            }
        }.toMutableList()
        return state
    }

    private fun persist(proposal: Proposal): ProposalState = ProposalState().apply {
        id = proposal.id.value
        agent = (proposal.origin as? Origin.Agent)?.name.orEmpty()
        type = proposal.type.name
        filePath = proposal.file.path
        lineStart = proposal.lines?.start
        lineEnd = proposal.lines?.end?.takeIf { proposal.lines?.single == false }
        revision = proposal.revision?.hash
        text = proposal.text
        reviewedCode = proposal.reviewedCode?.text
        madeAt = proposal.madeAt.toString()
        standing = when (proposal.standing) {
            is ProposalStanding.Open -> OPEN
            is ProposalStanding.Dismissed -> DISMISSED
            is ProposalStanding.Filed -> FILED
        }
        filedAs = (proposal.standing as? ProposalStanding.Filed)?.thread?.value.orEmpty()
    }

    private fun persist(thread: ReviewThread): ThreadState = ThreadState().apply {
        id = thread.id.value
        type = thread.type.name
        filePath = thread.file.path
        lineStart = thread.lines?.start
        lineEnd = thread.lines?.end?.takeIf { thread.lines?.single == false }
        revision = thread.revision?.hash
        outcome = thread.outcome?.name
        reviewedCode = thread.reviewedCode?.text
        sentBase = thread.sentBase?.text
        codeChanged = thread.codeChanged
        readAt = thread.readAt?.toString()
        messages = thread.messages.map { message ->
            MessageState().apply {
                id = message.id.value
                author = message.author.name
                text = message.text
                writtenAt = message.writtenAt.toString()
                sentAt = message.sentAt?.toString()
                suggestionBase = message.suggestionBase?.text
            }
        }.toMutableList()
        deliveries = thread.deliveries.map { sent ->
            DeliveryState().apply {
                destination = sent.destination
                externalId = sent.externalId
            }
        }.toMutableList()
    }

    override fun loadState(state: State) {
        reviews = state.reviews.map { saved ->
            ReviewSession(
                id = saved.id.takeIf { it.isNotBlank() }?.let(::ReviewId) ?: ReviewId.fresh(),
                name = saved.name.ifBlank { DEFAULT_NAME },
                startedAgainst = saved.startedAgainst,
                createdAt = instantOf(saved.createdAt) ?: Instant.EPOCH,
                open = saved.open,
                summary = saved.summary,
                selection = saved.selection.split(",").filter { it.isNotBlank() }.map(::Revision),
                conversation = saved.agentBackend.takeIf { it.isNotBlank() }?.let { backend ->
                    AgentConversation(BackendId(backend), saved.agentSession.takeIf { it.isNotBlank() }?.let(::SessionId))
                },
                briefedSession = saved.briefedSession.takeIf { it.isNotBlank() }?.let(::SessionId),
                threads = restore(saved.threads),
                proposals = restoreProposals(saved.proposals),
            )
        }
        if (reviews.none { it.open }) reviews = reviews + ReviewSession(name = DEFAULT_NAME)
        // An id that no longer names anything falls back rather than leaving nothing selected.
        activeId = ReviewId(state.activeId).takeIf { id -> reviews.any { it.id == id && it.open } }
            ?: reviews.first { it.open }.id
        publishedId = ReviewId(state.publishedId).takeIf { id -> reviews.any { it.id == id } }
    }

    /**
     * Lines or no lines is the whole distinction, so nothing else records which kind it is.
     *
     * Null for a thread with no file, which the model has no room for and restore drops.
     */
    private fun target(path: String?, lineStart: Int?, lineEnd: Int?, hash: String?): CommentTarget? {
        val file = ReviewedFile.of(path) ?: return null
        val revision = hash?.let(::Revision)
        return LineRange.of(lineStart, lineEnd)
            ?.let { CommentTarget.Line(file, it, revision) }
            ?: CommentTarget.File(file, revision)
    }

    private fun restoreProposals(saved: List<ProposalState>): List<Proposal> =
        saved.mapNotNull { proposal ->
            val target = target(proposal.filePath, proposal.lineStart, proposal.lineEnd, proposal.revision)
                ?: return@mapNotNull null
            Proposal(
                id = proposal.id.takeIf { it.isNotBlank() }?.let(::ProposalId) ?: ProposalId.fresh(),
                origin = proposal.agent.takeIf { it.isNotBlank() }?.let(Origin::Agent) ?: Origin.Reviewer,
                type = enumOrDefault(proposal.type, CommentType.FIX),
                target = target,
                text = proposal.text,
                reviewedCode = Snippet.of(proposal.reviewedCode),
                madeAt = instantOf(proposal.madeAt) ?: Instant.EPOCH,
                standing = standingOf(proposal),
            )
        }.toMutableList()

    /**
     * A proposal already acted on stays acted on, even where the record is damaged.
     *
     * Filed but naming no comment reads as dismissed rather than open: putting it back would ask
     * the reviewer to triage something they have already dealt with.
     */
    private fun standingOf(proposal: ProposalState): ProposalStanding = when (proposal.standing) {
        DISMISSED -> ProposalStanding.Dismissed
        FILED -> proposal.filedAs.takeIf { it.isNotBlank() }
            ?.let { ProposalStanding.Filed(ThreadId(it)) }
            ?: ProposalStanding.Dismissed
        else -> ProposalStanding.Open
    }

    private fun restore(saved: List<ThreadState>): List<ReviewThread> =
        saved.mapNotNull { thread ->
                val target = target(thread.filePath, thread.lineStart, thread.lineEnd, thread.revision)
                    ?: return@mapNotNull null
                ReviewThread(
                    id = thread.id.takeIf { it.isNotBlank() }?.let(::ThreadId) ?: ThreadId.fresh(),
                    type = enumOrDefault(thread.type, CommentType.FIX),
                    target = target,
                    outcome = enumOrNull<ThreadOutcome>(thread.outcome),
                    reviewedCode = Snippet.of(thread.reviewedCode),
                    sentBase = Snippet.of(thread.sentBase),
                    codeChanged = thread.codeChanged,
                    readAt = instantOf(thread.readAt),
                    messages = thread.messages.map { message ->
                        ReviewMessage(
                            id = message.id.takeIf { it.isNotBlank() }?.let(::MessageId) ?: MessageId.fresh(),
                            author = enumOrDefault(message.author, MessageAuthor.REVIEWER),
                            text = message.text,
                            // Comments written before this was stored show no age rather than 1970.
                            writtenAt = instantOf(message.writtenAt) ?: instantOf(message.sentAt) ?: Instant.EPOCH,
                            sentAt = instantOf(message.sentAt),
                            suggestionBase = Snippet.of(message.suggestionBase),
                        )
                    },
                    deliveries = thread.deliveries
                        .filter { it.destination.isNotBlank() }
                        .map { Delivery(it.destination, it.externalId) },
                )
        }.toMutableList()

    companion object {

        const val DEFAULT_NAME = "Review"

        private const val OPEN = "OPEN"
        private const val DISMISSED = "DISMISSED"
        private const val FILED = "FILED"

        fun getInstance(project: Project): ReviewSessionService =
            project.getService(ReviewSessionService::class.java)
    }
}

/** Tolerant of a hand-edited or truncated workspace file, rather than failing the whole load. */
private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default

private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
    enumValues<T>().firstOrNull { it.name == name }

/**
 * A stored ISO-8601 instant, or null when it is absent or not one.
 *
 * The workspace file is text a person can edit; a time that will not parse costs that one field
 * rather than the review it belongs to.
 */
private fun instantOf(iso: String?): Instant? =
    iso?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

/** The same list with one entry swapped, since the list itself is never edited in place. */
private fun <T> List<T>.replacing(at: Int, value: T): List<T> =
    toMutableList().also { it[at] = value }
