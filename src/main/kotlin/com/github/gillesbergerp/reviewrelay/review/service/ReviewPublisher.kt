package com.github.gillesbergerp.reviewrelay.review.service

import com.github.gillesbergerp.reviewrelay.backend.BackendService
import com.github.gillesbergerp.reviewrelay.backend.BackendTask
import com.github.gillesbergerp.reviewrelay.backend.PublishOutcome
import com.github.gillesbergerp.reviewrelay.backend.ReviewRequest
import com.github.gillesbergerp.reviewrelay.ui.editor.InlineCommentManager
import com.github.gillesbergerp.reviewrelay.ui.editor.reviewedFile
import com.github.gillesbergerp.reviewrelay.ui.sendingElsewhereAccepted
import com.github.gillesbergerp.reviewrelay.review.model.CodeAnchor
import com.github.gillesbergerp.reviewrelay.review.model.MessageId
import com.github.gillesbergerp.reviewrelay.review.model.ReviewId
import com.github.gillesbergerp.reviewrelay.review.model.ThreadId
import com.github.gillesbergerp.reviewrelay.review.model.ReviewThread
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

/** Hands the pending review to whichever backend is in use, and reports only what actually happened. */
object ReviewPublisher {

    /**
     * Call on the EDT: the anchor sync and the snapshots read open editors.
     *
     * [only] sends the one thread it names and leaves the summary alone: the summary is the
     * preamble of the whole review, not of a comment sent ahead of it.
     */
    fun publish(project: Project, only: ThreadId? = null) {
        val review = ReviewSessionService.getInstance(project)
        val service = BackendService.getInstance(project)
        val inline = InlineCommentManager.getInstance(project)
        inline.syncCommentLines()

        // Pinned before the send: whichever review this was, its tools must keep serving it.
        val sending = review.activeReviewId
        // The agent this review is worked by, not whichever one another tab is pointed at.
        val backend = service.backendOf(sending)
        val threads = review.pendingThreads.filter { only == null || it.id == only }
        val summary = if (only == null) review.currentSession.summary else ""
        if (threads.isEmpty() && summary.isBlank()) {
            val nothing = if (only == null) {
                "Nothing to send. Add a comment or a review summary first."
            } else {
                "That comment has nothing unsent."
            }
            notifyReview(project, nothing, NotificationType.WARNING)
            return
        }

        val basePath = project.basePath ?: return
        // The session this review remembers, which is what makes two reviews on one backend safe.
        val conversation = review.review(sending)?.conversation
        val sessionId = conversation?.sessionId
        val request = ReviewRequest(
            projectDirectory = basePath,
            summary = summary,
            threads = threads,
            session = sessionId,
            briefed = review.briefed(sending, sessionId),
            inWorkingTree = { reviewedFile(project, it.file) != null },
            lineInWorkingTree = { workingTreeLine(project, it) },
        )
        // Asked here rather than by the backend: it is the same question for every agent, and the
        // answer decides whether anything is sent at all.
        val session = backend.sessions?.sessionOf(sessionId)
        if (!sendingElsewhereAccepted(project, session?.title, session?.directory?.path, basePath)) return

        // Read here, on the EDT, before the task starts: currentText needs the live anchors.
        val snapshots = threads.associate { it.id to inline.currentText(it) }

        // Claimed before queueing rather than inside run(): everything up to it was a window in
        // which a second click sent the same comments again.
        if (!service.begin(BackendTask.PUBLISH, conversation)) return
        // Armed before the send, not after it answers: a short turn can finish while onSuccess is
        // still queued, and the idle that ends it would find nothing waiting for it.
        service.awaitIdleNotification(backend, sessionId)

        object : Task.Backgroundable(project, backend.delivery.progress, false) {
            private var outcome: PublishOutcome? = null

            override fun run(indicator: ProgressIndicator) {
                outcome = backend.publish(request)
            }

            override fun onFinished() = service.end(BackendTask.PUBLISH, conversation)

            override fun onSuccess() {
                when (val result = outcome) {
                    is PublishOutcome.Delivered -> {
                        if (only == null) review.updateSummary(sending, "")
                        review.markSent(sending, snapshots)
                        review.markPublished(sending)
                        // The rules went with it, so the next round to this session only recaps them.
                        review.markBriefed(sending, sessionId)
                        service.refreshAsync()
                        notifyReview(
                            project,
                            "Sent ${comments(snapshots.size)} to ${result.target}.",
                            NotificationType.INFORMATION,
                        )
                    }
                    is PublishOutcome.Published -> {
                        // It went with the review; a second round starts from a clean sheet.
                        if (only == null) review.updateSummary(sending, "")
                        review.markSent(sending, snapshots)
                        review.markPublished(sending)
                        notifyReview(
                            project,
                            "Published ${comments(snapshots.size)}. ${result.hint}",
                            NotificationType.INFORMATION,
                        )
                    }
                    // Nothing left the IDE, so nothing is marked as sent.
                    is PublishOutcome.Blocked -> {
                        service.stopAwaitingIdle(sessionId)
                        notifyReview(project, result.reason, NotificationType.WARNING)
                    }
                    null -> service.stopAwaitingIdle(sessionId)
                }
            }

            override fun onThrowable(error: Throwable) {
                service.stopAwaitingIdle(sessionId)
                notifyReview(project, "Could not send the review: ${reason(error)}", NotificationType.ERROR)
            }
        }.queue()
    }

    /**
     * Where a thread's reviewed code is in the working tree now, or null when it is not there.
     *
     * A comment written against a commit is editable wherever that code still stands, which an
     * amend or a rebase will have renumbered; one written against the working tree is already there.
     */
    fun workingTreeLine(project: Project, thread: ReviewThread): Int? = runReadActionBlocking {
        val file = reviewedFile(project, thread.file) ?: return@runReadActionBlocking null
        // A comment on a commit numbers its lines in that commit, so only a working tree's own count.
        val stored = thread.lines?.start?.takeIf { thread.revision == null }
        val text = FileDocumentManager.getInstance().getDocument(file)?.text
            ?: return@runReadActionBlocking stored
        CodeAnchor.placeOf(text, thread.reviewedCode?.takeIf { !it.isBlank }?.text, stored)
    }

    fun abort(project: Project) {
        val service = BackendService.getInstance(project)
        val reviews = ReviewSessionService.getInstance(project)
        val conversation = service.activeConversation
        val sessionId = conversation?.sessionId
        val interrupt = service.activeBackend.interrupt ?: return
        if (!service.begin(BackendTask.INTERRUPT, conversation)) return

        object : Task.Backgroundable(project, "Stopping the agent", false) {
            override fun run(indicator: ProgressIndicator) = interrupt.interrupt(sessionId)

            override fun onFinished() = service.end(BackendTask.INTERRUPT, conversation)

            override fun onSuccess() {
                service.refreshAsync()
            }

            override fun onThrowable(error: Throwable) {
                notifyReview(project, "Could not stop the agent: ${reason(error)}", NotificationType.ERROR)
            }
        }.queue()
    }
}
