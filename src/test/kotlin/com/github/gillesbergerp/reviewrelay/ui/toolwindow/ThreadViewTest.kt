package com.github.gillesbergerp.reviewrelay.ui.toolwindow

import com.github.gillesbergerp.reviewrelay.review.model.MessageAuthor
import com.github.gillesbergerp.reviewrelay.review.model.ReviewMessage
import com.github.gillesbergerp.reviewrelay.review.model.ThreadOutcome
import com.github.gillesbergerp.reviewrelay.review.model.thread
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadViewTest {

    @Test
    fun `needs you leaves out what the agent still has`() {
        val pending = thread(text = "Fix this")
        val sent = thread(text = "Sent", sentAt = Instant.ofEpochMilli(1))
        val answered = sent.copy(
            messages = sent.messages + ReviewMessage(author = MessageAuthor.AGENT, text = "Done", writtenAt = Instant.ofEpochMilli(100)),
        )
        val resolved = thread(text = "Old", sentAt = Instant.ofEpochMilli(1), outcome = ThreadOutcome.RESOLVED)

        val all = listOf(pending, sent, answered, resolved)
        assertEquals(all, all.filter { ThreadFilter.ALL.accepts(it) })
        assertEquals(listOf(pending, sent, answered), all.filter { ThreadFilter.OPEN.accepts(it) })
        assertEquals(listOf(pending, answered), all.filter { ThreadFilter.NEEDS_YOU.accepts(it) })
    }

    @Test
    fun `open work sorts before closed, then by where it is`() {
        val closed = thread(text = "Done", sentAt = Instant.ofEpochMilli(1), outcome = ThreadOutcome.RESOLVED)
        val laterFile = thread(text = "b", filePath = "src/B.kt", lineStart = 1)
        val earlierFile = thread(text = "a", filePath = "src/A.kt", lineStart = 99)

        assertEquals(
            listOf(earlierFile, laterFile, closed),
            listOf(closed, laterFile, earlierFile).sortedWith(reviewOrder),
        )
    }

    @Test
    fun `grouping heads each run without reordering the threads`() {
        val one = thread(text = "a", filePath = "src/main/A.kt", lineStart = 1)
        val two = thread(text = "b", filePath = "src/main/B.kt", lineStart = 2)
        val elsewhere = thread(text = "c", filePath = "src/test/C.kt", lineStart = 3)

        val rows = ThreadGrouping.DIRECTORY.rows(listOf(one, two, elsewhere)) { null }

        assertEquals(
            listOf(
                ThreadRow.Group("src/main", 2),
                ThreadRow.Card(one),
                ThreadRow.Card(two),
                ThreadRow.Group("src/test", 1),
                ThreadRow.Card(elsewhere),
            ),
            rows,
        )
    }

    @Test
    fun `one directory is one heading however the path was spelled`() {
        val fromGit = thread(text = "a", filePath = "src/main/A.kt", lineStart = 1)
        val fromTheVfs = thread(text = "b", filePath = "Src/Main/B.kt", lineStart = 2)

        val rows = ThreadGrouping.DIRECTORY.rows(listOf(fromGit, fromTheVfs)) { null }

        assertEquals(
            listOf(
                ThreadRow.Group("src/main", 2),
                ThreadRow.Card(fromGit),
                ThreadRow.Card(fromTheVfs),
            ),
            rows,
        )
    }

    @Test
    fun `no grouping is the threads as they came`() {
        val threads = listOf(thread(text = "a"), thread(text = "b"))

        assertEquals(threads.map { ThreadRow.Card(it) }, ThreadGrouping.NONE.rows(threads) { null })
    }

    @Test
    fun `a file no module claims is a group rather than a thread dropped`() {
        val mine = thread(text = "a", filePath = "app/A.kt")
        val theirs = thread(text = "b", filePath = "vendor/B.kt")

        val rows = ThreadGrouping.MODULE.rows(listOf(mine, theirs)) { file ->
            "app".takeIf { file.path.startsWith("app/") }
        }

        assertEquals(
            listOf(
                ThreadRow.Group("app", 1),
                ThreadRow.Card(mine),
                ThreadRow.Group("Outside the project", 1),
                ThreadRow.Card(theirs),
            ),
            rows,
        )
    }

    @Test
    fun `a round is the reviewer writing after an answer`() {
        val opened = thread(text = "Fix this", sentAt = Instant.ofEpochMilli(1))
        val answered = opened.copy(
            messages = opened.messages +
                ReviewMessage(author = MessageAuthor.AGENT, text = "Done", writtenAt = Instant.ofEpochMilli(2)),
        )
        val askedAgain = answered.copy(
            messages = answered.messages +
                ReviewMessage(author = MessageAuthor.REVIEWER, text = "Not quite", writtenAt = Instant.ofEpochMilli(3)),
        )

        assertEquals(1, opened.rounds)
        assertEquals(1, answered.rounds)
        assertEquals(2, askedAgain.rounds)
        assertEquals(
            listOf(ThreadRow.Group("Round 2", 1), ThreadRow.Card(askedAgain)),
            ThreadGrouping.ROUND.rows(listOf(askedAgain)) { null },
        )
    }
}
