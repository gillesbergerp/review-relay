package com.github.gillesbergerp.reviewrelay.ui.editor

/**
 * How an editor's own line numbers relate to the file's, where the two are not the same.
 *
 * A unified diff shows both sides in one document, so its numbering counts the removed lines too:
 * a comment placed at a line of that document names a line the file does not have, and a comment
 * drawn at the file's own number lands wherever the removals have pushed it to.
 *
 * Both sides are 0-based, as a [com.intellij.openapi.editor.Document] counts.
 */
internal interface DiffLines {

    /** The file line this editor's [line] shows, or null where it shows the other side's. */
    fun fileLine(line: Int): Int?

    /** The line of this editor showing the file's [line], or null where it shows none. */
    fun editorLine(line: Int): Int?
}

/** For every editor over the file itself, and for the side of a diff built from one revision. */
internal object SameLines : DiffLines {
    override fun fileLine(line: Int): Int = line
    override fun editorLine(line: Int): Int = line
}

/**
 * The mapping a diff viewer keeps, read on demand.
 *
 * Asked rather than copied, because a rediff replaces it: the agent writing the file while the
 * viewer is open is exactly when these numbers move.
 */
internal class ViewerLines(
    private val toFile: (Int) -> Int,
    private val toEditor: (Int) -> Int,
) : DiffLines {

    override fun fileLine(line: Int): Int? = toFile(line).takeIf { it >= 0 }

    override fun editorLine(line: Int): Int? = toEditor(line).takeIf { it >= 0 }
}
