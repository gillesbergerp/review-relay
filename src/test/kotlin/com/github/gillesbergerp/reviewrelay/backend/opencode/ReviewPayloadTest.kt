package com.github.gillesbergerp.reviewrelay.backend.opencode

import com.github.gillesbergerp.reviewrelay.review.model.CommentType
import com.github.gillesbergerp.reviewrelay.review.model.MessageAuthor
import com.github.gillesbergerp.reviewrelay.review.model.ReviewMessage
import com.github.gillesbergerp.reviewrelay.review.model.ReviewThread
import com.github.gillesbergerp.reviewrelay.review.model.Revision
import com.github.gillesbergerp.reviewrelay.review.model.Snippet
import com.github.gillesbergerp.reviewrelay.review.model.suggestionBlock
import com.github.gillesbergerp.reviewrelay.review.model.thread
import com.github.gillesbergerp.reviewrelay.review.model.Suggestion
import com.github.gillesbergerp.reviewrelay.review.ReviewText
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReviewPayloadTest {

    private val basePath = File("").absolutePath.replace(File.separatorChar, '/')
    private val present: (ReviewThread) -> Boolean = { true }
    private val absent: (ReviewThread) -> Boolean = { false }

    private fun lineComment(
        text: String = "Off by one",
        path: String = "src/Foo.kt",
        start: Int? = 10,
        end: Int? = 20,
        type: CommentType = CommentType.FIX,
        revision: Revision? = null,
    ) = thread(
        type = type,
        text = text,
        filePath = path,
        lineStart = start,
        lineEnd = end,
        revision = revision,
    )

    @Test
    fun `a range comment attaches exactly its lines`() {
        val url = ReviewPayload.fileUrl(basePath, lineComment())
        assertTrue(url.startsWith("file:/"))
        assertTrue(url.endsWith("src/Foo.kt?start=10&end=20"))
    }

    @Test
    fun `a single line comment sends start equal to end so OpenCode widens it to the symbol`() {
        val url = ReviewPayload.fileUrl(basePath, lineComment(start = 42, end = null))
        assertTrue(url.endsWith("src/Foo.kt?start=42&end=42"))
    }

    @Test
    fun `a whole file comment attaches the file without a range`() {
        val comment = thread(text = "Split this up", filePath = "src/Foo.kt")
        assertTrue(ReviewPayload.fileUrl(basePath, comment).endsWith("src/Foo.kt"))

        val parts = ReviewPayload.parts(basePath, "", listOf(comment), inWorkingTree = present)
        val attached = parts.single { it["type"] == "file" }
        assertEquals("src/Foo.kt", attached["filename"])
        assertTrue(attached["url"].toString().endsWith("src/Foo.kt"))
    }

    @Test
    fun `a review of whole file comments is not told its files are missing from the working tree`() {
        val comments = listOf(
            thread(text = "Split this up", filePath = "src/Foo.kt"),
            thread(text = "Untested", filePath = "src/Bar.kt"),
        )
        val preamble = ReviewPayload.parts(basePath, "", comments, inWorkingTree = present)
            .first()["text"]
            .toString()

        assertTrue(!preamble.contains("None of the reviewed lines are in the working tree"))
    }

    @Test
    fun `a whole file comment on a deleted file is not attached`() {
        val comment = thread(text = "Why did this go?", filePath = "src/Foo.kt")
        val parts = ReviewPayload.parts(basePath, "", listOf(comment), inWorkingTree = absent)

        assertTrue(parts.none { it["type"] == "file" })
        assertTrue(parts.any { it["text"].toString().contains("(not in the working tree)") })
    }

    @Test
    fun `parts start with the preamble and pair each attachment with its comment`() {
        val comments = listOf(
            lineComment(text = "First", path = "src/A.kt", start = 1, end = null),
            lineComment(text = "Second", path = "src/B.kt", start = 5, end = 7),
        )
        val parts = ReviewPayload.parts(basePath, "", comments, inWorkingTree = present)

        assertEquals("text", parts[0]["type"])
        assertTrue((parts[0]["text"] as String).contains("Please address each comment"))

        // Each attachment is immediately followed by the comment it was attached for.
        val attachments = parts.withIndex().filter { it.value["type"] == "file" }
        assertEquals(listOf("src/A.kt", "src/B.kt"), attachments.map { it.value["filename"] })
        assertEquals("text/plain", attachments.first().value["mime"])

        val comment = attachments.map { parts[it.index + 1]["text"] as String }
        assertTrue(comment[0].contains("src/A.kt:1"))
        assertTrue(comment[1].contains("src/B.kt:5-7"))

        // Each carries its own handle, which is how the agent addresses a reply.
        assertEquals(
            comments.map { ReviewText.handle(it) },
            comment.map { it.substringAfter("FIX ").substringBefore("]") },
        )
    }

    @Test
    fun `a review is indexed before it is read, and closed off after it`() {
        val comments = listOf(
            lineComment(text = "First", path = "src/A.kt", start = 1, end = null),
            thread(type = CommentType.QUESTION, text = "Second", filePath = "src/B.kt"),
        )
        val parts = ReviewPayload.parts(basePath, "", comments, inWorkingTree = present)

        val manifest = parts[1]["text"] as String
        assertTrue(manifest.startsWith("The 2 comments, in the order they follow:"))
        comments.forEach { assertTrue(manifest.contains(ReviewText.handle(it))) }
        assertTrue(manifest.contains("FIX      "))
        assertTrue(manifest.contains("src/B.kt (whole file)"))

        assertTrue((parts.last()["text"] as String).startsWith("That is all 2 comment(s)."))
    }

    @Test
    fun `a lone comment is not given an index of itself`() {
        val parts = ReviewPayload.parts(basePath, "", listOf(lineComment()), inWorkingTree = present)

        assertTrue(parts.none { it["text"].toString().contains("in the order they follow") })
    }

    @Test
    fun `a thread's unsent messages share the one header`() {
        val opened = lineComment(text = "First", start = 3, end = null)
        val withHistory = opened.copy(
            messages = listOf(opened.messages.single().copy(sentAt = Instant.ofEpochMilli(1))) +
                ReviewMessage(author = MessageAuthor.REVIEWER, text = "Second") +
                ReviewMessage(author = MessageAuthor.REVIEWER, text = "Third"),
        )
        val body = ReviewPayload.parts(basePath, "", listOf(withHistory), inWorkingTree = present)
            .single { it["text"].toString().contains("Second") }["text"] as String

        assertEquals(1, Regex(Regex.escape("[FIX ")).findAll(body).count())
        assertTrue(body.endsWith("Second\n\nThird"))
    }

    @Test
    fun `a comment on a deleted file is sent without an attachment, and says so`() {
        val comment = lineComment(text = "Why did this go?")
        val parts = ReviewPayload.parts(basePath, "", listOf(comment), inWorkingTree = absent)

        assertTrue(parts.none { it["type"] == "file" })
        assertTrue(
            parts.any {
                it["text"].toString().startsWith(
                    "[FIX " + ReviewText.handle(comment) + "] src/Foo.kt:10-20 (not in the working tree)"
                )
            }
        )
    }

    @Test
    fun `a session that has had the rules is not given them again`() {
        val comments = listOf(lineComment())
        val recap = ReviewPayload.parts(basePath, "", comments, inWorkingTree = present, briefed = true)
            .first()["text"]
            .toString()

        assertTrue(recap.startsWith("More review comments on the same changes."))
        assertTrue(!recap.contains("FIX means change it"))
        assertTrue(!recap.contains("attachment of the exact lines"))
        // What it still has to be told: how to answer, and to leave the reviewer's tree where it is.
        assertTrue(recap.contains("review_reply"))
        assertTrue(recap.contains("Do not check out, switch branches, stash, or reset"))
    }

    @Test
    fun `a recap still carries what only this round could have introduced`() {
        val comments = listOf(
            lineComment(
                text = "Use a set:" + "\n" + suggestionBlock("val seen = mutableSetOf<String>()"),
                revision = Revision("a1b2c3d4e5f6"),
            )
        )
        val recap = ReviewPayload
            .parts(basePath, "", comments, ReviewText.Elsewhere(basePath, true), present, briefed = true)
            .first()["text"]
            .toString()

        assertTrue(recap.contains("apply it verbatim"))
        assertTrue(recap.contains("numbers its lines in that commit"))
        assertTrue(recap.contains("leave your own checkout alone"))
    }

    @Test
    fun `a recap still carries the reviewer summary`() {
        val recap = ReviewPayload
            .parts(basePath, "Only error handling.", listOf(lineComment()), inWorkingTree = present, briefed = true)
            .first()["text"]
            .toString()

        assertTrue(recap.contains("Reviewer summary:"))
        assertTrue(recap.trimEnd().endsWith("Only error handling."))
    }

    @Test
    fun `the reviewer summary is appended to the preamble`() {
        val preamble = ReviewText.preamble("Only look at error handling.")
        assertTrue(preamble.contains("Reviewer summary:"))
        assertTrue(preamble.trimEnd().endsWith("Only look at error handling."))
    }

    @Test
    fun `an empty summary adds no summary block`() {
        assertTrue(!ReviewText.preamble("   ").contains("Reviewer summary"))
    }

    @Test
    fun `comment text carries type, a reply handle, location and body`() {
        val comment = lineComment(text = "Off by one", start = 3, end = null)
        val text = ReviewText.threadText(comment, comment.messages)
        assertEquals("[FIX " + ReviewText.handle(comment) + "] src/Foo.kt:3\nOff by one", text)
    }

    @Test
    fun `a follow-up is marked as one so the agent knows the thread has history`() {
        val opened = lineComment(text = "Off by one", start = 3, end = null)
        val withHistory = opened.copy(
            messages = listOf(opened.messages.single().copy(sentAt = Instant.ofEpochMilli(1))) +
                ReviewMessage(author = MessageAuthor.REVIEWER, text = "Still wrong"),
        )
        val text = ReviewText.threadText(withHistory, withHistory.unsent)
        assertTrue(text.startsWith("[FIX " + ReviewText.handle(withHistory) + "] src/Foo.kt:3 (follow-up)"))
        assertTrue(text.endsWith("Still wrong"))
    }

    @Test
    fun `only the unsent messages of a thread are sent`() {
        val opened = lineComment(text = "First", start = 3, end = null)
        val withHistory = opened.copy(
            messages = listOf(opened.messages.single().copy(sentAt = Instant.ofEpochMilli(1))) +
                ReviewMessage(author = MessageAuthor.AGENT, text = "Did it") +
                ReviewMessage(author = MessageAuthor.REVIEWER, text = "Second"),
        )
        val texts = ReviewPayload.parts(basePath, "", listOf(withHistory), inWorkingTree = present).mapNotNull { it["text"] as? String }

        assertTrue(texts.none { it.contains("First") })
        assertTrue(texts.none { it.contains("Did it") })
        assertTrue(texts.any { it.contains("Second") })
    }

    @Test
    fun `the agent is only sent when configured`() {
        val comments = listOf(lineComment())
        assertNull(ReviewPayload.body(basePath, "", comments, "", inWorkingTree = present)["agent"])
        assertNull(ReviewPayload.body(basePath, "", comments, null, inWorkingTree = present)["agent"])
        assertEquals("build", ReviewPayload.body(basePath, "", comments, "build", inWorkingTree = present)["agent"])
    }

    @Test
    fun `the suggestion instruction appears only when a comment carries one`() {
        val plain = listOf(lineComment(text = "Rename this"))
        assertTrue(!ReviewPayload.parts(basePath, "", plain, inWorkingTree = present).first()["text"].toString().contains("apply it verbatim"))

        val suggested = listOf(lineComment(text = "Use a set:" + "\n" + suggestionBlock("val seen = mutableSetOf<String>()")))
        val preamble = ReviewPayload.parts(basePath, "", suggested, inWorkingTree = present).first()["text"].toString()
        assertTrue(preamble.contains("apply it verbatim"))
    }

    @Test
    fun `a suggestion block survives into the comment text unchanged`() {
        val code = "val seen = mutableSetOf<String>()"
        val comment = lineComment(text = "Use a set:" + "\n" + suggestionBlock(code))
        val text = ReviewText.threadText(comment, comment.messages)
        assertTrue(text.contains(suggestionBlock(code)))
        assertTrue(Suggestion.isPresent(text))
    }

    @Test
    fun `a session running elsewhere is told to fix up where the review is`() {
        val worktree = "C:/work/worktrees/aws-fb-monitors"
        val preamble = ReviewPayload
            .parts(worktree, "", listOf(lineComment()), ReviewText.Elsewhere(worktree, true), present)
            .first()["text"]
            .toString()

        assertTrue(preamble.contains(worktree))
        assertTrue(preamble.contains("worktree of the checkout"))
        assertTrue(preamble.contains("leave your own checkout alone"))
    }

    @Test
    fun `a session elsewhere is given the absolute paths the preamble promises it`() {
        val worktree = "C:/work/worktrees/aws-fb-monitors"
        val texts = ReviewPayload
            .parts(worktree, "", listOf(lineComment()), ReviewText.Elsewhere(worktree, true), present)
            .mapNotNull { it["text"] as? String }

        assertTrue(texts.any { it.contains("$worktree/src/Foo.kt:10-20") })
    }

    @Test
    fun `a session in the reviewed directory is given the paths it already reads by`() {
        val texts = ReviewPayload
            .parts(basePath, "", listOf(lineComment()), inWorkingTree = present)
            .mapNotNull { it["text"] as? String }

        assertTrue(texts.any { it.contains("] src/Foo.kt:10-20") })
    }

    @Test
    fun `a session in an unrelated checkout is not told it is a worktree of one`() {
        val preamble = ReviewPayload
            .parts(basePath, "", listOf(lineComment()), ReviewText.Elsewhere(basePath, false), present)
            .first()["text"]
            .toString()

        assertTrue(preamble.contains("checkout separate from the one you are running in"))
        assertTrue(!preamble.contains("worktree of the checkout"))
    }

    @Test
    fun `a session in the reviewed directory gets no worktree instruction`() {
        assertNull(ReviewText.elsewhere("C:/work/aws", "C:/work/aws"))
        assertNull(ReviewText.elsewhere("C:/work/aws", null))
        assertTrue(
            !ReviewPayload.parts(basePath, "", listOf(lineComment()), inWorkingTree = present).first()["text"].toString()
                .contains("worktree of the checkout")
        )
    }

    @Test
    fun `a comment on a deleted file quotes what its lines said, having nothing to point at`() {
        val comment = lineComment(text = "Why did this go?").copy(reviewedCode = Snippet("val x = 1"))
        val parts = ReviewPayload.parts(basePath, "", listOf(comment), inWorkingTree = absent)

        val text = parts.joinToString("\n") { it["text"].toString() }
        assertTrue(text.contains("val x = 1"))
        assertTrue(text.contains("as they read when it was written"))
    }

    @Test
    fun `a comment the agent can be pointed at is not also quoted`() {
        val comment = lineComment().copy(reviewedCode = Snippet("val x = 1"))
        val parts = ReviewPayload.parts(basePath, "", listOf(comment), inWorkingTree = present)

        assertEquals(1, parts.count { it["type"] == "file" })
        assertTrue(parts.none { it["text"].toString().contains("val x = 1") })
    }

    @Test
    fun `a comment whose reviewed lines are gone sends them as text instead of the file`() {
        val comment = lineComment(path = "src/Foo.kt", revision = Revision("a1b2c3d4e5f6")).copy(reviewedCode = Snippet("val x = 1"))
        val parts = ReviewPayload
            .parts(basePath, "", listOf(comment), inWorkingTree = present, lineInWorkingTree = { null })

        assertTrue(parts.none { it["type"] == "file" })
        val text = parts.joinToString("\n") { it["text"].toString() }
        assertTrue(text.contains("val x = 1"))
        assertTrue(text.contains("a1b2c3d4"))
        assertTrue(text.contains("read them with git show"))
    }

    @Test
    fun `body carries the resolver through, so a moved comment is attached and not quoted`() {
        val comment = lineComment(path = "src/Foo.kt", start = 10, end = 20, revision = Revision("a1b2c3d4e5f6"))
            .copy(reviewedCode = Snippet("val x = 1"))
        @Suppress("UNCHECKED_CAST")
        val parts = ReviewPayload.body(
            basePath = basePath,
            summary = "",
            threads = listOf(comment),
            agent = null,
            inWorkingTree = present,
            lineInWorkingTree = { 41 },
        )["parts"] as List<Map<String, Any?>>

        assertTrue(parts.single { it["type"] == "file" }["url"].toString().endsWith("?start=41&end=51"))
    }

    @Test
    fun `a comment whose reviewed lines have moved is attached where they are now`() {
        val comment = lineComment(path = "src/Foo.kt", start = 10, end = 20, revision = Revision("a1b2c3d4e5f6"))
            .copy(reviewedCode = Snippet("val x = 1"))
        val parts = ReviewPayload
            .parts(basePath, "", listOf(comment), inWorkingTree = present, lineInWorkingTree = { 41 })

        val attached = parts.single { it["type"] == "file" }["url"].toString()
        assertTrue(attached.endsWith("src/Foo.kt?start=41&end=51"))
        val text = parts.joinToString("\n") { it["text"].toString() }
        assertTrue(text.contains("(in commit a1b2c3d4, now line 41)"))
        assertTrue(!text.contains("None of the reviewed lines are in the working tree"))
    }

    @Test
    fun `a working tree comment whose code drifted says where it was attached`() {
        val comment = lineComment(start = 10, end = 20).copy(reviewedCode = Snippet("val x = 1"))
        val parts = ReviewPayload
            .parts(basePath, "", listOf(comment), inWorkingTree = present, lineInWorkingTree = { 41 })

        assertTrue(parts.single { it["type"] == "file" }["url"].toString().endsWith("?start=41&end=51"))
        val text = parts.joinToString("\n") { it["text"].toString() }
        assertTrue(text.contains("src/Foo.kt:10-20 (now line 41)"))
    }

    @Test
    fun `a comment sitting where it was written says nothing about having moved`() {
        val parts = ReviewPayload
            .parts(basePath, "", listOf(lineComment()), inWorkingTree = present, lineInWorkingTree = { 10 })

        assertTrue(parts.none { it["text"].toString().contains("now line") })
    }

    @Test
    fun `a comment on the working tree still attaches its lines`() {
        val parts = ReviewPayload.parts(basePath, "", listOf(lineComment()), inWorkingTree = present)

        assertEquals(1, parts.count { it["type"] == "file" })
        val text = parts.joinToString("\n") { it["text"].toString() }
        assertTrue(!text.contains("None of the reviewed lines are in the working tree"))
        assertTrue(!text.contains("in commit"))
    }

    @Test
    fun `a commit comment is quoted when nothing can say where its lines are now`() {
        val comment = lineComment(revision = Revision("a1b2c3d4e5f6")).copy(reviewedCode = Snippet("val x = 1"))
        val parts = ReviewPayload.parts(basePath, "", listOf(comment), inWorkingTree = present)

        assertTrue(parts.none { it["type"] == "file" })
    }

    @Test
    fun `every review forbids moving the tree the reviewer is reading`() {
        val preamble = ReviewPayload.parts(basePath, "", listOf(lineComment()), inWorkingTree = present)
            .first()["text"]
            .toString()

        assertTrue(preamble.contains("Do not check out, switch branches, stash, or reset"))
    }

    @Test
    fun `git show is given the directory its paths are relative to`() {
        val preamble = ReviewText.preamble(
            "",
            fromCommits = true,
            repositoryRoot = "C:/work/aws",
        )

        assertTrue(preamble.contains("git show X:<path> run in C:/work/aws"))
        assertTrue(preamble.contains("<path> is relative to that directory"))
    }

    @Test
    fun `git show is described without a root when there is no repository to name`() {
        val preamble = ReviewText.preamble("", fromCommits = true)

        assertTrue(preamble.contains("git show X:<path>, and do not trust"))
    }

    @Test
    fun `a commit comment is marked as one rather than as missing from the working tree`() {
        val comment = lineComment(revision = Revision("a1b2c3d4e5f6"))
        val text = ReviewText.threadText(comment, comment.messages, absent = true)

        assertTrue(text.contains("(in commit a1b2c3d4)"))
        assertTrue(!text.contains("not in the working tree"))
    }

    @Test
    fun `a review whose lines are gone is told to fix the file where the point still holds`() {
        val comment = lineComment(revision = Revision("a1b2c3d4e5f6"))
        val preamble = ReviewPayload
            .parts(basePath, "", listOf(comment), inWorkingTree = { false })
            .first()["text"]
            .toString()

        assertTrue(preamble.contains("read them with git show"))
        assertTrue(preamble.contains("make the change there and say the lines have moved"))
        assertTrue(preamble.contains("Where it no longer applies, answer without editing"))
    }

    @Test
    fun `a review with no commit behind it is pointed at the quotes rather than at git show`() {
        val preamble = ReviewPayload
            .parts(basePath, "", listOf(lineComment()), inWorkingTree = { false })
            .first()["text"]
            .toString()

        assertTrue(preamble.contains("the lines are quoted with each comment"))
        assertTrue(!preamble.contains("read them with git show"))
    }

    @Test
    fun `a session elsewhere is told which checkout to work in even when nothing is on disk`() {
        val worktree = "C:/work/worktrees/aws-fb-monitors"
        val preamble = ReviewPayload
            .parts(worktree, "", listOf(lineComment()), ReviewText.Elsewhere(worktree, true), { false })
            .first()["text"]
            .toString()

        assertTrue(preamble.contains("leave your own checkout alone"))
        assertTrue(preamble.contains("None of the reviewed lines are in the working tree"))
    }
}

