package com.github.gillesbergerp.reviewrelay.ui

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A comment box is not a prose editor: the newlines someone types are the ones they meant.
 *
 * Markdown reads a single newline as a space, so a two-line comment used to render as one line.
 */
class MarkdownBreaksTest {

    @Test
    fun `a typed newline survives as a break`() {
        assertEquals("a  \nb", hardBreaks("a\nb"))
    }

    @Test
    fun `a paragraph break is left alone`() {
        assertEquals("a\n\nb", hardBreaks("a\n\nb"))
    }

    @Test
    fun `the last line needs no break after it`() {
        assertEquals("only", hardBreaks("only"))
    }

    /** A fence opens a block of its own, so there is nothing for a break to do on either side of it. */
    @Test
    fun `a fenced block keeps every line as it was written`() {
        val fenced = "look:\n```\nval a = 1\nval b = 2\n```\nand that is all"
        assertEquals(fenced, hardBreaks(fenced))
    }

    @Test
    fun `a list is already broken and gains nothing`() {
        assertEquals("items:\n- one\n- two", hardBreaks("items:\n- one\n- two"))
    }

    @Test
    fun `a heading or quote after a line starts its own block`() {
        assertEquals("intro\n## Next", hardBreaks("intro\n## Next"))
        assertEquals("intro\n> quoted", hardBreaks("intro\n> quoted"))
    }

    @Test
    fun `a break already written by hand is not doubled`() {
        assertEquals("a  \nb", hardBreaks("a  \nb"))
    }

    /** An agent writes the markdown, and the generator passes raw HTML straight through. */
    @Test
    fun `an image the pane would fetch over the network is dropped`() {
        val html = """<p>see <img src="https://example.test/x.png" alt="x"> here</p>"""

        val safe = withoutRemoteImages(html)

        assertTrue(safe, !safe.contains("example.test"))
        assertTrue(safe, safe.contains("see"))
        assertTrue(safe, safe.contains("here"))
    }

    @Test
    fun `a protocol-relative image is fetched over the network too`() {
        assertTrue(!withoutRemoteImages("""<img src="//example.test/x.png">""").contains("example.test"))
    }

    @Test
    fun `everything else the generator wrote is left alone`() {
        val html = "<p>a <code>fence</code> and a <a href=\"https://example.test\">link</a></p>"

        assertEquals(html, withoutRemoteImages(html))
    }
}
