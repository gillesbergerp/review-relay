package com.github.gillesbergerp.reviewrelay.review.model

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ReviewModelTest {

    @Test
    fun `a comment that opens with a type names it, and the rest is the comment`() {
        val prefix = TypePrefix.of("fix: widen the retry window")

        assertEquals(CommentType.FIX, prefix?.type)
        assertEquals("widen the retry window", "fix: widen the retry window".removeRange(prefix!!.from, prefix.until))
    }

    @Test
    fun `the prefix is found past the blanks the box opened with`() {
        val text = "\n  consider: your call"
        val prefix = TypePrefix.of(text)

        assertEquals(CommentType.CONSIDER, prefix?.type)
        assertEquals("\n  your call", text.removeRange(prefix!!.from, prefix.until))
    }

    @Test
    fun `a prefix needs its colon, so a comment starting with the word is just a comment`() {
        assertNull(TypePrefix.of("fix the retry window"))
        assertNull(TypePrefix.of("question mark"))
    }

    @Test
    fun `the prefix is case blind`() {
        assertEquals(CommentType.QUESTION, TypePrefix.of("QUESTION: why here?")?.type)
        assertEquals(CommentType.QUESTION, TypePrefix.of("Question: why here?")?.type)
    }

    @Test
    fun `a type named later in the sentence is not a prefix`() {
        assertNull(TypePrefix.of("this needs a fix: soon"))
    }

    @Test
    fun `a comment that is nothing but a prefix leaves no text behind`() {
        val prefix = TypePrefix.of("fix:")

        assertEquals(CommentType.FIX, prefix?.type)
        assertEquals("", "fix:".removeRange(prefix!!.from, prefix.until))
    }

    @Test
    fun `the type steps round, so one key reaches all three`() {
        assertEquals(CommentType.CONSIDER, CommentType.FIX.next)
        assertEquals(CommentType.QUESTION, CommentType.CONSIDER.next)
        assertEquals(CommentType.FIX, CommentType.QUESTION.next)
    }

    @Test
    fun `a comment is about a file, so a blank path is refused rather than stored`() {
        assertThrows(IllegalArgumentException::class.java) { ReviewedFile("") }
        assertThrows(IllegalArgumentException::class.java) { ReviewedFile("   ") }
        assertNull(ReviewedFile.of(" "))
    }

    @Test
    fun `the same file spelled with either separator is one file`() {
        assertEquals(ReviewedFile("src/main/Foo.kt"), ReviewedFile("src\\main\\Foo.kt"))
        assertEquals(
            ReviewedFile("src/main/Foo.kt").hashCode(),
            ReviewedFile("src\\main\\Foo.kt").hashCode(),
        )
    }

    @Test
    fun `a file knows its own name and orders by its path`() {
        assertEquals("Foo.kt", ReviewedFile("src/main/Foo.kt").name)
        assertTrue(ReviewedFile("a/z.kt") > ReviewedFile("a/a.kt"))
    }

    @Test
    fun `ReviewThread has correct defaults`() {
        val comment = thread(text = "Test comment")
        // No lines given is a whole-file comment, which is the only other thing it could be.
        assertTrue(comment.target is CommentTarget.File)
        assertEquals(CommentType.FIX, comment.type)
        assertEquals(ThreadStatus.PENDING, comment.status)
        assertEquals("Test comment", comment.text)
        assertNull(comment.lines)
        assertTrue(comment.id.value.isNotEmpty())
    }

    @Test
    fun `ReviewThread with all fields`() {
        val comment = thread(
            id = ThreadId("test-id"),
            type = CommentType.FIX,
            text = "Bug here",
            filePath = "src/main.go",
            lineStart = 10,
            lineEnd = 15,
        )
        assertEquals("test-id", comment.id.value)
        assertTrue(comment.target is CommentTarget.Line)
        assertEquals(CommentType.FIX, comment.type)
        assertEquals("Bug here", comment.text)
        assertEquals(ReviewedFile("src/main.go"), comment.file)
        assertEquals(LineRange(10, 15), comment.lines)
    }

    @Test
    fun `ReviewSession has correct defaults`() {
        val session = ReviewSession()
        assertTrue(session.threads.isEmpty())
    }

    /**
     * A review is replaced rather than edited, so whoever is reading one keeps the whole of what
     * they started reading - which is what lets the endpoint serve a review off its own thread.
     */
    @Test
    fun `a review is a value, so what is read of it cannot change underneath`() {
        val first = thread(text = "First")
        val second = thread(text = "Second")
        val session = ReviewSession(threads = listOf(first))

        val added = session.copy(threads = session.threads + second)
        val removed = added.copy(threads = added.threads.filterNot { it.id == first.id })

        assertEquals(listOf("First"), session.threads.map { it.text })
        assertEquals(listOf("First", "Second"), added.threads.map { it.text })
        assertEquals(listOf("Second"), removed.threads.map { it.text })
    }

    @Test
    fun `an explicit suggestion fence is recognised`() {
        assertTrue(Suggestion.isPresent(suggestionBlock("val x = 1")))
    }

    @Test
    fun `a bare code fence counts as a suggestion`() {
        val text = "Do this instead:" + nl + "```" + nl + "val x = 1" + nl + "```"
        assertEquals("val x = 1", Suggestion.parse(text).code)
        assertEquals("Do this instead:", Suggestion.parse(text).prose)
    }

    @Test
    fun `a language tagged fence counts as a suggestion`() {
        val text = "```kotlin" + nl + "val x = 1" + nl + "```"
        assertEquals("val x = 1", Suggestion.parse(text).code)
    }

    @Test
    fun `an example fence is illustration, not a suggestion`() {
        val text = "Compare with:" + nl + "```example" + nl + "other()" + nl + "```"
        assertEquals(null, Suggestion.parse(text).code)
        assertTrue(!Suggestion.isPresent(text))
    }

    @Test
    fun `an example fence does not hide a later suggestion`() {
        val text = "```example" + nl + "other()" + nl + "```" + nl + "Use:" + nl + "```" + nl + "val x = 1" + nl + "```"
        assertEquals("val x = 1", Suggestion.parse(text).code)
    }

    @Test
    fun `prose after the suggestion is kept`() {
        val text = "Before:" + nl + "```" + nl + "val x = 1" + nl + "```" + nl + "After."
        val parsed = Suggestion.parse(text)
        assertEquals("val x = 1", parsed.code)
        assertEquals("Before:" + nl + "After.", parsed.prose)
    }

    @Test
    fun `a comment with no fence is all prose`() {
        val parsed = Suggestion.parse("Just a remark about `naming`")
        assertEquals(null, parsed.code)
        assertEquals("Just a remark about `naming`", parsed.prose)
    }

    @Test
    fun `an unterminated fence still yields its code`() {
        assertEquals("val x = 1", Suggestion.parse("```" + nl + "val x = 1").code)
    }

    @Test
    fun `whatever fence was typed is normalised for the agent`() {
        val typed = "Use:" + nl + "```kotlin" + nl + "val x = 1" + nl + "```"
        assertTrue(Suggestion.normalized(typed).lines().contains("```suggestion"))
        assertTrue(!Suggestion.normalized(typed).lines().contains("```kotlin"))
    }

    @Test
    fun `normalising leaves an example fence alone`() {
        val text = "```example" + nl + "other()" + nl + "```"
        assertEquals(text, Suggestion.normalized(text))
    }

    @Test
    fun `a suggestion block keeps the code verbatim between the fences`() {
        val code = "fun f() {" + nl + "    return 1" + nl + "}"
        val lines = suggestionBlock(code).lines()
        assertEquals("```suggestion", lines.first())
        assertEquals("```", lines.last())
        assertEquals(code.lines(), lines.subList(1, lines.size - 1))
    }

    private val nl = "\n"

    @Test
    fun `a thread with an unsent reviewer message is pending`() {
        assertEquals(ThreadStatus.PENDING, thread(text = "Fix this").status)
    }

    @Test
    fun `a thread whose messages are all sent is awaiting the agent`() {
        assertEquals(ThreadStatus.SENT, thread(text = "Fix this", sentAt = Instant.ofEpochMilli(1)).status)
    }

    @Test
    fun `a thread the agent answered last is waiting on the reviewer`() {
        val answered = thread(text = "Fix this", sentAt = Instant.ofEpochMilli(1)).let {
            it.copy(messages = it.messages + ReviewMessage(author = MessageAuthor.AGENT, text = "Done"))
        }
        assertEquals(ThreadStatus.ANSWERED, answered.status)
    }

    @Test
    fun `a reply outranks a closed outcome, which is what reopens a thread`() {
        val closed = thread(text = "Fix this", sentAt = Instant.ofEpochMilli(1), outcome = ThreadOutcome.RESOLVED)
        assertEquals(ThreadStatus.RESOLVED, closed.status)

        val replied = closed.copy(
            messages = closed.messages + ReviewMessage(author = MessageAuthor.REVIEWER, text = "Not quite"),
        )
        assertEquals(ThreadStatus.PENDING, replied.status)
        assertEquals(listOf("Not quite"), replied.unsent.map { it.text })
    }

    @Test
    fun `only the unsent reviewer messages go out in the next round`() {
        val t = thread(text = "First", sentAt = Instant.ofEpochMilli(1)).let {
            it.copy(
                messages = it.messages +
                    ReviewMessage(author = MessageAuthor.AGENT, text = "Did it") +
                    ReviewMessage(author = MessageAuthor.REVIEWER, text = "Second"),
            )
        }
        assertEquals(listOf("Second"), t.unsent.map { it.text })
    }

    @Test
    fun `an answer is unread until the thread is read, and a later one is unread again`() {
        val sent = thread(text = "Fix this", sentAt = Instant.ofEpochMilli(1))
        assertFalse(sent.unread)

        val answered = sent.copy(
            messages = sent.messages + ReviewMessage(author = MessageAuthor.AGENT, text = "Done", writtenAt = Instant.ofEpochMilli(100)),
        )
        assertTrue(answered.unread)
        assertFalse(answered.copy(readAt = Instant.ofEpochMilli(200)).unread)

        val again = answered.copy(
            readAt = Instant.ofEpochMilli(200),
            messages = answered.messages + ReviewMessage(author = MessageAuthor.AGENT, text = "And this", writtenAt = Instant.ofEpochMilli(300)),
        )
        assertTrue(again.unread)
    }

    @Test
    fun `an answer with no recorded time reads as seen`() {
        val sent = thread(text = "Fix this", sentAt = Instant.ofEpochMilli(1))
        val answered = sent.copy(
            messages = sent.messages + ReviewMessage(author = MessageAuthor.AGENT, text = "Done", writtenAt = Instant.ofEpochMilli(0)),
        )
        assertFalse(answered.unread)
    }

    @Test
    fun `the opening message is the comment the thread started from`() {
        val t = thread(text = "First", sentAt = Instant.ofEpochMilli(1)).let {
            it.copy(messages = it.messages + ReviewMessage(author = MessageAuthor.AGENT, text = "Did it"))
        }
        assertEquals("First", t.text)
    }
}
