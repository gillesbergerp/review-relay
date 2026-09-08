package com.github.gillesbergerp.reviewrelay.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two numberings a unified diff has, and the arithmetic between them.
 *
 * Modelled on what the viewer's convertor answers: the line of the side it is asked about, or -1
 * where that side has no line there at all. The diff below is of a file whose second line was
 * replaced, so its one document reads `keep`, `-was`, `+is`, `keep`.
 */
class DiffLinesTest {

    /** Editor line to after-side line, and -1 for the removed one. */
    private val toFile = listOf(0, -1, 1, 2)

    /** After-side line to editor line. */
    private val toEditor = listOf(0, 2, 3)

    private val unified = ViewerLines(
        toFile = { line -> toFile.getOrElse(line) { -1 } },
        toEditor = { line -> toEditor.getOrElse(line) { -1 } },
    )

    @Test
    fun `a line the change kept is on both sides`() {
        assertEquals(0, unified.fileLine(0))
        assertEquals(0, unified.editorLine(0))
    }

    @Test
    fun `the removals push the file's lines down the document`() {
        assertEquals(1, unified.fileLine(2))
        assertEquals(2, unified.editorLine(1))
        assertEquals(2, unified.fileLine(3))
        assertEquals(3, unified.editorLine(2))
    }

    /** There is nothing in the working tree at a line the change removed. */
    @Test
    fun `a removed line has no line in the file`() {
        assertNull(unified.fileLine(1))
    }

    @Test
    fun `a line neither side has is no line at all`() {
        assertNull(unified.fileLine(99))
        assertNull(unified.editorLine(99))
    }

    /** Every editor over the file itself, where the two numberings are the same one. */
    @Test
    fun `an ordinary editor numbers the file's own lines`() {
        assertEquals(7, SameLines.fileLine(7))
        assertEquals(7, SameLines.editorLine(7))
    }

    /** Placing a card: the file's range out to the editor's, which the removals stretch. */
    @Test
    fun `a range grows by the lines removed inside it`() {
        assertEquals(0, unified.editorLine(0))
        assertEquals(3, unified.editorLine(2))
    }
}
