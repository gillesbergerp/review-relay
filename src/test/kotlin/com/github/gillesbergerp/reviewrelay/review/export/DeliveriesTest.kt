package com.github.gillesbergerp.reviewrelay.review.export

import com.github.gillesbergerp.reviewrelay.review.model.ReviewSession
import com.github.gillesbergerp.reviewrelay.review.model.ThreadId
import com.github.gillesbergerp.reviewrelay.review.model.ThreadOutcome
import com.github.gillesbergerp.reviewrelay.review.model.thread
import com.github.gillesbergerp.reviewrelay.review.service.ReviewSessionService
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What stops a second post putting the same comment on the same pull request again. */
class DeliveriesTest {

    private val github = GitHubDestination()

    @Test
    fun `every open comment is offered, whichever pull request is picked`() {
        val fresh = thread(id = ThreadId("c1"), text = "Fix this")
        val sent = thread(id = ThreadId("c2"), text = "Out", sentAt = Instant.ofEpochMilli(1))
        val closed = thread(
            id = ThreadId("c3"),
            text = "Done",
            sentAt = Instant.ofEpochMilli(1),
            outcome = ThreadOutcome.RESOLVED,
        )
        val review = ReviewSession(threads = listOf(fresh, sent, closed))

        assertEquals(listOf(fresh, sent), github.selects(review))
    }

    @Test
    fun `a comment knows the pull request it went to, and only that one`() {
        val service = ReviewSessionService(null)
        service.addThread(thread(id = ThreadId("c1"), text = "Fix this"))
        service.addThread(thread(id = ThreadId("c2"), text = "And this"))

        service.recordDeliveries(service.activeReviewId, "github", mapOf(ThreadId("c1") to "owner/repo#7"))

        assertTrue(service.thread(ThreadId("c1"))!!.deliveredTo("github", "owner/repo#7"))
        assertFalse(service.thread(ThreadId("c1"))!!.deliveredTo("github", "owner/repo#8"))
        assertFalse(service.thread(ThreadId("c2"))!!.deliveredTo("github", "owner/repo#7"))
    }

    @Test
    fun `a delivery survives a round trip and is not confused with another destination`() {
        val service = ReviewSessionService(null)
        service.addThread(thread(id = ThreadId("c1"), text = "Fix this"))
        service.recordDeliveries(service.activeReviewId, "github", mapOf(ThreadId("c1") to "owner/repo#7"))

        val loaded = ReviewSessionService(null).apply { loadState(service.state) }

        val restored = loaded.thread(ThreadId("c1"))!!
        assertEquals("owner/repo#7", restored.deliveries.single().externalId)
        assertTrue(restored.deliveredTo("github", "owner/repo#7"))
        assertFalse(restored.deliveredTo("clipboard", "owner/repo#7"))
    }

    @Test
    fun `posting to a second pull request does not erase the first`() {
        val service = ReviewSessionService(null)
        service.addThread(thread(id = ThreadId("c1"), text = "Fix this"))

        service.recordDeliveries(service.activeReviewId, "github", mapOf(ThreadId("c1") to "owner/repo#7"))
        service.recordDeliveries(service.activeReviewId, "github", mapOf(ThreadId("c1") to "owner/repo#8"))

        val posted = service.thread(ThreadId("c1"))!!
        assertEquals(listOf("owner/repo#7", "owner/repo#8"), posted.deliveries.map { it.externalId })
        assertTrue(posted.deliveredTo("github", "owner/repo#7"))
        assertTrue(posted.deliveredTo("github", "owner/repo#8"))
    }

    @Test
    fun `posting again to the same pull request records it once`() {
        val service = ReviewSessionService(null)
        service.addThread(thread(id = ThreadId("c1"), text = "Fix this"))

        service.recordDeliveries(service.activeReviewId, "github", mapOf(ThreadId("c1") to "owner/repo#7"))
        service.recordDeliveries(service.activeReviewId, "github", mapOf(ThreadId("c1") to "owner/repo#7"))

        assertEquals(listOf("owner/repo#7"), service.thread(ThreadId("c1"))!!.deliveries.map { it.externalId })
    }
}
