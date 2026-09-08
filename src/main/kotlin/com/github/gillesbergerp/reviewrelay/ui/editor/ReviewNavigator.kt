package com.github.gillesbergerp.reviewrelay.ui.editor

import com.github.gillesbergerp.reviewrelay.review.model.ReviewedFile
import com.github.gillesbergerp.reviewrelay.review.model.ReviewItem
import com.github.gillesbergerp.reviewrelay.review.model.ReviewThread
import com.github.gillesbergerp.reviewrelay.ui.toolwindow.reviewOrder
import com.github.gillesbergerp.reviewrelay.review.service.ReviewSessionService
import com.github.gillesbergerp.reviewrelay.review.service.notifyReview
import com.github.gillesbergerp.reviewrelay.review.service.reason
import com.github.gillesbergerp.reviewrelay.ui.toolwindow.ReviewToolWindowFactory
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Pair
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil

/**
 * Takes the reviewer to the code a comment is about, as source or as a diff.
 *
 * Always ends somewhere visible: the file at the line, the diff at the line, or a notification
 * saying what was missing. Doing nothing at all is the one outcome this must never have.
 */
object ReviewNavigator {

    private val LOG = Logger.getInstance(ReviewNavigator::class.java)

    private enum class Target { SOURCE, DIFF }

    private data class Revision(val label: String, val text: String)

    /** The file at the line; a comment whose file is gone falls back to the diff. */
    fun open(project: Project, item: ReviewItem) = go(project, item, Target.SOURCE)

    /**
     * Walks the review in the order the list shows it, opening each comment as it goes, so a round
     * can be read without going back to the tool window between comments.
     */
    fun step(project: Project, forward: Boolean) {
        val service = ReviewSessionService.getInstance(project)
        val ordered = service.currentSession.threads.sortedWith(reviewOrder)
        if (ordered.isEmpty()) return

        val at = ordered.indexOfFirst { it.id == service.focusedThreadId }
        val next = when {
            at < 0 -> if (forward) 0 else ordered.lastIndex
            else -> Math.floorMod(at + if (forward) 1 else -1, ordered.size)
        }

        val thread = ordered[next]
        service.focusedThreadId = thread.id
        ReviewToolWindowFactory.select(project, thread.id)
        open(project, thread)
    }

    /** The last revision against the working tree, whichever of the two the file is still in. */
    fun diff(project: Project, item: ReviewItem) = go(project, item, Target.DIFF)

    private fun go(project: Project, item: ReviewItem, target: Target) {
        val relative = item.file
        val line = ((item.lines?.start ?: 1) - 1).coerceAtLeast(0)

        val file = reviewedFile(project, relative)
        if (target == Target.SOURCE && file != null) {
            showSource(project, file, line)
        } else {
            resolveThenShow(project, relative, line, target)
        }
    }

    private fun showSource(project: Project, file: VirtualFile, line: Int) {
        val editors = FileEditorManager.getInstance(project)
        if (editors.openTextEditor(OpenFileDescriptor(project, file, line, 0), true) == null) {
            LOG.info("Opened ${file.path} without a text editor")
            editors.openFile(file, true)
        }
    }

    /**
     * The VFS can be behind the agent, so a miss is re-checked against disk before the file is
     * given up for deleted. Both that and the history lookup are too slow for the EDT.
     */
    private fun resolveThenShow(project: Project, relative: ReviewedFile, line: Int, target: Target) {
        object : Task.Backgroundable(project, "Looking for $relative", true) {
            private var file: VirtualFile? = null
            private var revision: Revision? = null

            override fun run(indicator: ProgressIndicator) {
                file = reviewedFile(project, relative, refresh = true)
                if (target == Target.DIFF || file == null) {
                    revision = lastRevision(project, relative.absoluteIn(project.basePath.orEmpty()))
                }
            }

            override fun onSuccess() {
                val found = file
                if (target == Target.SOURCE && found != null) {
                    LOG.info("Opened $relative after a refresh; the VFS had not caught up")
                    showSource(project, found, line)
                    return
                }
                showDiff(project, relative, line, found, revision)
            }

            override fun onThrowable(error: Throwable) {
                LOG.warn("Could not open $relative", error)
                notifyReview(
                    project,
                    "Could not open $relative: ${reason(error)}",
                    NotificationType.WARNING,
                )
            }
        }.queue()
    }

    private fun showDiff(project: Project, relative: ReviewedFile, line: Int, file: VirtualFile?, revision: Revision?) {
        if (file == null && revision == null) {
            notifyReview(
                project,
                "$relative is not in the working tree and has no history to show.",
                NotificationType.WARNING,
            )
            return
        }

        val contents = DiffContentFactory.getInstance()
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(relative.name)
        val request = SimpleDiffRequest(
            relative.path,
            revision?.let { contents.create(project, it.text, fileType) } ?: contents.createEmpty(),
            file?.let { contents.create(project, it) } ?: contents.createEmpty(),
            revision?.let { "$relative at ${it.label}" } ?: "added",
            if (file != null) "working tree" else "deleted",
        )
        // The comment's line belongs to whichever side still has the file.
        val side = if (file != null) Side.RIGHT else Side.LEFT
        request.putUserData(DiffUserDataKeys.SCROLL_TO_LINE, Pair.create(side, line))
        DiffManager.getInstance().showDiff(project, request)
    }

    private fun lastRevision(project: Project, absolutePath: String): Revision? {
        val path = VcsUtil.getFilePath(absolutePath, false)
        val vcs = ProjectLevelVcsManager.getInstance(project).getVcsFor(path) ?: return null
        val session = vcs.vcsHistoryProvider?.createSessionFor(path) ?: return null

        // The newest revision is the one that removed the file, so walk back to the last that still has it.
        return session.revisionList.firstNotNullOfOrNull { revision ->
            val bytes = runCatching { revision.loadContent() }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                null
            } else {
                Revision(revision.revisionNumber.asString().take(8), String(bytes, path.charset))
            }
        }
    }
}
