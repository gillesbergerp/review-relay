package com.github.gillesbergerp.reviewrelay.backend.mcp

import com.github.gillesbergerp.reviewrelay.util.json.Json
import com.github.gillesbergerp.reviewrelay.util.json.bool
import com.github.gillesbergerp.reviewrelay.util.json.get
import com.github.gillesbergerp.reviewrelay.util.json.items
import com.github.gillesbergerp.reviewrelay.util.json.string
import com.google.gson.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpProtocolTest {

    private val noProjects: (String?) -> ProjectLookup.Result = { ProjectLookup.Result.NoneOpen }

    private fun ask(
        body: String,
        lookup: (String?) -> ProjectLookup.Result = noProjects,
    ): JsonElement? = McpProtocol.respond(body, "1.2.3", lookup)?.let { Json.parse(it) }

    @Test
    fun `initialize names the server and echoes the protocol asked for`() {
        val answer = ask("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}""")
        val result = answer["result"]
        assertEquals("2.0", answer["jsonrpc"].string)
        assertEquals("2025-03-26", result["protocolVersion"].string)
        assertEquals("Review Relay", result["serverInfo"]["name"].string)
        assertEquals("1.2.3", result["serverInfo"]["version"].string)
        assertNotNull(result["capabilities"]["tools"])
    }

    @Test
    fun `initialize falls back to a known protocol when none is asked for`() {
        val answer = ask("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")
        assertEquals("2025-06-18", answer["result"]["protocolVersion"].string)
    }

    @Test
    fun `exactly the review tools are offered`() {
        val tools = ask("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")["result"]["tools"].items
        assertEquals(
            listOf("review_comments", "review_comment", "review_propose", "review_reply"),
            tools.map { it["name"].string },
        )
        val reply = tools.single { it["name"].string == "review_reply" }
        assertEquals(listOf("id", "note"), reply["inputSchema"]["required"].items.map { it.string })
        val propose = tools.single { it["name"].string == "review_propose" }
        assertEquals(listOf("file", "text"), propose["inputSchema"]["required"].items.map { it.string })
        assertEquals("integer", propose["inputSchema"]["properties"]["lineStart"]["type"].string)
    }

    /** The schema is read off the class the arguments are parsed into, so it says what they are. */
    @Test
    fun `an advertised argument carries its type and its description`() {
        val tools = ask("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")["result"]["tools"].items
        val comments = tools.single { it["name"].string == "review_comments" }
        val properties = comments["inputSchema"]["properties"]
        assertEquals("object", comments["inputSchema"]["type"].string)
        assertEquals("boolean", properties["includeClosed"]["type"].string)
        assertEquals("string", properties["directory"]["type"].string)
        assertTrue(properties["directory"]["description"].string!!.isNotBlank())
        // Nothing is required, so the key is absent rather than an empty list.
        assertNull(comments["inputSchema"]["required"])
    }

    /** The drift this guards against: a tool advertised in tools/list that tools/call cannot run. */
    @Test
    fun `every advertised tool can be called`() {
        val tools = ask("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")["result"]["tools"].items
        assertTrue(tools.isNotEmpty())
        tools.map { it["name"].string }.forEach { name ->
            val call = """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"$name","arguments":{}}}"""
            val said = ask(call)["result"]["content"].items.first()["text"].string.orEmpty()
            // No project is open in this fixture, so every tool gets that far and no further.
            assertTrue(name + " -> " + said, said.contains("No project is open"))
        }
    }

    @Test
    fun `a notification is not answered`() {
        assertNull(McpProtocol.respond("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", "1"))
    }

    @Test
    fun `a request without an id is not answered either`() {
        assertNull(McpProtocol.respond("""{"jsonrpc":"2.0","method":"tools/list"}""", "1"))
    }

    @Test
    fun `the id comes back as it was sent`() {
        assertEquals("abc", ask("""{"jsonrpc":"2.0","id":"abc","method":"ping"}""")["id"].string)
    }

    @Test
    fun `malformed json is a parse error`() {
        val answer = ask("not json at all")
        assertEquals(-32700, answer["error"]["code"]?.asInt)
    }

    @Test
    fun `an unknown method is refused as a protocol error`() {
        val answer = ask("""{"jsonrpc":"2.0","id":3,"method":"resources/list"}""")
        assertEquals(-32601, answer["error"]["code"]?.asInt)
    }

    @Test
    fun `an unknown tool is refused as a tool result, so the agent can read it`() {
        val answer = ask("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"rm_rf"}}""")
        assertNull(answer["error"])
        assertTrue(answer["result"]["isError"].bool == true)
        assertTrue(text(answer).contains("Unknown tool"))
    }

    @Test
    fun `with no project open the tool says so`() {
        val answer = ask("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"review_comments"}}""")
        assertTrue(answer["result"]["isError"].bool == true)
        assertTrue(text(answer).contains("No project is open"))
    }

    @Test
    fun `with several projects open the tool names them and asks for a directory`() {
        val several: (String?) -> ProjectLookup.Result =
            { ProjectLookup.Result.Ambiguous(listOf("/a/app", "/b/other")) }
        val answer = ask(
            """{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"review_reply","arguments":{"id":"x","note":"y"}}}""",
            several,
        )
        val message = text(answer)
        assertTrue(answer["result"]["isError"].bool == true)
        assertTrue(message.contains("directory"))
        assertTrue(message.contains("/a/app"))
        assertTrue(message.contains("/b/other"))
    }

    /** An exception out of here reached netty, which answered by closing the connection. */
    @Test
    fun `a tool that throws is refused in words, not by dropping the connection`() {
        val broken: (String?) -> ProjectLookup.Result = { error("the VFS blew up") }

        val answer = ask(
            """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"review_comments"}}""",
            broken,
        )

        assertNull(answer["error"])
        assertTrue(answer["result"]["isError"].bool == true)
        assertTrue(text(answer), text(answer).contains("the VFS blew up"))
    }

    private fun text(answer: JsonElement?): String =
        answer["result"]["content"].items.single()["text"].string.orEmpty()
}
