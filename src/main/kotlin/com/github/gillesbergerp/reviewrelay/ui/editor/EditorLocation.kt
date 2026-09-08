package com.github.gillesbergerp.reviewrelay.ui.editor

import com.github.gillesbergerp.reviewrelay.review.model.ReviewedFile
import com.github.gillesbergerp.reviewrelay.review.model.Revision
import com.github.gillesbergerp.reviewrelay.util.PathMatch
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/**
 * Null outside the project: a comment must anchor to the working tree the agent will edit, and the
 * base side of a diff is a light virtual file carrying a path the agent cannot resolve.
 */
internal fun relativePath(project: Project, absolutePath: String): ReviewedFile? {
    val base = project.basePath ?: return null
    // A plain startsWith answers case-sensitively and without a separator boundary, so a sibling
    // sharing the project's name resolved to a path inside it.
    if (!PathMatch.below(base, absolutePath)) return null
    return ReviewedFile.of(PathMatch.normalize(absolutePath).removePrefix(PathMatch.normalize(base) + "/"))
}

/**
 * Reads the document's file, so it holds a read action: the EDT carries none of its own, and the
 * hover and redraw paths that reach this all run there.
 */
internal fun relativePath(project: Project, document: Document): ReviewedFile? = runReadAction {
    FileDocumentManager.getInstance().getFile(document)?.path?.let { relativePath(project, it) }
}

/**
 * The one answer to "is the file this comment is about still there", used by the comment list, the
 * payload and navigation alike. Two answers, from the VFS and from disk, disagreed often enough to
 * label a live file deleted.
 *
 * [refresh] costs a stat and must not run on the EDT; without it a file the agent has just written
 * can still read as gone.
 */
internal fun reviewedFile(project: Project, relativePath: ReviewedFile?, refresh: Boolean = false): VirtualFile? {
    val base = project.basePath ?: return null
    val absolute = relativePath?.absoluteIn(base) ?: return null
    val local = LocalFileSystem.getInstance()
    val file = if (refresh) local.refreshAndFindFileByPath(absolute) else local.findFileByPath(absolute)
    return file?.takeIf { it.isValid && !it.isDirectory }
}

/**
 * A path a comment could anchor to: inside the project, and still in the working tree for the agent
 * to edit. Says nothing about which side of a diff it is, so a caller showing two must choose.
 */
internal fun reviewablePath(project: Project, document: Document): ReviewedFile? {
    val path = relativePath(project, document) ?: return null
    return path.takeIf { reviewedFile(project, it) != null }
}

/**
 * The same path, but only from the editor holding the file live.
 *
 * A diff builds its other side from a revision and can hand it the real path, so this is how one
 * viewer showing the working tree twice ends up hosting one comment rather than two.
 */
internal fun reviewPath(project: Project, document: Document): ReviewedFile? = runReadAction {
    val path = reviewablePath(project, document) ?: return@runReadAction null
    val file = reviewedFile(project, path) ?: return@runReadAction null
    path.takeIf { FileDocumentManager.getInstance().getDocument(file) === document }
}

/**
 * The commit the side a comment is placed on was built from, or null when that side is the working
 * tree. A local change carries a revision object too, so the number has to be asked for rather than
 * inferred from the side being present.
 */
internal fun revisionOf(change: Change): Revision? {
    val revision = change.afterRevision ?: change.beforeRevision ?: return null
    if (revision is CurrentContentRevision) return null
    val number = runCatching { revision.revisionNumber.asString() }.getOrNull().orEmpty()
    return number.takeIf { it.isNotBlank() && it != VcsRevisionNumber.NULL.asString() }?.let(::Revision)
}

/** Uses the editor selection when it covers the clicked line, so a drag becomes a range comment. */
internal fun rangeForLine(editor: Editor, line1Based: Int): Pair<Int, Int?> {
    val selection = editor.selectionModel
    if (selection.hasSelection()) {
        val document = editor.document
        val start = document.getLineNumber(selection.selectionStart) + 1
        val end = document.getLineNumber(selection.selectionEnd) + 1
        if (line1Based in start..end) return start to (if (end == start) null else end)
    }
    return line1Based to null
}

/** The current text of the reviewed lines, used to seed a suggestion and to diff it against. */
internal fun reviewedLines(document: Document, lineStart: Int, lineEnd: Int): String? = runReadActionBlocking {
    if (document.lineCount == 0) return@runReadActionBlocking null
    val first = (lineStart - 1).coerceIn(0, document.lineCount - 1)
    val last = (lineEnd - 1).coerceIn(first, document.lineCount - 1)
    document.getText(TextRange(document.getLineStartOffset(first), document.getLineEndOffset(last)))
}
