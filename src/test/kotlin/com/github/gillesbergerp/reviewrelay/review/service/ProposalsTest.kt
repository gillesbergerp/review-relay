package com.github.gillesbergerp.reviewrelay.review.service

import com.github.gillesbergerp.reviewrelay.review.model.CommentDraft
import com.github.gillesbergerp.reviewrelay.review.model.CommentType
import com.github.gillesbergerp.reviewrelay.review.model.ItemId
import com.github.gillesbergerp.reviewrelay.review.model.Origin
import com.github.gillesbergerp.reviewrelay.review.model.ProposalId
import com.github.gillesbergerp.reviewrelay.review.model.ProposalStanding
import com.github.gillesbergerp.reviewrelay.review.model.Snippet
import com.github.gillesbergerp.reviewrelay.review.model.proposal
import com.github.gillesbergerp.reviewrelay.review.model.suggestionBlock
import com.github.gillesbergerp.reviewrelay.review.model.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proposals, and the gate between one and a comment.
 *
 * The rule the rest of the plugin leans on is that nothing unfiled is ever sent, so the tests that
 * matter most here are the ones asserting a proposal is absent from what a send carries.
 */
class ProposalsTest {

    private fun service() = ReviewSessionService(null)

    @Test
    fun `a proposal is not pending work`() {
        val service = service()

        service.addProposal(service.activeReviewId, proposal(text = "This leaks", lineStart = 4))

        assertEquals(1, service.currentSession.proposed)
        assertEquals(0, service.currentSession.pending)
        assertTrue("a proposal must never reach a send", service.pendingThreads.isEmpty())
        assertTrue(service.currentSession.threads.isEmpty())
    }

    @Test
    fun `the same proposal twice is recorded once`() {
        val service = service()
        val first = proposal(text = "This leaks", lineStart = 4)
        val again = proposal(text = "This leaks", lineStart = 4)

        assertTrue(service.addProposal(service.activeReviewId, first))
        assertFalse("a second scan must not repeat itself", service.addProposal(service.activeReviewId, again))
        assertEquals(1, service.currentSession.proposals.size)
    }

    @Test
    fun `the same words spelled differently are the same proposal`() {
        val service = service()

        service.addProposal(service.activeReviewId, proposal(text = "This  leaks\na handle", lineStart = 4))

        assertFalse(service.addProposal(service.activeReviewId, proposal(text = "this leaks a handle", lineStart = 4)))
    }

    @Test
    fun `a proposal on other lines is a different one`() {
        val service = service()

        service.addProposal(service.activeReviewId, proposal(text = "This leaks", lineStart = 4))

        assertTrue(service.addProposal(service.activeReviewId, proposal(text = "This leaks", lineStart = 90)))
    }

    @Test
    fun `a dismissed proposal is not proposed again`() {
        val service = service()
        val id = ProposalId("p1")
        service.addProposal(service.activeReviewId, proposal(id = id, text = "This leaks", lineStart = 4))

        assertTrue(service.dismissProposal(id))

        assertEquals(0, service.currentSession.proposed)
        assertFalse(
            "dismissing is what a re-scan has to respect",
            service.addProposal(service.activeReviewId, proposal(text = "This leaks", lineStart = 4)),
        )
    }

    @Test
    fun `what is drawn beside the code is the comments and the undecided proposals`() {
        val service = service()
        val filed = ProposalId("filed")
        val dismissed = ProposalId("dismissed")
        service.addThread(thread(text = "Fix this", lineStart = 1))
        service.addProposal(service.activeReviewId, proposal(id = filed, text = "This leaks", lineStart = 4))
        service.addProposal(service.activeReviewId, proposal(id = dismissed, text = "Rename it", lineStart = 8))
        service.addProposal(service.activeReviewId, proposal(text = "Untidy", lineStart = 12))

        val threadId = service.fileProposal(filed)
        service.dismissProposal(dismissed)

        val items = service.currentSession.items
        assertEquals(
            "a filed proposal is here as its thread and once only, a dismissed one not at all",
            listOf<ItemId>(service.currentSession.threads[0].id, threadId!!, service.currentSession.openProposals[0].id),
            items.map { it.id },
        )
    }

    @Test
    fun `filing writes the comment in the reviewer's name, unsent`() {
        val service = service()
        val id = ProposalId("p1")
        service.addProposal(
            service.activeReviewId,
            proposal(id = id, origin = Origin.Agent("claude"), text = "This leaks", lineStart = 4, lineEnd = 6),
        )

        val threadId = service.fileProposal(id)

        val thread = service.thread(threadId!!)!!
        assertEquals("This leaks", thread.text)
        assertEquals(Origin.Agent("claude"), thread.provenance)
        assertEquals(4, thread.lines?.start)
        assertEquals(6, thread.lines?.end)
        assertTrue("a filed comment goes out with the next round", thread.isPending)
        assertEquals(1, service.pendingThreads.size)
        assertEquals(0, service.currentSession.proposed)
        assertEquals(ProposalStanding.Filed(threadId), service.proposal(id)?.standing)
    }

    @Test
    fun `filing can carry the reviewer's own words instead`() {
        val service = service()
        val id = ProposalId("p1")
        service.addProposal(service.activeReviewId, proposal(id = id, text = "This leaks", lineStart = 4))

        val threadId = service.fileProposal(id, CommentDraft(CommentType.QUESTION, "Does this leak?", null))

        val thread = service.thread(threadId!!)!!
        assertEquals("Does this leak?", thread.text)
        assertEquals(CommentType.QUESTION, thread.type)
    }

    @Test
    fun `a filed suggestion keeps the lines it replaces`() {
        val service = service()
        val id = ProposalId("p1")
        service.addProposal(
            service.activeReviewId,
            proposal(
                id = id,
                text = "Close it:\n" + suggestionBlock("stream.use { }"),
                lineStart = 4,
                reviewedCode = Snippet("val stream = open()"),
            ),
        )

        val thread = service.thread(service.fileProposal(id)!!)!!

        assertEquals(Snippet("val stream = open()"), thread.opening?.suggestionBase)
    }

    @Test
    fun `a proposal already filed cannot be filed twice`() {
        val service = service()
        val id = ProposalId("p1")
        service.addProposal(service.activeReviewId, proposal(id = id, text = "This leaks", lineStart = 4))
        service.fileProposal(id)

        assertNull(service.fileProposal(id))
        assertEquals(1, service.currentSession.threads.size)
    }

    /** The tombstone is what dedupes a re-scan, so clearing closed work must not sweep it up. */
    @Test
    fun `clearing closed comments leaves a dismissed proposal behind`() {
        val service = service()
        val id = ProposalId("p1")
        service.addProposal(service.activeReviewId, proposal(id = id, text = "This leaks", lineStart = 4))
        service.dismissProposal(id)

        service.removeClosedThreads()
        service.clearSession()

        assertEquals(ProposalStanding.Dismissed, service.proposal(id)?.standing)
    }

    @Test
    fun `proposals survive a round trip`() {
        val service = service()
        val open = ProposalId("p1")
        service.addProposal(
            service.activeReviewId,
            proposal(id = open, origin = Origin.Agent("codex"), text = "This leaks", lineStart = 4, lineEnd = 6),
        )
        val dismissed = ProposalId("p2")
        service.addProposal(service.activeReviewId, proposal(id = dismissed, text = "Rename it", lineStart = 20))
        service.dismissProposal(dismissed)

        val loaded = ReviewSessionService(null).apply { loadState(service.state) }

        assertEquals(2, loaded.currentSession.proposals.size)
        val restored = loaded.proposal(open)!!
        assertEquals(Origin.Agent("codex"), restored.origin)
        assertEquals("This leaks", restored.text)
        assertEquals(4, restored.lines?.start)
        assertEquals(6, restored.lines?.end)
        assertTrue(restored.isOpen)
        assertEquals(ProposalStanding.Dismissed, loaded.proposal(dismissed)?.standing)
    }

    @Test
    fun `a reviewer's own proposal round trips as theirs`() {
        val service = service()
        val id = ProposalId("p1")
        service.addProposal(service.activeReviewId, proposal(id = id, origin = Origin.Reviewer, text = "Park this"))

        val loaded = ReviewSessionService(null).apply { loadState(service.state) }

        assertEquals(Origin.Reviewer, loaded.proposal(id)?.origin)
    }

    /** A workspace file from before proposals existed, which must load as a review with none. */
    @Test
    fun `a review saved without proposals loads`() {
        val saved = ReviewSessionService.State().apply {
            reviews.add(ReviewSessionService.ReviewState().apply { id = "r1"; name = "Old" })
            activeId = "r1"
        }

        val loaded = ReviewSessionService(null).apply { loadState(saved) }

        assertTrue(loaded.currentSession.proposals.isEmpty())
        assertEquals("Old", loaded.currentSession.name)
    }
}
