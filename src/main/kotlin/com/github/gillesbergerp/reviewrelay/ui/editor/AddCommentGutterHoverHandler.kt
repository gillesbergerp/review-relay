package com.github.gillesbergerp.reviewrelay.ui.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import javax.swing.Icon

class AddCommentGutterHoverHandler(private val project: Project) :
    EditorMouseMotionListener, EditorMouseListener {

    /** Nothing else ever fires once the pointer is gone, so the icon stayed on the last line crossed. */
    override fun mouseExited(e: EditorMouseEvent) = clearHighlighter(e.editor)

    private var currentHighlighter: RangeHighlighter? = null
    private var currentLine: Int = -1

    override fun mouseMoved(e: EditorMouseEvent) {
        val editor = e.editor
        if (editor.isDisposed) return
        if (commentPath(editor) == null) return

        val line = editor.xyToLogicalPosition(e.mouseEvent.point).line
        val document = editor.document

        // A removed line of a unified diff is on neither side of the working tree, so there is
        // nothing there to comment on and no + to offer.
        if (line < 0 || line >= document.lineCount || commentLines(editor).fileLine(line) == null) {
            clearHighlighter(editor)
            return
        }

        if (line == currentLine) return

        clearHighlighter(editor)
        currentLine = line

        val offset = document.getLineStartOffset(line)
        val highlighter = editor.markupModel.addRangeHighlighter(
            offset, offset,
            HighlighterLayer.LAST,
            null,
            HighlighterTargetArea.LINES_IN_RANGE
        )
        highlighter.gutterIconRenderer = AddCommentGutterIconRenderer(editor, project, line)
        currentHighlighter = highlighter
    }

    private fun clearHighlighter(editor: Editor) {
        currentHighlighter?.let { editor.markupModel.removeHighlighter(it) }
        currentHighlighter = null
        currentLine = -1
    }
}

private class AddCommentGutterIconRenderer(
    private val editor: Editor,
    private val project: Project,
    private val line0: Int
) : GutterIconRenderer() {

    private val click = object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            if (line0 < 0 || line0 >= editor.document.lineCount) return
            InlineCommentManager.getInstance(project).newComment(editor, line0 + 1)
        }
    }

    // Not Balloon, which CommentStatusUi already uses to mean "the agent answered": in the gutter it
    // has to read as something to press, not as something that happened.
    override fun getIcon(): Icon = AllIcons.General.InlineAdd

    override fun getTooltipText(): String = listOfNotNull(
        "Add review comment",
        KeymapUtil
            .getFirstKeyboardShortcutText(ActionManager.getInstance().getAction("ReviewRelay.AddLineComment"))
            .takeIf { it.isNotBlank() }
            ?.let { "($it)" },
    ).joinToString(" ")

    override fun getClickAction(): AnAction = click

    // The gutter takes this as the cue to show a hand rather than the arrow.
    override fun isNavigateAction(): Boolean = true

    override fun equals(other: Any?): Boolean = other is AddCommentGutterIconRenderer && other.line0 == line0

    override fun hashCode(): Int = line0
}
