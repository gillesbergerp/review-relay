package com.github.gillesbergerp.reviewrelay.ui.toolwindow

import com.github.gillesbergerp.reviewrelay.review.model.proposal
import com.github.gillesbergerp.reviewrelay.review.model.thread
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a proposal sits in the list, which is under everything the review is actually carrying. */
class ProposalViewTest {

    @Test
    fun `proposals sort under the comments`() {
        val pending = thread(text = "Fix this", filePath = "src/Z.kt", lineStart = 90)
        val proposed = proposal(text = "Consider this", filePath = "src/A.kt", lineStart = 1)

        assertEquals(listOf(pending, proposed), listOf(proposed, pending).sortedWith(reviewOrder))
    }

    @Test
    fun `every filter but one shows a proposal, and that one shows nothing else`() {
        val comment = thread(text = "Sent", sentAt = Instant.ofEpochMilli(1))
        val proposed = proposal(text = "Consider this")
        val both = listOf(comment, proposed)

        assertEquals(both, both.filter { ThreadFilter.ALL.accepts(it) })
        assertEquals(both, both.filter { ThreadFilter.OPEN.accepts(it) })
        assertEquals(listOf(proposed), both.filter { ThreadFilter.NEEDS_YOU.accepts(it) })
        assertEquals(listOf(proposed), both.filter { ThreadFilter.PROPOSALS.accepts(it) })
    }

    @Test
    fun `a proposal groups by where it is, like any other card`() {
        val comment = thread(text = "a", filePath = "src/main/A.kt", lineStart = 1)
        val proposed = proposal(text = "b", filePath = "src/main/B.kt", lineStart = 2)

        val rows = ThreadGrouping.DIRECTORY.rows(listOf(comment, proposed)) { null }

        assertEquals(
            listOf(ThreadRow.Group("src/main", 2), ThreadRow.Card(comment), ThreadRow.Proposed(proposed)),
            rows,
        )
    }

    @Test
    fun `standing and round both read as proposed, since it has neither`() {
        val proposed = proposal(text = "b", filePath = "src/B.kt", lineStart = 2)

        assertEquals(
            listOf(ThreadRow.Group("Proposed", 1), ThreadRow.Proposed(proposed)),
            ThreadGrouping.STATUS.rows(listOf(proposed)) { null },
        )
        assertEquals(
            listOf(ThreadRow.Group("Proposed", 1), ThreadRow.Proposed(proposed)),
            ThreadGrouping.ROUND.rows(listOf(proposed)) { null },
        )
    }
}
