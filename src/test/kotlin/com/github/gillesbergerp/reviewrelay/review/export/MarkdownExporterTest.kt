package com.github.gillesbergerp.reviewrelay.review.export

import com.github.gillesbergerp.reviewrelay.review.model.CommentType
import com.github.gillesbergerp.reviewrelay.review.model.MessageAuthor
import com.github.gillesbergerp.reviewrelay.review.model.ReviewMessage
import com.github.gillesbergerp.reviewrelay.review.model.ThreadId
import com.github.gillesbergerp.reviewrelay.review.model.thread
import com.github.gillesbergerp.reviewrelay.review.model.ReviewSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MarkdownExporterTest {

    @Test
    fun `export empty session returns empty string`() {
        assertEquals("", MarkdownExporter.export(ReviewSession()))
    }

    @Test
    fun `export file comment`() {
        val comment = thread(type = CommentType.FIX, text = "Missing tests", filePath = "src/auth.go")
        val exported = MarkdownExporter.export(ReviewSession(threads = listOf(comment)))
        assertTrue(
            exported,
            exported.contains(
                "1. **[FIX ${comment.id.handle}]** `src/auth.go (whole file)` - Missing tests"
            ),
        )
    }

    @Test
    fun `export line comment single line`() {
        val comment = thread(
            type = CommentType.CONSIDER,
            text = "Add unit tests",
            filePath = "src/auth.go",
            lineStart = 42,
        )
        val exported = MarkdownExporter.export(ReviewSession(threads = listOf(comment)))
        assertTrue(
            exported,
            exported.contains("1. **[CONSIDER ${comment.id.handle}]** `src/auth.go:42` - Add unit tests"),
        )
    }

    @Test
    fun `export line comment range`() {
        val comment = thread(
            type = CommentType.FIX,
            text = "Race condition",
            filePath = "src/handler.go",
            lineStart = 10,
            lineEnd = 15,
        )
        val exported = MarkdownExporter.export(ReviewSession(threads = listOf(comment)))
        assertTrue(
            exported,
            exported.contains("1. **[FIX ${comment.id.handle}]** `src/handler.go:10-15` - Race condition"),
        )
    }

    @Test
    fun `a pasted review can be answered, carrying the ids and the rule for them`() {
        val comment = thread(text = "Missing tests", filePath = "src/auth.go", lineStart = 42)
        val exported = MarkdownExporter.export(ReviewSession(threads = listOf(comment)))

        assertTrue(exported, exported.contains(comment.id.handle))
        assertTrue(exported, exported.contains("review_reply"))
    }

    @Test
    fun `export sorts by scope then file then line`() {
        val session = ReviewSession(
            threads = listOf(
                thread(text = "Bug", filePath = "z.go", lineStart = 5),
                thread(text = "Refactor", filePath = "a.go"),
                thread(text = "Nice", filePath = "a.go", lineStart = 1),
            )
        )
        val lines = MarkdownExporter.export(session).lines().filter { it.matches(Regex("^\\d+\\..*")) }
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("a.go (whole file)` - Refactor"))
        assertTrue(lines[1].contains("a.go:1"))
        assertTrue(lines[2].contains("z.go:5"))
    }

    @Test
    fun `export contains header and a legend for every comment type`() {
        val session = ReviewSession(
            threads = listOf(thread(text = "Test", filePath = "a.go"))
        )
        val result = MarkdownExporter.export(session)
        assertTrue(result.contains("I reviewed your code and have the following comments."))
        CommentType.entries.forEach { assertTrue(it.name, result.contains(it.name)) }
    }

    @Test
    fun `summary replaces the default header`() {
        val comments = listOf(thread(text = "Test", filePath = "a.go"))
        val result = MarkdownExporter.export("Only the error handling, please.", comments)
        assertTrue(result.startsWith("Only the error handling, please."))
        assertTrue(!result.contains("I reviewed your code"))
    }

    /**
     * A thread is pending again the moment a follow-up is written, and its opening has already been
     * sent: the export carried that opening a second time and never the words it was pending for.
     */
    @Test
    fun `a follow-up round carries what is queued, not the comment it opened with`() {
        val answered = thread(id = ThreadId("c1"), text = "Rename this", filePath = "a.kt", lineStart = 3, sentAt = Instant.ofEpochMilli(1))
            .let { it.copy(messages = it.messages + ReviewMessage(author = MessageAuthor.AGENT, text = "Renamed")) }
            .let { it.copy(messages = it.messages + ReviewMessage(author = MessageAuthor.REVIEWER, text = "Still shadows the field")) }

        val exported = MarkdownExporter.export("", listOf(answered))

        assertTrue(exported, exported.contains("Still shadows the field"))
        assertTrue("the opening has already gone", !exported.contains("Rename this"))
    }
}
