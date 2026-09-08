package com.github.gillesbergerp.reviewrelay.backend.mcp

import com.github.gillesbergerp.reviewrelay.ui.editor.reviewedFile
import com.github.gillesbergerp.reviewrelay.util.json.Json
import com.github.gillesbergerp.reviewrelay.review.model.MessageAuthor
import com.github.gillesbergerp.reviewrelay.review.model.ReviewThread
import com.github.gillesbergerp.reviewrelay.review.model.Suggestion
import com.github.gillesbergerp.reviewrelay.review.ReviewPresentation
import com.github.gillesbergerp.reviewrelay.review.ReviewText
import com.github.gillesbergerp.reviewrelay.review.service.ReviewPublisher
import com.github.gillesbergerp.reviewrelay.review.service.ReviewSessionService
import com.intellij.openapi.project.Project

/**
 * The review as an agent reads it when it comes asking.
 *
 * Deliberately free of any MCP type: the toolset is the only class allowed to name those, so a
 * missing MCP plugin can never stop this loading.
 */
internal object ReviewJson {

    /** The published review, as one document. */
    data class Review(
        val instructions: String,
        val project: String?,
        val comments: List<Comment>,
    )

    data class Comment(
        val id: String,
        val type: String,
        val status: String,
        val file: String?,
        // No attachment mechanism as OpenCode has, so the agent gets coordinates for its own read.
        val absolutePath: String?,
        val lineStart: Int?,
        val lineEnd: Int?,
        /** Where the lines are now, which an agent that has been editing has moved. */
        val lineNow: Int? = null,
        // The lines are that commit's; reading them off the working tree gives other code.
        val revision: String?,
        /** What the lines said when the comment was written, to recognise them by. */
        val reviewedCode: String?,
        val deleted: Boolean,
        val text: String,
        val suggestion: String?,
        val replies: List<Reply>,
        val answered: Boolean,
    )

    data class Reply(val author: String, val text: String)

    fun comments(project: Project, includeClosed: Boolean): String {
        // What the agent was given, so clicking to another review does not move the ground.
        val session = ReviewSessionService.getInstance(project).publishedReview()
        val review = ReviewPresentation(
            threads = offered(session.threads, includeClosed),
            inWorkingTree = { reviewedFile(project, it.file) != null },
            projectDirectory = project.basePath,
            lineInWorkingTree = { ReviewPublisher.workingTreeLine(project, it) },
        )
        return Json.write(
            Review(
                // Everything offered here has been sent, so a suggestion in any of it still counts.
                instructions = review.preamble(
                    summary = session.summary,
                    lines = ReviewText.Lines.STRUCTURED,
                    withSuggestions = review.threads.any { thread ->
                        thread.messages.any { Suggestion.isPresent(it.text) }
                    },
                ),
                project = project.basePath,
                comments = review.threads.map {
                    describe(it, project.basePath, review::absent).copy(lineNow = review.attachAt(it))
                },
            )
        )
    }

    /** One comment by the handle it was published under, or null when the review has no such id. */
    fun comment(project: Project, handle: String): String? {
        val service = ReviewSessionService.getInstance(project)
        val (_, thread) = service.threadAnywhere(ReviewText::handle, handle) ?: return null
        val review = ReviewPresentation(
            threads = listOf(thread),
            inWorkingTree = { reviewedFile(project, it.file) != null },
            projectDirectory = project.basePath,
            lineInWorkingTree = { ReviewPublisher.workingTreeLine(project, it) },
        )
        val described = describe(thread, project.basePath, review::absent)
        return Json.write(described.copy(lineNow = review.attachAt(thread)))
    }

    /**
     * Published work only. A pending thread is still being typed and is nobody else's business;
     * publishing is what moves it to sent, which is what makes it visible here.
     */
    fun offered(threads: List<ReviewThread>, includeClosed: Boolean): List<ReviewThread> =
        threads.filter { !it.isPending && (includeClosed || it.isOpen) }

    fun describe(
        thread: ReviewThread,
        basePath: String?,
        isDeleted: (ReviewThread) -> Boolean = { false },
    ): Comment {
        val (prose, suggestion) = Suggestion.parse(thread.text)
        return Comment(
            id = ReviewText.handle(thread),
            type = thread.type.name,
            status = thread.status.name,
            file = thread.file.path,
            absolutePath = basePath?.let { thread.file.absoluteIn(it) },
            lineStart = thread.lines?.start,
            lineEnd = thread.lines?.end,
            revision = thread.revision?.hash,
            reviewedCode = thread.reviewedCode?.text,
            deleted = isDeleted(thread),
            text = prose,
            suggestion = suggestion,
            replies = thread.messages
                .filter { it.id != thread.opening?.id }
                .map { Reply(author = it.author.name.lowercase(), text = it.text) },
            answered = thread.messages.any { it.author == MessageAuthor.AGENT },
        )
    }

}
