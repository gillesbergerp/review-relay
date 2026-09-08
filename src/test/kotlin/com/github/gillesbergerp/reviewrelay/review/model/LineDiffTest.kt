package com.github.gillesbergerp.reviewrelay.review.model

import com.intellij.diff.fragments.LineFragment
import com.intellij.diff.fragments.LineFragmentImpl
import org.junit.Assert.assertEquals
import org.junit.Test

class LineDiffTest {

    private val before = listOf("fun f() {", "    val a = 1", "    return a", "}")
    private val after = listOf("fun f() {", "    return 1", "}")

    /** Offsets are irrelevant to row assembly, so they stay zero. */
    private fun fragment(startLine1: Int, endLine1: Int, startLine2: Int, endLine2: Int): LineFragment =
        LineFragmentImpl(startLine1, endLine1, startLine2, endLine2, 0, 0, 0, 0)

    private fun kinds(rows: List<DiffLine>) = rows.map { it.kind }

    @Test
    fun `no fragments means the whole block is unchanged context`() {
        val rows = LineDiff.assemble(before, before, emptyList())
        assertEquals(before, rows.map { it.text })
        assertEquals(List(before.size) { DiffLineKind.CONTEXT }, kinds(rows))
    }

    @Test
    fun `a replaced block keeps its surrounding context and groups removals before additions`() {
        val rows = LineDiff.assemble(before, after, listOf(fragment(1, 3, 1, 2)))

        assertEquals(
            listOf(
                DiffLine(DiffLineKind.CONTEXT, "fun f() {"),
                DiffLine(DiffLineKind.REMOVED, "    val a = 1"),
                DiffLine(DiffLineKind.REMOVED, "    return a"),
                DiffLine(DiffLineKind.ADDED, "    return 1"),
                DiffLine(DiffLineKind.CONTEXT, "}"),
            ),
            rows,
        )
    }

    @Test
    fun `an insertion adds without removing`() {
        val rows = LineDiff.assemble(listOf("a", "b"), listOf("a", "new", "b"), listOf(fragment(1, 1, 1, 2)))
        assertEquals(listOf(DiffLineKind.CONTEXT, DiffLineKind.ADDED, DiffLineKind.CONTEXT), kinds(rows))
        assertEquals("new", rows[1].text)
    }

    @Test
    fun `a deletion removes without adding`() {
        val rows = LineDiff.assemble(listOf("a", "gone", "b"), listOf("a", "b"), listOf(fragment(1, 2, 1, 1)))
        assertEquals(listOf(DiffLineKind.CONTEXT, DiffLineKind.REMOVED, DiffLineKind.CONTEXT), kinds(rows))
        assertEquals("gone", rows[1].text)
    }

    @Test
    fun `several fragments each keep the context between them`() {
        val rows = LineDiff.assemble(
            listOf("a", "x", "b", "y", "c"),
            listOf("a", "1", "b", "2", "c"),
            listOf(fragment(1, 2, 1, 2), fragment(3, 4, 3, 4)),
        )
        assertEquals(
            listOf(
                DiffLineKind.CONTEXT, DiffLineKind.REMOVED, DiffLineKind.ADDED,
                DiffLineKind.CONTEXT, DiffLineKind.REMOVED, DiffLineKind.ADDED,
                DiffLineKind.CONTEXT,
            ),
            kinds(rows),
        )
    }

    @Test
    fun `a refused diff falls back to replacing the whole block`() {
        val rows = LineDiff.assemble(before, after, null)
        assertEquals(before + after, rows.map { it.text })
        assertEquals(
            List(before.size) { DiffLineKind.REMOVED } + List(after.size) { DiffLineKind.ADDED },
            kinds(rows),
        )
    }

    @Test
    fun `fragment bounds past the end of the text do not overrun`() {
        val rows = LineDiff.assemble(listOf("only"), listOf("only", "extra"), listOf(fragment(1, 9, 1, 9)))
        assertEquals(listOf(DiffLine(DiffLineKind.CONTEXT, "only"), DiffLine(DiffLineKind.ADDED, "extra")), rows)
    }
}
