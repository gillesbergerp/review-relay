package com.github.gillesbergerp.reviewrelay.ui.editor

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.EditorDiffViewer
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.diff.tools.util.base.DiffViewerListener
import com.intellij.diff.util.Side
import com.github.gillesbergerp.reviewrelay.ui.EditorInlayHost
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer

/** The side a comment belongs to: the code as it stands after the change, never before it. */
private val COMMENTED = Side.RIGHT

/**
 * Keeps the comments in the diff viewer, which is where a review is actually read.
 *
 * An editor listener alone sees these editors once, when the viewer is built. A rediff - the agent
 * writing the file, or the whitespace setting changing - rebuilds what is in them, so the comments
 * go back in afterwards.
 */
class ReviewDiffExtension : DiffExtension() {

    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val project = context.project ?: return
        if (viewer !is EditorDiffViewer) return

        decorate(project, viewer, request)
        (viewer as? DiffViewerBase)?.addListener(object : DiffViewerListener() {
            override fun onAfterRediff() = decorate(project, viewer, request)
        })
    }

    /**
     * One side carries the comments. Normally that is whichever holds the file live, so a
     * working-tree diff does not show each comment twice; a commit diff holds it on neither side,
     * and there the later revision is the one worth commenting on.
     *
     * The change also names the path, which content built from a revision cannot give, and the
     * commit, without which the lines would be read as the working tree's.
     */
    private fun decorate(project: Project, viewer: EditorDiffViewer, request: DiffRequest) {
        if (viewer is UnifiedDiffViewer) return unified(project, viewer, request)
        val editors = viewer.editors
        val live = editors.filter { reviewPath(project, it.document) != null }
        if (live.isNotEmpty()) {
            matchHeights(editors, live)
            live.forEach { prepareReviewEditor(project, it, reviewPath(project, it.document)) }
            return
        }
        val after = editors.lastOrNull() ?: return
        matchHeights(editors, listOf(after))
        val change = request.getUserData(ChangeDiffRequestProducer.CHANGE_KEY)
        prepareReviewEditor(
            project,
            after,
            change?.let { relativePath(project, ChangesUtil.getFilePath(it).path) }
                ?: reviewablePath(project, after.document),
            change?.let { revisionOf(it) },
        )
    }

    /**
     * One editor over both sides, numbered over the pair: the after side is the one commented on,
     * and the viewer's own convertor is what turns its numbering into the file's either way.
     *
     * There is no other pane to keep in step, so nothing is matched in height here.
     */
    private fun unified(project: Project, viewer: UnifiedDiffViewer, request: DiffRequest) {
        val editor = viewer.editor
        val change = request.getUserData(ChangeDiffRequestProducer.CHANGE_KEY)
        prepareReviewEditor(
            project,
            editor,
            change?.let { relativePath(project, ChangesUtil.getFilePath(it).path) }
                ?: reviewablePath(project, viewer.getDocument(COMMENTED)),
            change?.let { revisionOf(it) },
            ViewerLines(
                toFile = { line -> viewer.transferLineFromOnesideStrict(COMMENTED, line) },
                toEditor = { line -> viewer.transferLineToOneside(COMMENTED, line) },
            ),
        )
    }

    /**
     * A comment makes its own side taller, and the panes are read against each other line by line.
     *
     * Only when one side carries them: both sides commented grow by the same amount at the same
     * line, and a single editor - a unified diff - has nothing to be out of step with.
     */
    private fun matchHeights(editors: List<Editor>, commented: List<Editor>) {
        if (commented.size != 1 || editors.size < 2) return
        val one = commented.single()
        EditorInlayHost.matchHeightsAcross(one, editors.filterNot { it === one })
    }
}
