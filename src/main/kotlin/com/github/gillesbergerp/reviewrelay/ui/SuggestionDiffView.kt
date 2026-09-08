package com.github.gillesbergerp.reviewrelay.ui

import com.github.gillesbergerp.reviewrelay.review.model.DiffLine
import com.github.gillesbergerp.reviewrelay.review.model.DiffLineKind
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import java.awt.Font

/**
 * A suggestion shown the way the IDE shows a diff: the real code, syntax highlighted, with the
 * scheme's own added and removed line colours instead of +/- markers.
 */
class SuggestionDiffView(
    private val project: Project?,
    private val fileType: FileType,
    private val rows: List<DiffLine>,
) : EditorTextField(
    EditorFactory.getInstance().createDocument(rows.joinToString("\n") { it.text }),
    project,
    fileType,
    true,
    false,
) {

    init {
        addSettingsProvider { editor ->
            // EditorTextField only installs one when it was given a project, and an inlay in a diff
            // is not always able to name one. The file type alone is enough to colour by.
            runCatching {
                editor.highlighter = EditorHighlighterFactory.getInstance()
                    .createEditorHighlighter(fileType, editor.colorsScheme, project)
            }
            editor.settings.isLineNumbersShown = false
            editor.settings.isCaretRowShown = false
            // Wrapped rather than scrolled: the card is clipped to the pane it sits in and shows no
            // scrollbar, so a line wider than that was unreachable by any gesture.
            editor.settings.isUseSoftWraps = true
            editor.settings.additionalLinesCount = 0
            editor.settings.additionalColumnsCount = 0
            editor.setHorizontalScrollbarVisible(false)
            editor.setVerticalScrollbarVisible(false)
            // A viewer editor still opens with a caret row and a selection, which wash over the bands.
            editor.setCaretEnabled(false)
            editor.selectionModel.removeSelection()
            editor.contentComponent.isFocusable = false
            paintChangedLines(editor)
        }
    }

    private fun paintChangedLines(editor: EditorEx) {
        // The DiffColors attribute keys are what the diff viewer resolves per theme. The
        // ADDED_LINES_COLOR colour keys look equivalent but are unset in most schemes, so they fall
        // back to a light-theme default that glares on a dark background.
        val scheme = editor.colorsScheme
        val added = scheme.getAttributes(DiffColors.DIFF_INSERTED)?.backgroundColor
        val removed = scheme.getAttributes(DiffColors.DIFF_DELETED)?.backgroundColor
        val document = editor.document

        rows.forEachIndexed { line, row ->
            val background = when (row.kind) {
                DiffLineKind.ADDED -> added
                DiffLineKind.REMOVED -> removed
                DiffLineKind.CONTEXT -> null
            } ?: return@forEachIndexed
            if (line >= document.lineCount) return@forEachIndexed

            editor.markupModel.addRangeHighlighter(
                document.getLineStartOffset(line),
                document.getLineEndOffset(line),
                HighlighterLayer.SELECTION - 1,
                TextAttributes(null, background, null, null, Font.PLAIN),
                HighlighterTargetArea.LINES_IN_RANGE,
            )
        }
    }
}
