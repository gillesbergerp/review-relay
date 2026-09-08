package com.github.gillesbergerp.reviewrelay.backend.claude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClaudeSessionsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var sessions: File

    private fun write(
        pid: Int,
        cwd: File,
        name: String = "desktop-$pid",
        status: String = "idle",
        waitingFor: String? = null,
        protocol: Int = 1,
        kind: String = "interactive",
        token: String? = "tok-$pid",
        updatedAt: Long = pid.toLong(),
    ) {
        if (!::sessions.isInitialized) sessions = temp.newFolder("sessions")
        val path = cwd.path.replace("\\", "\\\\")
        File(sessions, "$pid.json").writeText(
            """
            {"pid":$pid,"sessionId":"ses-$pid","cwd":"$path","peerProtocol":$protocol,
             "kind":"$kind","messagingSocketPath":"\\\\.\\pipe\\cc-$pid","name":"$name",
             "status":"$status","updatedAt":$updatedAt${waitingFor?.let { ""","waitingFor":"$it"""" } ?: ""}}
            """.trimIndent()
        )
        if (token != null) {
            File(sessions, "$pid.abc123.key").writeText("""{"peerToken":"$token"}""")
        }
    }

    private fun checkout(name: String): File =
        temp.newFolder(name).also { File(it, ".git").mkdir() }

    private fun worktree(name: String, of: File): File =
        temp.newFolder(name).also {
            File(it, ".git").writeText(
                "gitdir: " + File(of, ".git/worktrees/$name").path.replace('\\', '/')
            )
        }

    @Test
    fun `a session in the project is found, with its token`() {
        val repo = checkout("app")
        write(1, repo)
        val found = ClaudeSessions.inProject(repo.path, sessions).single()
        assertEquals("ses-1", found.id.value)
        assertEquals("tok-1", found.token)
        assertEquals("desktop-1", found.name)
    }

    @Test
    fun `a session running in a worktree is found from the checkout`() {
        val repo = checkout("app")
        val tree = worktree("app-feature", repo)
        write(2, tree)
        assertEquals("ses-2", ClaudeSessions.inProject(repo.path, sessions).single().id.value)
    }

    @Test
    fun `a session in an unrelated project is not found`() {
        val repo = checkout("app")
        write(3, checkout("other"))
        assertTrue(ClaudeSessions.inProject(repo.path, sessions).isEmpty())
    }

    @Test
    fun `a session announcing another protocol is left alone`() {
        val repo = checkout("app")
        write(4, repo, protocol = 2)
        assertTrue(ClaudeSessions.all(sessions).isEmpty())
    }

    @Test
    fun `a non-interactive session is not offered`() {
        val repo = checkout("app")
        write(5, repo, kind = "background")
        assertTrue(ClaudeSessions.all(sessions).isEmpty())
    }

    @Test
    fun `a file that is not json at all costs only that session`() {
        val repo = checkout("app")
        write(9, repo)
        File(sessions, "truncated.json").writeText("""{"pid":10,"sessionId":"ses-10","cwd":""")
        assertEquals(listOf("ses-9"), ClaudeSessions.all(sessions).map { it.id.value })
    }

    @Test
    fun `a session missing a field it cannot be built without is dropped, not half-built`() {
        if (!::sessions.isInitialized) sessions = temp.newFolder("sessions")
        File(sessions, "11.json").writeText(
            """{"pid":11,"peerProtocol":1,"kind":"interactive","name":"no cwd here"}"""
        )
        File(sessions, "11.abc.key").writeText("""{"peerToken":"tok-11"}""")
        assertTrue(ClaudeSessions.all(sessions).isEmpty())
    }

    @Test
    fun `a field this build has never heard of does not lose the session`() {
        val repo = checkout("app")
        val path = repo.path.replace("\\", "\\\\")
        if (!::sessions.isInitialized) sessions = temp.newFolder("sessions")
        File(sessions, "12.json").writeText(
            """{"pid":12,"sessionId":"ses-12","cwd":"$path","peerProtocol":1,"kind":"interactive",
               "messagingSocketPath":"\\\\.\\pipe\\cc-12","somethingNewInClaude":{"a":[1,2]}}"""
        )
        File(sessions, "12.abc.key").writeText("""{"peerToken":"tok-12"}""")
        assertEquals(listOf("ses-12"), ClaudeSessions.all(sessions).map { it.id.value })
    }

    @Test
    fun `a session with no token beside it is unusable and dropped`() {
        val repo = checkout("app")
        write(6, repo, token = null)
        assertTrue(ClaudeSessions.all(sessions).isEmpty())
    }

    @Test
    fun `busy is read from the status the session publishes`() {
        val repo = checkout("app")
        write(7, repo, status = "busy")
        assertTrue(ClaudeSessions.all(sessions).single().busy)
        write(8, repo, status = "waiting")
        assertTrue(ClaudeSessions.all(sessions).none { it.id.value == "ses-8" && it.busy })
    }

    @Test
    fun `a session stopped on a question is waiting, not idle`() {
        val repo = checkout("app")
        write(20, repo, status = "waiting", waitingFor = "input needed")
        val found = ClaudeSessions.all(sessions).single()
        assertTrue(found.waiting)
        assertEquals("input needed", found.waitingFor)
        assertTrue(!found.busy)
    }

    @Test
    fun `an idle session is neither busy nor waiting`() {
        val repo = checkout("app")
        write(21, repo, status = "idle")
        val found = ClaudeSessions.all(sessions).single()
        assertTrue(!found.waiting)
        assertTrue(!found.busy)
        assertNull(found.waitingFor)
    }

    @Test
    fun `the most recently updated session comes first`() {
        val repo = checkout("app")
        write(9, repo, updatedAt = 100)
        write(10, repo, updatedAt = 900)
        assertEquals(listOf("ses-10", "ses-9"), ClaudeSessions.all(sessions).map { it.id.value })
    }
}
