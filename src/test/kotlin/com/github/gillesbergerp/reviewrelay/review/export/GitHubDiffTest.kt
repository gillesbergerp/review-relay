package com.github.gillesbergerp.reviewrelay.review.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubDiffTest {

    @Test
    fun `a hunk covers its added and unchanged lines`() {
        val patch = """
            @@ -1,3 +1,4 @@
             val a = 1
            -val b = 2
            +val b = 3
            +val c = 4
             val d = 5
        """.trimIndent()

        assertEquals(setOf(1, 2, 3, 4), GitHubDiff.commentableLines(patch))
    }

    @Test
    fun `a second hunk starts where its header says`() {
        val patch = """
            @@ -1,2 +1,2 @@
             one
             two
            @@ -40,2 +40,2 @@
             forty
            +forty one
        """.trimIndent()

        val lines = GitHubDiff.commentableLines(patch)
        assertTrue(lines.containsAll(listOf(1, 2, 40, 41)))
        assertFalse("nothing between the hunks is in the diff", lines.contains(20))
    }

    @Test
    fun `a single line hunk header has no count`() {
        assertEquals(setOf(7), GitHubDiff.commentableLines("@@ -7 +7 @@\n line"))
    }

    /** The marker git writes for a file with no trailing newline is not a line of the file. */
    @Test
    fun `no newline at end of file is not a line`() {
        val patch = "@@ -1,1 +1,1 @@\n-old\n+new\n\\ No newline at end of file"

        assertEquals(setOf(1), GitHubDiff.commentableLines(patch))
    }

    @Test
    fun `a file with no patch offers nothing`() {
        assertTrue(GitHubDiff.commentableLines(null).isEmpty())
        assertTrue(GitHubDiff.commentableLines("").isEmpty())
    }
}
