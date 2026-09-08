package com.github.gillesbergerp.reviewrelay.backend.opencode

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Against payloads captured from a running OpenCode, so the shapes are its own rather than mine. */
class OpenCodePayloadsTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/opencode/$name")) { "missing fixture $name" }.readText()

    @Test
    fun `a session page decodes, fields this build never heard of and all`() {
        val page = openCodeJson.decodeFromString<SessionPage>(fixture("session-page.json"))
        assertTrue(page.data.isNotEmpty())
        val session = page.data.first().asSession()
        assertTrue(session.id.startsWith("ses_"))
        assertTrue(session.title.isNotEmpty())
        assertTrue(session.updatedAt > 0)
    }

    /**
     * A listed session states its directory under location, and nothing asked for it: every session
     * read as standing nowhere, which the elsewhere warning and the API scoping both need.
     */
    @Test
    fun `a listed session says where it is running`() {
        val page = openCodeJson.decodeFromString<SessionPage>(fixture("session-page.json"))

        assertTrue(page.data.all { it.asSession().directory.isNotEmpty() })
        assertEquals(
            """C:\work\example-service""",
            page.data.first().asSession().directory,
        )
    }

    /** A created session answers with it at the top level instead. */
    @Test
    fun `a directory given either way is the same directory`() {
        val top = openCodeJson.decodeFromString<SessionPayload>("""{"id":"s","directory":"/a/b"}""")
        val nested = openCodeJson.decodeFromString<SessionPayload>(
            """{"id":"s","location":{"directory":"/a/b"}}""",
        )

        assertEquals("/a/b", top.asSession().directory)
        assertEquals("/a/b", nested.asSession().directory)
    }

    @Test
    fun `projects decode with their sandboxes`() {
        val projects = openCodeJson.decodeFromString<List<ProjectPayload>>(fixture("projects.json"))
        assertTrue(projects.isNotEmpty())
        assertTrue(projects.all { it.worktree.isNotEmpty() })
    }

    @Test
    fun `session status decodes as a map of what each session is doing`() {
        val status = openCodeJson.decodeFromString<Map<String, SessionStatus>>(fixture("status.json"))
        assertEquals(setOf("busy"), status.values.map { it.type }.toSet())
    }

    @Test
    fun `a session with no title or time still decodes`() {
        val session = openCodeJson.decodeFromString<SessionPayload>("""{"id":"ses_bare"}""").asSession()
        assertEquals("ses_bare", session.title)
        assertEquals(0L, session.updatedAt)
        assertNull(session.parentId)
    }

    @Test
    fun `an event carries only the properties its type has`() {
        val idle = openCodeJson.decodeFromString<EventPayload>(
            """{"type":"session.idle","properties":{"sessionID":"ses_1"}}"""
        )
        assertEquals("ses_1", idle.properties.sessionId)
        assertNull(idle.properties.status)

        val busy = openCodeJson.decodeFromString<EventPayload>(
            """{"type":"session.status","properties":{"sessionID":"ses_1","status":{"type":"busy"}}}"""
        )
        assertEquals("busy", busy.properties.status?.type)
    }

    @Test
    fun `the bare array the proxy returns is not the envelope`() {
        val flattened = """[{"id":"ses_1"},{"id":"ses_2"}]"""
        assertTrue(flattened.trimStart().startsWith("["))
        val sessions = openCodeJson.decodeFromString<List<SessionPayload>>(flattened)
        assertEquals(listOf("ses_1", "ses_2"), sessions.map { it.id })
        assertNotNull(Json.parseToJsonElement(flattened))
    }

    /** A session backing off after a rate limit has not finished, and idle greyed Stop on it. */
    @Test
    fun `a status that is not idle is a session still working`() {
        val retry = openCodeJson.decodeFromString<EventPayload>(
            """{"type":"session.status","properties":{"sessionID":"ses_1","status":{"type":"retry"}}}""",
        )

        assertEquals("retry", retry.properties.status?.type)
    }

    @Test
    fun `the status that means finished says idle`() {
        val done = openCodeJson.decodeFromString<EventPayload>(
            """{"type":"session.status","properties":{"sessionID":"ses_1","status":{"type":"idle"}}}""",
        )

        assertEquals("idle", done.properties.status?.type)
    }
}
