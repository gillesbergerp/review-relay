package com.github.gillesbergerp.reviewrelay.backend.mcp

import com.github.gillesbergerp.reviewrelay.review.model.CommentType
import com.github.gillesbergerp.reviewrelay.review.model.MessageAuthor
import com.github.gillesbergerp.reviewrelay.review.model.ReviewMessage
import com.github.gillesbergerp.reviewrelay.review.model.Snippet
import com.github.gillesbergerp.reviewrelay.review.model.ThreadOutcome
import com.github.gillesbergerp.reviewrelay.review.model.ThreadStatus
import com.github.gillesbergerp.reviewrelay.review.model.thread
import com.github.gillesbergerp.reviewrelay.review.ReviewText
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What an MCP client is handed, checkable without a running MCP server. */
class ReviewJsonTest {

    private fun sent(text: String = "Off by one", start: Int? = 10) = thread(
        type = CommentType.FIX,
        text = text,
        filePath = "src/Foo.kt",
        lineStart = start,
        sentAt = Instant.ofEpochMilli(1),
    )

    @Test
    fun `a comment still being typed is nobody elses business`() {
        val pending = thread(text = "half typed", filePath = "src/Foo.kt", lineStart = 3)
        assertEquals(ThreadStatus.PENDING, pending.status)
        assertTrue(ReviewJson.offered(listOf(pending), includeClosed = false).isEmpty())
    }

    @Test
    fun `a published comment is offered`() {
        assertEquals(1, ReviewJson.offered(listOf(sent()), includeClosed = false).size)
    }

    @Test
    fun `a closed comment is held back unless asked for`() {
        val closed = sent().copy(outcome = ThreadOutcome.RESOLVED)
        assertTrue(ReviewJson.offered(listOf(closed), includeClosed = false).isEmpty())
        assertEquals(1, ReviewJson.offered(listOf(closed), includeClosed = true).size)
    }

    @Test
    fun `a comment carries the handle its reply comes back on`() {
        val comment = sent()
        assertEquals(comment.id.handle, ReviewJson.describe(comment, null).id)
    }

    @Test
    fun `the agent is given a path it can read rather than an attachment`() {
        val described = ReviewJson.describe(sent(), "C:/proj")
        assertEquals("C:/proj/src/Foo.kt", described.absolutePath)
        assertEquals(10, described.lineStart)
        assertEquals(10, described.lineEnd)
    }

    @Test
    fun `a whole file comment has no line to read`() {
        val whole = thread(text = "Split this up", filePath = "src/Foo.kt", sentAt = Instant.ofEpochMilli(1))
        val described = ReviewJson.describe(whole, "C:/proj")
        assertNull(described.lineStart)
    }

    @Test
    fun `a suggestion is separated from the prose it came with`() {
        val comment = sent(text = "Use a set:\n```\nval seen = mutableSetOf<String>()\n```")
        val described = ReviewJson.describe(comment, null)
        assertEquals("Use a set:", described.text)
        assertEquals("val seen = mutableSetOf<String>()", described.suggestion)
    }

    @Test
    fun `the lines a comment was written against travel with it, commit or not`() {
        val comment = sent().copy(reviewedCode = Snippet("val x = 1"))
        assertEquals("val x = 1", ReviewJson.describe(comment, null).reviewedCode)
    }

    @Test
    fun `structured instructions name fields rather than the markers of a prose review`() {
        val instructions = ReviewText.preamble("", lines = ReviewText.Lines.STRUCTURED, fromCommits = true)

        assertTrue(instructions.contains("absolutePath"))
        assertTrue(instructions.contains("deleted true"))
        assertTrue(instructions.contains("Each comment carries an id."))
        assertTrue(!instructions.contains("[TYPE id]"))
        assertTrue(!instructions.contains("(not in the working tree)"))
        assertTrue(!instructions.contains("(in commit X)"))
    }

    @Test
    fun `an answered comment says so, and carries what was said`() {
        val answered = sent().let {
            it.copy(messages = it.messages + ReviewMessage(author = MessageAuthor.AGENT, text = "Done"))
        }
        val described = ReviewJson.describe(answered, null)
        assertTrue(described.answered)
        assertEquals(1, described.replies.size)
    }
}
