package com.github.gillesbergerp.reviewrelay.review.service

import com.github.gillesbergerp.reviewrelay.review.model.CommentTarget
import com.github.gillesbergerp.reviewrelay.review.model.CommentType
import com.github.gillesbergerp.reviewrelay.review.model.MessageAuthor
import com.github.gillesbergerp.reviewrelay.review.model.Origin
import com.github.gillesbergerp.reviewrelay.review.model.ProposalStanding
import com.github.gillesbergerp.reviewrelay.review.model.ReviewMessage
import com.github.gillesbergerp.reviewrelay.review.model.Revision
import com.github.gillesbergerp.reviewrelay.review.model.Snippet
import com.github.gillesbergerp.reviewrelay.review.model.ThreadId
import com.github.gillesbergerp.reviewrelay.review.model.ThreadStatus
import com.github.gillesbergerp.reviewrelay.review.model.thread
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.SkipDefaultsSerializationFilter
import com.intellij.util.xmlb.XmlSerializer
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The workspace file itself, which the round-trip tests never touch: they hand [ReviewSessionService]
 * the bean it just built, so neither the element names nor the fields left out of the file are theirs
 * to check. Both are the format, and a rename or a changed default moves them silently.
 */
class ReviewStateXmlTest {

    private fun stored(): ReviewSessionService.State {
        val xml = checkNotNull(javaClass.getResource("/workspace/review-relay.xml")) { "missing fixture" }
        return XmlSerializer.deserialize(JDOMUtil.load(xml.readText()), ReviewSessionService.State::class.java)
    }

    private fun loaded() = ReviewSessionService(null).apply { loadState(stored()) }

    @Test
    fun `a stored file loads as the reviews it names`() {
        val service = loaded()

        assertEquals(listOf("Review"), service.openReviews.map { it.name })
        assertEquals(listOf("Review2"), service.closedReviews.map { it.name })
        assertEquals("71bbd039-c955-4367-b312-54055e9e8440", service.activeReviewId.value)
        assertEquals(service.activeReviewId, service.publishedReviewId)
    }

    @Test
    fun `a review keeps what it was of and the agent working it`() {
        val review = loaded().currentSession

        assertEquals("in e8470d84", review.startedAgainst)
        assertEquals("Look at the locking", review.summary)
        assertEquals(Instant.parse("2026-09-06T17:53:20.049812Z"), review.createdAt)
        assertEquals(
            listOf(Revision("e8470d8402a9fdbc9bdf3f04462d586f3f3e5fab"), Revision("92c5653ffee1")),
            review.selection,
        )
        assertEquals("opencode", review.conversation?.backendId?.value)
        assertEquals("ses_f8f48146effeiOPULsYi0SRKi6", review.conversation?.sessionId?.value)
        assertEquals("ses_f8f48146effeiOPULsYi0SRKi6", review.briefedSession?.value)
    }

    @Test
    fun `a thread keeps its lines, its code and everything said on it`() {
        val comment = loaded().thread(ThreadId("f9586286-1d5c-4826-8e3c-d070f6f27c56"))!!

        assertEquals(CommentType.CONSIDER, comment.type)
        assertEquals("src/main/kotlin/Backend.kt", comment.file.path)
        assertEquals(70, comment.lines?.start)
        assertEquals(72, comment.lines?.end)
        assertEquals(Snippet("private val runningLock = Any()"), comment.reviewedCode)
        assertEquals(Snippet("private val runningLock = Any()"), comment.sentBase)
        assertEquals(true, comment.codeChanged)
        assertEquals(Instant.parse("2026-09-06T18:02:00Z"), comment.readAt)
        assertEquals(listOf(MessageAuthor.REVIEWER, MessageAuthor.AGENT), comment.messages.map { it.author })
        assertEquals(ThreadStatus.ANSWERED, comment.status)
        assertFalse("the reply is older than the read", comment.unread)
        assertTrue(comment.deliveredTo("github", "owner/repo#7"))
    }

    /** An absent element is the default, so what a thread does not say is as load-bearing as what it does. */
    @Test
    fun `a thread that names no lines and no author is a whole-file comment of the reviewer's`() {
        val comment = loaded().thread(ThreadId("10a83c7c-b099-4de8-85ed-3d15fa46403a"))!!

        assertTrue(comment.target is CommentTarget.File)
        assertEquals(CommentType.FIX, comment.type)
        assertEquals(MessageAuthor.REVIEWER, comment.messages.single().author)
        assertEquals(ThreadStatus.RESOLVED, comment.status)
    }

    @Test
    fun `proposals keep who made them and what became of them`() {
        val review = loaded().currentSession

        val open = review.proposals.first { it.id.value.startsWith("2a1f0c3e") }
        assertEquals(Origin.Agent("claude"), open.origin)
        assertEquals(CommentType.QUESTION, open.type)
        assertEquals(18, open.lines?.start)
        assertEquals(ProposalStanding.Open, open.standing)

        val dismissed = review.proposals.first { it.id.value.startsWith("3b2e1d4f") }
        assertEquals(Origin.Reviewer, dismissed.origin)
        assertEquals(ProposalStanding.Dismissed, dismissed.standing)
    }

    @Test
    fun `what is written back is what was read`() {
        val again = ReviewSessionService(null).apply { loadState(loaded().state) }.currentSession

        assertEquals("Look at the locking", again.summary)
        assertEquals(2, again.threads.size)
        assertEquals(2, again.proposals.size)
        assertEquals(listOf("github"), again.threads.first().deliveries.map { it.destination })
    }

    @Test
    fun `every stored element is named by this plugin rather than by a class`() {
        val service = ReviewSessionService(null)
        service.addThread(thread(id = ThreadId("c1"), text = "Fix this", filePath = "a.kt", lineStart = 1))
        service.markSent(mapOf(ThreadId("c1") to Snippet("val x = 1")))
        service.addMessage(ThreadId("c1"), ReviewMessage(author = MessageAuthor.AGENT, text = "Done"))

        val written = JDOMUtil.write(XmlSerializer.serialize(service.state, SkipDefaultsSerializationFilter()))

        listOf("<reviews>", "<review>", "<threads>", "<thread>", "<messages>", "<message>").forEach {
            assertTrue("$it is missing from\n$written", written.contains(it))
        }
        listOf("ReviewState", "ThreadState", "MessageState", "DeliveryState", "ProposalState").forEach {
            assertFalse("$it is the format in\n$written", written.contains(it))
        }
    }

    /** A default is written as nothing, so changing one re-reads every file rather than one field. */
    @Test
    fun `a value equal to its default is left out of the file`() {
        val service = ReviewSessionService(null)
        service.addThread(thread(id = ThreadId("c1"), text = "Fix this", filePath = "a.kt", lineStart = 1))

        val written = JDOMUtil.write(XmlSerializer.serialize(service.state, SkipDefaultsSerializationFilter()))

        assertFalse("FIX is the default type", written.contains("<type>"))
        assertFalse("REVIEWER is the default author", written.contains("<author>"))
        assertFalse("an open review is the default", written.contains("<open>"))
        assertFalse("nothing was proposed", written.contains("<proposal>"))
    }

    /** A single line has no end of its own, and writing one contradicted the reader's own rule. */
    @Test
    fun `only a range writes the line it ends on`() {
        val service = ReviewSessionService(null)
        service.addThread(thread(id = ThreadId("one"), filePath = "a.kt", lineStart = 4))
        service.addThread(thread(id = ThreadId("many"), filePath = "b.kt", lineStart = 4, lineEnd = 9))

        val stored = service.state.reviews.single().threads.associateBy { it.id }

        assertNull(stored.getValue("one").lineEnd)
        assertEquals(9, stored.getValue("many").lineEnd)
    }

    /** A hand-edited file can leave an id blank, and two blanks name the same comment to a lookup. */
    @Test
    fun `a comment stored without an id is given one`() {
        val state = ReviewSessionService.State().apply {
            reviews.add(
                ReviewSessionService.ReviewState().apply {
                    id = "r1"
                    threads = mutableListOf(
                        ReviewSessionService.ThreadState().apply { filePath = "a.kt"; lineStart = 1 },
                        ReviewSessionService.ThreadState().apply { filePath = "b.kt"; lineStart = 1 },
                    )
                },
            )
            activeId = "r1"
        }

        val restored = ReviewSessionService(null).apply { loadState(state) }.currentSession.threads

        assertEquals(2, restored.size)
        assertEquals(2, restored.map { it.id }.toSet().size)
        assertTrue(restored.all { it.id.value.isNotBlank() })
    }
}
