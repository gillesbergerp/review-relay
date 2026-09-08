package com.github.gillesbergerp.reviewrelay.review.service

import com.github.gillesbergerp.reviewrelay.review.model.AgentConversation
import com.github.gillesbergerp.reviewrelay.review.model.BackendId
import com.github.gillesbergerp.reviewrelay.review.model.Revision
import com.github.gillesbergerp.reviewrelay.review.model.SessionId
import com.github.gillesbergerp.reviewrelay.review.model.ThreadId
import com.github.gillesbergerp.reviewrelay.review.model.thread
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Many reviews in one project: the container, its persistence, and the isolation between them. */
class ReviewsTest {

    private lateinit var service: ReviewSessionService

    @Before
    fun setUp() {
        service = ReviewSessionService(null)
    }

    private fun reload(from: ReviewSessionService) =
        ReviewSessionService(null).apply { loadState(from.state) }

    @Test
    fun `a project starts with one open review`() {
        assertEquals(1, service.openReviews.size)
        assertEquals(service.openReviews.single().id, service.activeReviewId)
    }

    @Test
    fun `an empty file still yields somewhere to write`() {
        val loaded = ReviewSessionService(null).apply { loadState(ReviewSessionService.State()) }
        assertEquals(1, loaded.openReviews.size)
    }

    @Test
    fun `reviews and the active one survive a reload`() {
        service.updateSummary("first")
        val second = service.createReview("second", "abc1234")
        service.updateSummary("second summary")

        val loaded = reload(service)
        assertEquals(listOf("Review", "second"), loaded.openReviews.map { it.name })
        assertEquals(second.id, loaded.activeReviewId)
        assertEquals("abc1234", loaded.review(second.id)?.startedAgainst)
        assertEquals("first", loaded.openReviews.first().summary)
    }

    @Test
    fun `an active id naming nothing falls back to an open review`() {
        val state = service.state.apply { activeId = "gone" }
        val loaded = ReviewSessionService(null).apply { loadState(state) }
        assertEquals(loaded.openReviews.first().id, loaded.activeReviewId)
    }

    @Test
    fun `writing in one review leaves the other alone`() {
        val first = service.activeReviewId
        service.addThread(thread(id = ThreadId("a"), filePath = "A.kt"))
        val second = service.createReview("second", "")
        service.addThread(thread(id = ThreadId("b"), filePath = "B.kt"))

        assertEquals(listOf("a"), service.review(first)?.threads?.map { it.id.value })
        assertEquals(listOf("b"), service.review(second.id)?.threads?.map { it.id.value })
    }

    @Test
    fun `clearing empties the active review without removing it`() {
        service.addThread(thread(id = ThreadId("a")))
        val id = service.activeReviewId
        service.clearSession()
        assertEquals(id, service.activeReviewId)
        assertTrue(service.currentSession.threads.isEmpty())
    }

    @Test
    fun `closing puts a review away and moves off it`() {
        val first = service.activeReviewId
        val second = service.createReview("second", "")
        service.activateReview(first)
        service.closeReview(first)

        assertEquals(listOf("second"), service.openReviews.map { it.name })
        assertEquals(second.id, service.activeReviewId)
        assertEquals(1, service.closedReviews.size)
    }

    @Test
    fun `closing the last review puts it away and leaves a fresh one to write in`() {
        val only = service.activeReviewId
        service.addThread(thread(id = ThreadId("a")))
        service.closeReview(only)

        assertEquals(1, service.openReviews.size)
        assertEquals(listOf(only), service.closedReviews.map { it.id })
        assertTrue(service.openReviews.single().id != only)
        assertTrue(service.currentSession.threads.isEmpty())
        // Put away, not thrown away.
        assertEquals(listOf("a"), service.review(only)?.threads?.map { it.id.value })
    }

    @Test
    fun `closing a review that is already closed changes nothing`() {
        val first = service.activeReviewId
        service.createReview("second", "")
        service.closeReview(first)
        val after = service.openReviews.map { it.id }

        service.closeReview(first)
        assertEquals(after, service.openReviews.map { it.id })
        assertEquals(1, service.closedReviews.size)
    }

    @Test
    fun `a closed review comes back with its comments`() {
        service.addThread(thread(id = ThreadId("a")))
        val first = service.activeReviewId
        service.createReview("second", "")
        service.closeReview(first)
        service.reopenReview(first)

        assertEquals(first, service.activeReviewId)
        assertEquals(listOf("a"), service.review(first)?.threads?.map { it.id.value })
    }

    @Test
    fun `a closed review still reloads, so nothing is lost by putting it away`() {
        service.addThread(thread(id = ThreadId("a")))
        val first = service.activeReviewId
        service.createReview("second", "")
        service.closeReview(first)

        val loaded = reload(service)
        assertEquals(listOf("a"), loaded.review(first)?.threads?.map { it.id.value })
        assertEquals(1, loaded.closedReviews.size)
    }

    @Test
    fun `deleting the only review leaves a fresh one behind`() {
        val only = service.activeReviewId
        service.deleteReview(only)
        assertEquals(1, service.openReviews.size)
        assertNotEquals(only, service.activeReviewId)
    }

    @Test
    fun `a reply finds its thread in a review that is not active`() {
        service.addThread(thread(id = ThreadId("aaaaaaaa-1111"), filePath = "A.kt"))
        val first = service.activeReviewId
        service.markPublished(first)
        service.createReview("second", "")

        val found = service.threadAnywhere({ it.id.handle }, "aaaaaaaa")
        assertEquals(first, found?.first?.id)
        assertEquals("aaaaaaaa-1111", found?.second?.id?.value)
    }

    @Test
    fun `a handle in two reviews resolves to the published one`() {
        service.addThread(thread(id = ThreadId("shared-one"), filePath = "A.kt"))
        val second = service.createReview("second", "")
        service.addThread(thread(id = ThreadId("shared-two"), filePath = "B.kt"))
        service.markPublished(second.id)

        val found = service.threadAnywhere({ it.id.value.take(6) }, "shared")
        assertEquals(second.id, found?.first?.id)
    }

    private fun conversation(backend: String, session: String? = null) =
        AgentConversation(BackendId(backend), session?.let(::SessionId))

    @Test
    fun `a new review is pointed at no agent until one is picked for it`() {
        assertNull(service.review(service.activeReviewId)?.conversation)
        assertNull(service.createReview("second", "").conversation)
    }

    @Test
    fun `the conversation a review is worked in survives a reload`() {
        val id = service.activeReviewId
        service.rememberConversation(id, conversation("opencode", "ses-1"))
        assertEquals(conversation("opencode", "ses-1"), reload(service).review(id)?.conversation)
    }

    @Test
    fun `two reviews are worked by different agents at once`() {
        val first = service.activeReviewId
        val second = service.createReview("second", "").id
        service.rememberConversation(first, conversation("opencode", "ses-1"))
        service.rememberConversation(second, conversation("claude-code", "ses-2"))

        val loaded = reload(service)
        assertEquals(conversation("opencode", "ses-1"), loaded.review(first)?.conversation)
        assertEquals(conversation("claude-code", "ses-2"), loaded.review(second)?.conversation)
    }

    @Test
    fun `a backend chosen before any session of it survives a reload on its own`() {
        val id = service.activeReviewId
        service.rememberConversation(id, conversation("claude-code"))
        val restored = reload(service).review(id)?.conversation
        assertEquals(BackendId("claude-code"), restored?.backendId)
        assertNull(restored?.sessionId)
    }

    @Test
    fun `a review can be let go of its agent again`() {
        val id = service.activeReviewId
        service.rememberConversation(id, conversation("opencode", "ses-1"))
        service.rememberConversation(id, null)
        assertNull(reload(service).review(id)?.conversation)
    }

    @Test
    fun `the commits a review is of survive a reload`() {
        val id = service.activeReviewId
        service.rememberSelection(id, listOf(Revision("abc12345"), Revision("def67890")))
        assertEquals(listOf(Revision("abc12345"), Revision("def67890")), reload(service).review(id)?.selection)
    }

    @Test
    fun `a review with nothing selected reloads with nothing selected`() {
        val id = service.activeReviewId
        assertEquals(emptyList<Revision>(), reload(service).review(id)?.selection)
    }

    @Test
    fun `an observation lands in the review named, not the one active`() {
        service.addThread(thread(id = ThreadId("a"), filePath = "A.kt", sentAt = Instant.ofEpochMilli(1)))
        val first = service.activeReviewId
        val second = service.createReview("second", "")
        service.addThread(thread(id = ThreadId("b"), filePath = "B.kt", sentAt = Instant.ofEpochMilli(1)))

        service.recordObservation(first, ThreadId("a"), true)

        assertEquals(true, service.review(first)?.threads?.single()?.codeChanged)
        assertNull(service.review(second.id)?.threads?.single()?.codeChanged)
    }
}
