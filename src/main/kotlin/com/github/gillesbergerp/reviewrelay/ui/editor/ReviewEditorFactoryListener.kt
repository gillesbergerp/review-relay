package com.github.gillesbergerp.reviewrelay.ui.editor

import com.github.gillesbergerp.reviewrelay.review.model.ReviewedFile
import com.github.gillesbergerp.reviewrelay.review.model.Revision
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Key

/**
 * Puts the review comments and the gutter's add button into every editor of the project.
 *
 * The listener is registered in code rather than in XML because the dispatch signature of
 * [EditorFactoryListener] changed in 2024.1 and an XML listener has to commit to one of them.
 */
class ReviewEditorListenerStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        EditorFactory.getInstance().addEditorFactoryListener(ReviewEditorFactoryListenerImpl(), project)

        // The tabs restored with the project were created before this ran, so they never saw the
        // listener and would show no comments until they were closed and opened again.
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            EditorFactory.getInstance().allEditors
                .filter { it.project == project }
                .forEach { prepareReviewEditor(project, it, reviewPath(project, it.document)) }
        }
    }
}

private class ReviewEditorFactoryListenerImpl : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project ?: return
        prepareReviewEditor(project, editor, reviewPath(project, editor.document))
    }
}

private val REVIEW_PATH = Key.create<ReviewedFile>("reviewrelay.reviewPath")
private val REVIEW_REVISION = Key.create<Revision>("reviewrelay.reviewRevision")
private val REVIEW_LINES = Key.create<DiffLines>("reviewrelay.reviewLines")

/**
 * Safe to call again for the same editor: only the comments are re-applied on a second pass.
 *
 * [path], [revision] and [lines] are the caller's decision, because only it knows whether this
 * editor is the one side of a diff that should carry comments, whether that side is a commit, and
 * whether its numbering is the file's at all.
 */
internal fun prepareReviewEditor(
    project: Project,
    editor: Editor,
    path: ReviewedFile?,
    revision: Revision? = null,
    lines: DiffLines = SameLines,
) {
    if (editor.isDisposed || path == null) return
    if (editor.getUserData(REVIEW_PATH) == null) {
        val gutter = AddCommentGutterHoverHandler(project)
        editor.addEditorMouseMotionListener(gutter)
        editor.addEditorMouseListener(gutter)
    }
    editor.putUserData(REVIEW_PATH, path)
    // Written on every pass, so a viewer rebuilt over the working tree drops the commit it had.
    editor.putUserData(REVIEW_REVISION, revision)
    editor.putUserData(REVIEW_LINES, lines)
    InlineCommentManager.getInstance(project).reapplyInlineComments(editor, path)
}

/**
 * The path a comment placed in this editor belongs to, or null if this is not a side that carries
 * comments — the other pane of a diff numbers its lines differently, so a comment made there would
 * anchor to the wrong ones.
 *
 * The path the editor was prepared with, not one derived again: content built from a revision has no
 * file of its own to ask.
 */
internal fun commentPath(editor: Editor): ReviewedFile? = editor.getUserData(REVIEW_PATH)

/** The commit this editor's lines were read from, so a comment on them is not taken for the file. */
internal fun commentRevision(editor: Editor): Revision? = editor.getUserData(REVIEW_REVISION)

/** How this editor's numbering relates to the file's, which for all but a unified diff is exactly. */
internal fun commentLines(editor: Editor): DiffLines = editor.getUserData(REVIEW_LINES) ?: SameLines
