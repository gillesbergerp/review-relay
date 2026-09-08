package com.github.gillesbergerp.reviewrelay.ui

import com.intellij.openapi.editor.impl.DocumentImpl
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a card hangs from, including the document that has no lines to hang it on. */
class EditorInlayHostTest {

    @Test
    fun `a card hangs from the end of its line`() {
        val document = DocumentImpl("one\ntwo\nthree")

        assertEquals(3, EditorInlayHost.endOf(document, 0))
        assertEquals(7, EditorInlayHost.endOf(document, 1))
    }

    @Test
    fun `a line past the end hangs from the last one`() {
        val document = DocumentImpl("one\ntwo")

        assertEquals(EditorInlayHost.endOf(document, 1), EditorInlayHost.endOf(document, 99))
    }

    /** The other side of an added file, which used to throw out of coerceIn rather than clamp. */
    @Test
    fun `a document with no lines at all hangs from its start`() {
        assertEquals(0, EditorInlayHost.endOf(DocumentImpl(""), 0))
        assertEquals(0, EditorInlayHost.endOf(DocumentImpl(""), 7))
    }
}
