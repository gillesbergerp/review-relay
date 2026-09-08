package com.github.gillesbergerp.reviewrelay.util.json

import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {

    @Test
    fun `parses a nested object`() {
        val value = Json.parse("""{"id":"ses_1","time":{"updated":1788162475126},"tags":["a","b"]}""")
        assertEquals("ses_1", value["id"].string)
        assertEquals(1788162475126L, value["time"]["updated"].long)
        assertEquals(listOf("a", "b"), value["tags"].items.map { it.string })
    }

    @Test
    fun `missing keys read as null instead of throwing`() {
        val value = Json.parse("""{"a":1}""")
        assertNull(value["b"].string)
        assertNull(value["b"]["c"].int)
        assertTrue(value["b"].items.isEmpty())
    }

    @Test
    fun `parses literals and numbers`() {
        val value = Json.parse("""{"t":true,"f":false,"n":null,"neg":-2.5,"exp":1e3}""")
        assertEquals(true, value["t"].bool)
        assertEquals(false, value["f"].bool)
        assertNull(value["n"])
        assertEquals(-2.5, value["neg"].double!!, 0.0001)
        assertEquals(1000, value["exp"].int)
    }

    @Test
    fun `parses string escapes`() {
        val json = "\"line\\nbreak \\\"quoted\\\" back\\\\slash \\u0041\""
        assertEquals("line\nbreak \"quoted\" back\\slash A", Json.parse(json).string)
    }

    @Test
    fun `parses empty containers`() {
        assertTrue(Json.parse("[]").items.isEmpty())
        assertTrue(Json.parse("{}").fields.isEmpty())
    }

    @Test
    fun `writes maps, lists and scalars`() {
        val written = Json.write(
            mapOf(
                "parts" to listOf(mapOf("type" to "text", "text" to "hi")),
                "count" to 2,
                "flag" to true,
            )
        )
        assertEquals("""{"parts":[{"type":"text","text":"hi"}],"count":2,"flag":true}""", written)
    }

    @Test
    fun `null map values are dropped so optional fields can be passed through`() {
        assertEquals("""{"a":1}""", Json.write(mapOf("a" to 1, "agent" to null)))
    }

    @Test
    fun `escapes control characters when writing`() {
        assertEquals("\"a\\nb\\tc\\\"d\\\\e\"", Json.write("a\nb\tc\"d\\e"))
    }

    @Test
    fun `round trips text with newlines and quotes`() {
        val text = "Comment with \"quotes\", a\nnewline and a backslash \\ in it"
        assertEquals(text, Json.parse(Json.write(text)).string)
    }

    @Test
    fun `parseOrNull returns null on malformed input`() {
        assertNull(Json.parseOrNull("{not json"))
        assertNull(Json.parseOrNull(""))
        assertNull(Json.parseOrNull("""{"a":1}trailing"""))
    }

    @Test
    fun `writes review prose without escaping its punctuation`() {
        assertEquals("\"a < b && c = d\"", Json.write("a < b && c = d"))
    }

    @Test
    fun `reads an int64 beyond what a double holds exactly`() {
        assertEquals(9007199254740993L, Json.parse("""{"n":9007199254740993}""")["n"].long)
    }

    /** `gh api --paginate` writes one array per page, back to back rather than as one document. */
    @Test
    fun `pages written back to back are read as the documents they are`() {
        val paged = """
            [{"filename":"a.kt"}]
            [{"filename":"b.kt"}]
        """.trimIndent()

        val documents = Json.parseAll(paged)

        assertEquals(2, documents.size)
        assertEquals(listOf("a.kt", "b.kt"), documents.flatMap { it.items }.map { it["filename"].string })
    }

    @Test
    fun `one document is still one document`() {
        assertEquals(1, Json.parseAll("""[{"filename":"a.kt"}]""").size)
    }

    @Test
    fun `nothing at all is not a document`() {
        assertThrows(JsonException::class.java) { Json.parseAll("   ") }
    }

    @Test
    fun `a stream that is not json at all says so`() {
        assertThrows(JsonException::class.java) { Json.parseAll("""{"a":""") }
    }

    /**
     * The stream is read leniently, so a notice gh wrote beside the pages is one more document
     * rather than the end of the read - and the pages are still there to be found.
     */
    @Test
    fun `prose beside the pages does not lose them`() {
        val documents = Json.parseAll("""[{"filename":"a.kt"}] done""")

        assertEquals(listOf("a.kt"), documents.flatMap { it.items }.map { it["filename"].string })
    }
}
