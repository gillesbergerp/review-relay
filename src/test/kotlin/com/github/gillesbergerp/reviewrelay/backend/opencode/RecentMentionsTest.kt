package com.github.gillesbergerp.reviewrelay.backend.opencode

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Counting the handles in a session's recent messages, which is how a post is told to have landed.
 *
 * A thread's handle lasts its whole life, so merely finding one meant a follow-up round read as
 * delivered on the strength of the round before it.
 */
class RecentMentionsTest {

    private fun mentions(messages: String, needles: Collection<String>): Int =
        OpenCodeClient.mentionsIn(messages, needles)

    @Test
    fun `nothing said about a handle is no mention of it`() {
        assertEquals(0, mentions("""[{"text":"hello"}]""", listOf("aaaaaaaa")))
    }

    @Test
    fun `a handle said once counts once`() {
        assertEquals(1, mentions("""[{"text":"[FIX aaaaaaaa] do it"}]""", listOf("aaaaaaaa")))
    }

    /** The round before it put the handle there, and the agent's answer put it there again. */
    @Test
    fun `a handle already twice over needs a third to count as new`() {
        val messages = """[{"text":"[FIX aaaaaaaa] do it"},{"text":"done, aaaaaaaa"}]"""

        assertEquals(2, mentions(messages, listOf("aaaaaaaa")))
    }

    @Test
    fun `every handle of the round is counted`() {
        val messages = """[{"text":"[FIX aaaaaaaa] and [FIX bbbbbbbb]"}]"""

        assertEquals(2, mentions(messages, listOf("aaaaaaaa", "bbbbbbbb")))
    }

    @Test
    fun `no handles is nothing to count`() {
        assertEquals(0, mentions("""[{"text":"anything"}]""", emptyList()))
    }
}
