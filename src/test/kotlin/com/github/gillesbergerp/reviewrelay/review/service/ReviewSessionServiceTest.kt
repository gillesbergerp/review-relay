package com.github.gillesbergerp.reviewrelay.review.service

import com.github.gillesbergerp.reviewrelay.review.model.LineRange
import com.github.gillesbergerp.reviewrelay.review.model.Snippet
import com.github.gillesbergerp.reviewrelay.review.model.*
import org.junit.Assert.*
import org.junit.Before
import java.time.Instant
import org.junit.Test

class ReviewSessionServiceTest {

    private lateinit var service: ReviewSessionService

    @Before
    fun setup() {
        service = ReviewSessionService(null)
    }

    private fun saved(vararg threads: ReviewSessionService.ThreadState) = ReviewSessionService.State().apply {
        reviews.add(ReviewSessionService.ReviewState().apply { id = "r1"; this.threads = threads.toMutableList() })
        activeId = "r1"
    }

    @Test
    fun `an unsent reply can be discarded without the thread`() {
        val comment = thread(id = ThreadId("c1"), text = "Original")
        service.addThread(comment)
        service.markSent(mapOf(ThreadId("c1") to Snippet("code")))
        val reply = ReviewMessage(author = MessageAuthor.REVIEWER, text = "Follow-up")
        service.addMessage(ThreadId("c1"), reply)
        assertEquals(ThreadStatus.PENDING, service.thread(ThreadId("c1"))!!.status)

        service.removeMessage(ThreadId("c1"), reply.id)

        val left = service.thread(ThreadId("c1"))!!
        assertEquals(1, left.messages.size)
        assertEquals("Original", left.text)
        assertEquals(ThreadStatus.SENT, left.status)
    }

    @Test
    fun `a session keeps its briefing across a restart, and another session does not inherit it`() {
        val review = service.activeReviewId
        val first = SessionId("ses_one")
        assertFalse(service.briefed(review, first))

        service.markBriefed(review, first)
        assertTrue(service.briefed(review, first))
        assertFalse(service.briefed(review, SessionId("ses_two")))
        // Nothing to brief, so nothing is remembered as briefed.
        assertFalse(service.briefed(review, null))

        val restarted = ReviewSessionService(null).also { it.loadState(service.state) }
        assertTrue(restarted.briefed(review, first))
    }

    @Test
    fun `addComment adds to session`() {
        val comment = thread(text = "Test")
        service.addThread(comment)
        assertEquals(1, service.currentSession.threads.size)
        assertEquals("Test", service.currentSession.threads[0].text)
    }

    @Test
    fun `removeComment removes by id`() {
        val comment1 = thread(id = ThreadId("c1"), text = "First")
        val comment2 = thread(id = ThreadId("c2"), text = "Second")
        service.addThread(comment1)
        service.addThread(comment2)

        service.removeThread(ThreadId("c1"))
        assertEquals(1, service.currentSession.threads.size)
        assertEquals("c2", service.currentSession.threads[0].id.value)
    }

    @Test
    fun `removeComment with unknown id does nothing`() {
        val comment = thread(id = ThreadId("c1"), text = "First")
        service.addThread(comment)
        service.removeThread(ThreadId("nonexistent"))
        assertEquals(1, service.currentSession.threads.size)
    }

    @Test
    fun `editing the opening message rewrites it in place`() {
        val comment = thread(id = ThreadId("c1"), type = CommentType.CONSIDER, text = "Original")
        service.addThread(comment)

        service.setType(ThreadId("c1"), CommentType.FIX)
        service.editMessage(ThreadId("c1"), comment.opening!!.id, "Updated", null)

        val updated = service.currentSession.threads[0]
        assertEquals(CommentType.FIX, updated.type)
        assertEquals("Updated", updated.text)
        assertEquals(1, updated.messages.size)
    }

    @Test
    fun `clearSession resets everything`() {
        service.addThread(thread(text = "Test"))
        service.updateSummary("Only error handling")

        service.clearSession()

        assertEquals(0, service.currentSession.threads.size)
        assertEquals("", service.currentSession.summary)
    }

    @Test
    fun `serialization round trip preserves data`() {
        val comment = thread(
            id = ThreadId("c1"),
            type = CommentType.CONSIDER,
            text = "Add tests",
            filePath = "src/auth.go",
            lineStart = 42,
            lineEnd = 50,
        )
        service.addThread(comment)

        val state = service.state

        // Create a new service and load the state
        val newService = ReviewSessionService(null)
        newService.loadState(state)

        val restored = newService.currentSession.threads[0]
        assertEquals("c1", restored.id.value)
        assertTrue(restored.target is CommentTarget.Line)
        assertEquals(CommentType.CONSIDER, restored.type)
        assertEquals("Add tests", restored.text)
        assertEquals(ReviewedFile("src/auth.go"), restored.file)
        assertEquals(LineRange(42, 50), restored.lines)
    }

    @Test
    fun `serialization round trip with multiple comments`() {
        service.addThread(thread(id = ThreadId("c1"), text = "Overall good", filePath = "a.go", lineStart = 1))
        service.addThread(thread(id = ThreadId("c2"), text = "Complex file", filePath = "handler.go"))
        service.addThread(thread(id = ThreadId("c3"), text = "Bug", filePath = "main.go", lineStart = 10))

        val state = service.state
        val newService = ReviewSessionService(null)
        newService.loadState(state)

        assertEquals(3, newService.currentSession.threads.size)
        assertEquals("c1", newService.currentSession.threads[0].id.value)
        assertEquals("c2", newService.currentSession.threads[1].id.value)
        assertEquals("c3", newService.currentSession.threads[2].id.value)
    }

    @Test
    fun `serialization with null optional fields`() {
        val comment = thread(id = ThreadId("c1"), text = "Review note")
        service.addThread(comment)

        val state = service.state
        val newService = ReviewSessionService(null)
        newService.loadState(state)

        val restored = newService.currentSession.threads[0]
        assertTrue(restored.target is CommentTarget.File)
        assertNull(restored.lines)
    }

    @Test
    fun `pendingThreads excludes sent and review scoped comments`() {
        service.addThread(thread(id = ThreadId("a"), text = "A", filePath = "b.kt", lineStart = 1))
        service.addThread(thread(id = ThreadId("b"), text = "B", filePath = "a.kt", lineStart = 9))

        assertEquals(listOf("b", "a"), service.pendingThreads.map { it.id.value })

        service.markSent(mapOf(ThreadId("b") to Snippet("old text")))
        assertEquals(listOf("a"), service.pendingThreads.map { it.id.value })
        assertEquals(ThreadStatus.SENT, service.currentSession.threads.first { it.id.value == "b" }.status)
    }

    @Test
    fun `clearing closed comments keeps the open ones`() {
        service.addThread(thread(id = ThreadId("a"), text = "A", filePath = "a.kt", lineStart = 1))
        service.addThread(thread(id = ThreadId("b"), text = "B", filePath = "b.kt", lineStart = 1))
        service.addThread(thread(id = ThreadId("c"), text = "C", filePath = "c.kt", lineStart = 1))
        service.markSent(mapOf(ThreadId("a") to Snippet("old text"), ThreadId("c") to Snippet("old text")))
        service.resolve(ThreadId("a"))
        service.wontFix(ThreadId("c"))

        service.removeClosedThreads()

        assertEquals(listOf("b"), service.currentSession.threads.map { it.id.value })
    }

    @Test
    fun `only pending comments are queued to send`() {
        service.addThread(thread(id = ThreadId("a"), text = "A", filePath = "a.kt", lineStart = 1))
        service.addThread(thread(id = ThreadId("b"), text = "B", filePath = "b.kt", lineStart = 1))
        service.markSent(mapOf(ThreadId("a") to Snippet("base")))

        assertEquals(listOf(ThreadStatus.SENT, ThreadStatus.PENDING), listOf(ThreadId("a"), ThreadId("b")).map { id ->
            service.currentSession.threads.first { it.id == id }.status
        })
        assertEquals(listOf("b"), service.pendingThreads.map { it.id.value })

        service.resolve(ThreadId("a"))
        assertEquals(listOf("b"), service.pendingThreads.map { it.id.value })
    }

    /**
     * Closing a comment nobody has been given used to drop its only message to make the outcome
     * stick, leaving a card with no words on it. Delete is the gesture for one still being written.
     */
    @Test
    fun `a comment still being written is not closed at all`() {
        service.addThread(thread(id = ThreadId("a"), text = "A", filePath = "a.kt", lineStart = 1))

        service.wontFix(ThreadId("a"))

        val comment = service.currentSession.threads.first()
        assertEquals(ThreadStatus.PENDING, comment.status)
        assertEquals("A", comment.text)
        assertEquals(listOf("a"), service.pendingThreads.map { it.id.value })
    }

    @Test
    fun `closing a sent comment keeps what it says`() {
        service.addThread(thread(id = ThreadId("a"), text = "Sent", filePath = "a.kt", lineStart = 1))
        service.markSent(mapOf(ThreadId("a") to Snippet("code")))

        service.wontFix(ThreadId("a"))

        val comment = service.thread(ThreadId("a"))!!
        assertEquals(ThreadStatus.WONT_FIX, comment.status)
        assertTrue(!comment.status.open)
        assertEquals("Sent", comment.text)
        assertTrue(service.pendingThreads.isEmpty())
    }


    @Test
    fun `updateLines re-anchors a comment after the file moved underneath it`() {
        service.addThread(thread(id = ThreadId("a"), text = "A", filePath = "a.kt", lineStart = 10, lineEnd = 12))

        service.updateLines(ThreadId("a"), LineRange(18, 20))

        val comment = service.currentSession.threads.first()
        assertEquals(LineRange(18, 20), comment.lines)
    }

    @Test
    fun `a proposal that moved is filed against where it ended up`() {
        val id = ProposalId("p")
        service.addProposal(service.activeReviewId, proposal(id = id, text = "This leaks", lineStart = 10, lineEnd = 12))

        service.updateLines(id, LineRange(18, 20))
        val threadId = service.fileProposal(id)

        assertEquals(LineRange(18, 20), service.thread(threadId!!)?.lines)
    }

    @Test
    fun `sent state survives a serialization round trip`() {
        service.addThread(thread(id = ThreadId("a"), text = "A", filePath = "a.kt", lineStart = 1))
        service.markSent(mapOf(ThreadId("a") to Snippet("old text")))

        val restored = ReviewSessionService(null).apply { loadState(service.state) }

        assertEquals(ThreadStatus.SENT, restored.currentSession.threads.first().status)
    }


    @Test
    fun `sending records the lines to compare against later`() {
        service.addThread(thread(id = ThreadId("a"), text = "A", filePath = "a.kt", lineStart = 1))

        service.markSent(mapOf(ThreadId("a") to Snippet("val x = 1")))

        val comment = service.currentSession.threads.first()
        assertEquals(ThreadStatus.SENT, comment.status)
        assertEquals(Snippet("val x = 1"), comment.sentBase)
        assertNull(comment.codeChanged)
    }

    @Test
    fun `an observation records whether the lines moved`() {
        service.addThread(thread(id = ThreadId("a"), text = "A", filePath = "a.kt", lineStart = 1))
        service.markSent(mapOf(ThreadId("a") to Snippet("val x = 1")))

        service.recordObservation(ThreadId("a"), true)

        assertEquals(true, service.currentSession.threads.first().codeChanged)
    }

    @Test
    fun `a reply puts a closed thread back in the queue`() {
        service.addThread(thread(id = ThreadId("a"), text = "A", filePath = "a.kt", lineStart = 1))
        service.markSent(mapOf(ThreadId("a") to Snippet("val x = 1")))
        service.recordObservation(ThreadId("a"), false)

        service.addMessage(ThreadId("a"), ReviewMessage(author = MessageAuthor.REVIEWER, text = "still wrong"))

        val comment = service.currentSession.threads.first()
        assertEquals(ThreadStatus.PENDING, comment.status)
        assertEquals(listOf("a"), service.pendingThreads.map { it.id.value })
    }

    @Test
    fun `the observation survives a serialization round trip`() {
        service.addThread(thread(id = ThreadId("a"), text = "A", filePath = "a.kt", lineStart = 1))
        service.markSent(mapOf(ThreadId("a") to Snippet("val x = 1")))
        service.recordObservation(ThreadId("a"), true)

        val restored = ReviewSessionService(null).apply { loadState(service.state) }.currentSession.threads.first()

        assertEquals(Snippet("val x = 1"), restored.sentBase)
        assertEquals(true, restored.codeChanged)
    }

    @Test
    fun `reading a thread sticks, so a reply is only new once`() {
        service.addThread(thread(id = ThreadId("a"), text = "A"))
        service.markSent(mapOf(ThreadId("a") to Snippet("val x = 1")))
        service.addMessage(ThreadId("a"), ReviewMessage(author = MessageAuthor.AGENT, text = "done"))
        assertTrue(service.currentSession.threads.first().unread)

        service.markRead(ThreadId("a"))
        val restored = ReviewSessionService(null).apply { loadState(service.state) }.currentSession.threads.first()
        assertFalse(restored.unread)
    }

    @Test
    fun `an unreadable stored value falls back instead of failing the whole load`() {
        val state = saved(
            ReviewSessionService.ThreadState().apply {
                id = "a"
                type = "NONSENSE"
                outcome = "NONSENSE"
                filePath = "a.kt"
                lineStart = 1
                messages = mutableListOf(
                    ReviewSessionService.MessageState().apply {
                        id = "m"
                        author = "NONSENSE"
                        text = "A"
                    },
                )
            },
        )

        val restored = ReviewSessionService(null).apply { loadState(state) }.currentSession.threads.single()

        assertTrue(restored.target is CommentTarget.Line)
        assertEquals(CommentType.FIX, restored.type)
        assertEquals(MessageAuthor.REVIEWER, restored.messages.single().author)
        assertEquals(ThreadStatus.PENDING, restored.status)
    }

    /** The model has no room for a comment about nothing, so one stored that way is not restored. */
    @Test
    fun `a stored comment with no file is dropped rather than restored without one`() {
        val state = saved(
            ReviewSessionService.ThreadState().apply {
                id = "nowhere"
                messages = mutableListOf(ReviewSessionService.MessageState().apply { text = "A" })
            },
            ReviewSessionService.ThreadState().apply {
                id = "somewhere"
                filePath = "a.kt"
                lineStart = 1
                messages = mutableListOf(ReviewSessionService.MessageState().apply { text = "B" })
            },
        )

        val restored = ReviewSessionService(null).apply { loadState(state) }.currentSession.threads

        assertEquals(listOf("somewhere"), restored.map { it.id.value })
    }

    @Test
    fun `the code a comment was written against survives a reload`() {
        val code = """
            val x = 1
            val y = 2
        """.trimIndent()
        service.addThread(thread(id = ThreadId("c1"), filePath = "src/Foo.kt", lineStart = 3, reviewedCode = Snippet(code)))
        val reloaded = ReviewSessionService(null).apply { loadState(service.state) }
        assertEquals(Snippet(code), reloaded.currentSession.threads.single().reviewedCode)
    }

    @Test
    fun `a comment written before the code was kept reloads without one`() {
        service.addThread(thread(id = ThreadId("c2"), filePath = "src/Foo.kt", lineStart = 3))
        val reloaded = ReviewSessionService(null).apply { loadState(service.state) }
        assertNull(reloaded.currentSession.threads.single().reviewedCode)
    }
    @Test
    fun `times are stored as iso strings, and read back as the same instant`() {
        val service = ReviewSessionService(null)
        val written = Instant.parse("2026-09-06T08:15:30Z")
        service.addThread(thread(id = ThreadId("c1"), text = "Fix", writtenAt = written))
        service.markSent(mapOf(ThreadId("c1") to Snippet("val x = 1")))
        // readAt is only recorded for a thread with an answer to have read.
        service.addMessage(service.activeReviewId, ThreadId("c1"), ReviewMessage(author = MessageAuthor.AGENT, text = "Done"))
        service.markRead(ThreadId("c1"))

        val stored = service.state.reviews.single().threads.single()
        assertEquals("2026-09-06T08:15:30Z", stored.messages.first().writtenAt)
        assertTrue(stored.messages.first().sentAt.orEmpty().endsWith("Z"))
        assertTrue(stored.readAt.orEmpty().endsWith("Z"))
        assertTrue(service.state.reviews.single().createdAt.endsWith("Z"))

        val restored = ReviewSessionService(null).apply { loadState(service.state) }
        assertEquals(written, restored.currentSession.threads.single().messages.first().writtenAt)
    }

    @Test
    fun `a time that will not parse costs that field and not the review`() {
        val service = ReviewSessionService(null)
        service.addThread(thread(id = ThreadId("c1"), text = "Fix"))
        val state = service.state
        state.reviews.single().threads.single().messages.single().writtenAt = "not a time"
        state.reviews.single().threads.single().readAt = ""

        val restored = ReviewSessionService(null).apply { loadState(state) }
        val thread = restored.currentSession.threads.single()
        assertEquals("Fix", thread.text)
        assertEquals(Instant.EPOCH, thread.messages.single().writtenAt)
        assertNull(thread.readAt)
    }

}