package com.github.gillesbergerp.reviewrelay.ui

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import java.awt.Color

/**
 * What a review comment needs from wherever it is being shown.
 *
 * The same card renders in an editor and in the tool window, and only these four things differ:
 * without a document there is nothing to seed a suggestion from, which is the one feature that goes.
 */
class CommentSurface(
    val project: Project?,
    val background: Color,
    val foreground: Color,
    val document: Document? = null,
) {

    /** The editor's, as against the tool window's, which is also which one seeds a suggestion. */
    val besideTheCode: Boolean get() = document != null

    companion object {

        fun of(editor: Editor): CommentSurface = CommentSurface(
            project = editor.project,
            background = editor.colorsScheme.defaultBackground,
            foreground = editor.colorsScheme.defaultForeground,
            document = editor.document,
        )

        fun of(project: Project): CommentSurface {
            val scheme = EditorColorsManager.getInstance().globalScheme
            return CommentSurface(project, scheme.defaultBackground, scheme.defaultForeground)
        }
    }
}
