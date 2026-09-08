package com.github.gillesbergerp.reviewrelay.backend.claude

import com.github.gillesbergerp.reviewrelay.backend.ReviewRequest
import com.github.gillesbergerp.reviewrelay.review.model.CommentType
import com.github.gillesbergerp.reviewrelay.review.model.ReviewedFile
import com.github.gillesbergerp.reviewrelay.review.model.ReviewThread
import com.github.gillesbergerp.reviewrelay.review.model.thread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeMessageTest {

    private val project = "C:/repo"

    private fun comment(path: String = "src/Foo.kt") = thread(
        type = CommentType.FIX,
        text = "Off by one",
        filePath = path,
        lineStart = 10,
        lineEnd = null,
    )

    private fun request(
        threads: List<ReviewThread>,
        summary: String = "",
        inWorkingTree: (ReviewThread) -> Boolean = { true },
    ) = ReviewRequest(project, summary, threads, inWorkingTree = inWorkingTree)

    /** The preamble names the marker so the agent knows it; only the comment line may carry it. */
    private fun headerFor(text: String, path: String): String =
        text.lines().single { it.startsWith("[") && it.contains(path) }

    @Test
    fun `a file that is there is not marked absent`() {
        val text = ClaudeMessage.text(request(listOf(comment())), project)
        val header = headerFor(text, "src/Foo.kt:10")
        assertFalse(header, header.contains("(not in the working tree)"))
    }

    @Test
    fun `a file that is not on disk is marked absent`() {
        val text = ClaudeMessage.text(request(listOf(comment()), inWorkingTree = { false }), project)
        val header = headerFor(text, "src/Foo.kt:10")
        assertTrue(header, header.contains("(not in the working tree)"))
    }

    @Test
    fun `only the missing one is marked`() {
        val here = comment("src/Here.kt")
        val gone = comment("src/Gone.kt")
        val text = ClaudeMessage.text(
            request(listOf(here, gone), inWorkingTree = { it.file == ReviewedFile("src/Here.kt") }),
            project,
        )
        val lines = text.lines()
        assertTrue(text, lines.any { it.contains("src/Here.kt") && !it.contains("(not in the working tree)") })
        assertTrue(text, lines.any { it.contains("src/Gone.kt") && it.contains("(not in the working tree)") })
    }

    @Test
    fun `the lines are asked for by path, since nothing can be attached here`() {
        val text = ClaudeMessage.text(request(listOf(comment())), project)
        assertTrue(text, text.contains("names the file and the lines"))
        assertFalse(text, text.contains("attachment"))
    }

    @Test
    fun `the summary rides along as the preamble`() {
        val text = ClaudeMessage.text(request(listOf(comment()), summary = "do as fixups"), project)
        assertTrue(text, text.contains("Reviewer summary:"))
        assertTrue(text, text.contains("do as fixups"))
    }

    @Test
    fun `the one message is indexed before the comments and closed off after them`() {
        val text = ClaudeMessage.text(request(listOf(comment("src/A.kt"), comment("src/B.kt"))), project)

        assertTrue(text, text.contains("The 2 comments, in the order they follow:"))
        assertTrue(text, text.trimEnd().endsWith("That is all 2 comment(s). Answer every one, and make no unrelated changes."))
    }

    @Test
    fun `a session working elsewhere is told where the review came from`() {
        val text = ClaudeMessage.text(request(listOf(comment())), "C:/repo-worktree")
        assertTrue(text, text.contains("C:/repo"))
    }
}
