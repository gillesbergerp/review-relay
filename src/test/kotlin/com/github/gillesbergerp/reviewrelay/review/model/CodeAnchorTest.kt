package com.github.gillesbergerp.reviewrelay.review.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodeAnchorTest {

    private val reviewed = "    val total = items.size\n    return total"

    private fun file(vararg lines: String) = lines.joinToString("\n")

    @Test
    fun `the reviewed lines are found where they still are`() {
        val text = file("fun count() {", "    val total = items.size", "    return total", "}")
        assertEquals(2, CodeAnchor.lineOf(text, reviewed))
    }

    @Test
    fun `an amended commit that moved the lines down finds them at their new numbers`() {
        val text = file(
            "import kotlin.math.max",
            "",
            "/** Added by the amend. */",
            "fun count() {",
            "    val total = items.size",
            "    return total",
            "}",
        )
        assertEquals(5, CodeAnchor.lineOf(text, reviewed))
    }

    @Test
    fun `lines the amend removed are not placed anywhere`() {
        assertNull(CodeAnchor.lineOf(file("fun count() = items.size"), reviewed))
    }

    @Test
    fun `code appearing twice is left unplaced rather than guessed at`() {
        val text = file(
            "fun a() {",
            "    val total = items.size",
            "    return total",
            "}",
            "fun b() {",
            "    val total = items.size",
            "    return total",
            "}",
        )
        assertNull(CodeAnchor.lineOf(text, reviewed))
    }

    @Test
    fun `a trailing newline on the reviewed lines does not stop them matching`() {
        val text = file("fun count() {", "    val total = items.size", "    return total", "}")
        assertEquals(2, CodeAnchor.lineOf(text, reviewed + "\n"))
    }

    @Test
    fun `the first line of a file is line one, not line zero`() {
        assertEquals(1, CodeAnchor.lineOf(file("package foo", "", "class Bar"), "package foo"))
    }

    @Test
    fun `blank reviewed code matches nothing`() {
        assertNull(CodeAnchor.lineOf(file("anything"), "   \n  "))
    }

    @Test
    fun `the code is searched for rather than trusted to the number it was read at`() {
        val text = file("added", "above", "fun count() {", "    val total = items.size", "    return total", "}")
        assertEquals(4, CodeAnchor.placeOf(text, reviewed, line = 2))
    }

    @Test
    fun `a comment with no code recorded keeps its number`() {
        assertEquals(7, CodeAnchor.placeOf(file("anything"), null, line = 7))
    }

    @Test
    fun `code that is gone beyond recognition keeps the number it was read at`() {
        assertEquals(2, CodeAnchor.placeOf(file("fun count() = items.size"), reviewed, line = 2))
    }

    @Test
    fun `code gone beyond recognition with no number recorded places nothing`() {
        assertNull(CodeAnchor.placeOf(file("fun count() = items.size"), reviewed, line = null))
    }

    @Test
    fun `the lines the agent edited are found where they now are`() {
        val text = file(
            "data class Session(",
            "    val pid: Int,",
            "    val name: String,",
            "    val updatedAt: Instant,",
            ")",
        )
        assertEquals(4, CodeAnchor.nearestLineOf(text, "    val updatedAt: Long,"))
    }

    @Test
    fun `an edit is followed past the lines inserted above it`() {
        val text = file(
            "enum class Status { BUSY, WAITING, OTHER }",
            "",
            "data class Session(",
            "    val updatedAt: Instant,",
            ")",
        )
        assertEquals(4, CodeAnchor.placeOf(text, "    val updatedAt: Long,", line = 1))
    }

    @Test
    fun `lines of the same shape are not mistaken for the edited ones`() {
        val text = file("    val pipe: String,", "    val token: String,", "    val name: String,")
        assertNull(CodeAnchor.nearestLineOf(text, "    val updatedAt: Long,"))
    }

    @Test
    fun `two candidates equally like the reviewed lines place nothing`() {
        val text = file("fun a(count: Int)", "fun b(other: Int)", "fun a(count: Long)")
        assertNull(CodeAnchor.nearestLineOf(text, "fun a(count: Short)"))
    }

    @Test
    fun `a block edited in the middle is found whole rather than vetoed by its own overlap`() {
        val text = file(
            "fun count() {",
            "    val total = items.count()",
            "    return total",
            "}",
        )
        assertEquals(2, CodeAnchor.nearestLineOf(text, reviewed))
    }

    @Test
    fun `an exact match wins over a place that merely resembles the code`() {
        val text = file(
            "    val total = items.count()",
            "    return total",
            "    val total = items.size",
            "    return total",
        )
        assertEquals(3, CodeAnchor.placeOf(text, reviewed, line = 1))
    }

    @Test
    fun `code longer than the whole file places nothing`() {
        assertNull(CodeAnchor.nearestLineOf(file("one line"), reviewed))
    }
}
