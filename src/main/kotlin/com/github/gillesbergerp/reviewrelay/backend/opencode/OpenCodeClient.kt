package com.github.gillesbergerp.reviewrelay.backend.opencode

import com.github.gillesbergerp.reviewrelay.util.json.Json
import com.github.gillesbergerp.reviewrelay.util.json.get
import com.github.gillesbergerp.reviewrelay.util.json.string
import com.intellij.openapi.diagnostic.Logger
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration

/** The one status that means the agent has stopped because it is done. */
private const val IDLE = "idle"

class OpenCodeClient(val endpoint: OpenCodeEndpoint) {

    fun ping(): Boolean = try {
        send(requestBuilder(uri("/path", null), PING_TIMEOUT).GET().build())
        true
    } catch (_: OpenCodeException) {
        false
    }

    /**
     * Sessions in [directory], or every session the server holds when it is null.
     *
     * Walks up to [pages] of the cursor the list is paged by. The OpenChamber proxy answers with the
     * bare array rather than the paged envelope, so there the first page is all there is.
     */
    fun sessions(directory: String?, pages: Int = 1): List<OpenCodeSession> {
        val found = mutableListOf<OpenCodeSession>()
        var cursor: String? = null
        repeat(pages.coerceAtLeast(1)) {
            val page = sessionPage(directory, cursor)
            found += page.data.map { it.asSession() }
            cursor = page.cursor.next ?: return found
        }
        return found
    }

    private fun sessionPage(directory: String?, cursor: String?): SessionPage {
        val params = mutableListOf("limit" to PAGE_SIZE.toString())
        cursor?.let { params.add("cursor" to it) }
        val body = get("/api/session", directory, *params.toTypedArray()).trimStart()
        // The proxy answers with the bare array the envelope wraps.
        return if (body.startsWith("[")) {
            SessionPage(data = openCodeJson.decodeFromString(body))
        } else {
            openCodeJson.decodeFromString(body)
        }
    }

    /** The worktree and sandboxes OpenCode groups together, which is how a review of one finds the others. */
    fun projects(): List<OpenCodeProject> =
        openCodeJson.decodeFromString<List<ProjectPayload>>(get("/project", null))
            .map { OpenCodeProject(it.worktree, it.sandboxes) }

    fun createSession(directory: String, title: String): OpenCodeSession {
        val body = post("/session", directory, Json.write(mapOf("title" to title)))
        val created = runCatching { openCodeJson.decodeFromString<SessionPayload>(body) }.getOrNull()
            ?: throw OpenCodeException("OpenCode did not return a session")
        return created.asSession().let {
            it.copy(
                title = it.title.ifEmpty { title },
                directory = it.directory.ifEmpty { directory },
                updatedAt = it.updatedAt.takeIf { at -> at > 0 } ?: System.currentTimeMillis(),
            )
        }
    }

    /** Anything that is not idle: a session backing off after a rate limit is not finished either. */
    fun busySessions(directory: String): Set<String> =
        openCodeJson.decodeFromString<Map<String, SessionStatus>>(get("/session/status", directory))
            .filterValues { it.type.isNotEmpty() && it.type != IDLE }
            .keys

    fun promptAsync(sessionId: String, directory: String, body: Map<String, Any?>) {
        // OpenCode reads every attached range before it answers, so this is not a quick call.
        post("/session/" + enc(sessionId) + "/prompt_async", directory, Json.write(body), PROMPT_TIMEOUT)
    }

    /**
     * How many times [needles] appear in the session's most recent messages.
     *
     * Counted rather than merely found: a thread's handle lasts its whole life, so a follow-up round
     * would have read as delivered on the round before it, before the post had landed at all.
     */
    fun recentMessageMentions(sessionId: String, directory: String, needles: Collection<String>): Int {
        if (needles.isEmpty()) return 0
        val messages = get("/session/" + enc(sessionId) + "/message", directory, "limit" to "4")
        return mentionsIn(messages, needles)
    }

    fun abort(sessionId: String, directory: String) {
        post("/session/" + enc(sessionId) + "/abort", directory, "{}")
    }

    fun subscribe(directory: String, onEvent: (OpenCodeEvent) -> Unit): EventSubscription {
        val request = requestBuilder(uri("/event", directory))
            // The timeout is for the headers, not the stream: a day of it only meant a server that
            // accepts the connection and never answers held the refresh for a day.
            .header("Accept", "text/event-stream")
            .GET()
            .build()
        val response = try {
            HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: Exception) {
            throw OpenCodeException("Cannot open the OpenCode event stream: ${e.message}", e)
        }
        if (response.statusCode() != 200) {
            response.body().close()
            throw OpenCodeException("OpenCode event stream returned HTTP ${response.statusCode()}")
        }
        return EventSubscription(response.body(), onEvent)
    }

    private fun get(path: String, directory: String?, vararg params: Pair<String, String>): String =
        send(requestBuilder(uri(path, directory, *params)).GET().build())

    private fun post(
        path: String,
        directory: String?,
        body: String,
        timeout: Duration = REQUEST_TIMEOUT,
    ): String = send(
        requestBuilder(uri(path, directory), timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
    )

    private fun send(request: HttpRequest): String {
        val response = try {
            HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (e: HttpTimeoutException) {
            throw OpenCodeTimeout("OpenCode did not answer in time for ${request.uri().path}", e)
        } catch (e: IOException) {
            throw OpenCodeException("Cannot reach OpenCode at ${endpoint.baseUrl}: ${e.message}", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw OpenCodeException("Interrupted while talking to OpenCode", e)
        }
        if (response.statusCode() !in 200..299) {
            throw OpenCodeException(
                "OpenCode returned HTTP ${response.statusCode()} for ${request.uri().path}"
            )
        }
        return response.body()
    }

    private fun requestBuilder(uri: URI, timeout: Duration = REQUEST_TIMEOUT): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(uri).timeout(timeout)
        endpoint.authHeader?.let { builder.header("Authorization", it) }
        return builder
    }

    private fun uri(path: String, directory: String?, vararg params: Pair<String, String>): URI {
        val all = listOfNotNull(directory?.takeIf { it.isNotBlank() }?.let { "directory" to it }) + params
        val query = all.joinToString("&", prefix = "?") { (name, value) -> name + "=" + enc(value) }
        return URI.create(endpoint.baseUrl.trimEnd('/') + path + query.takeIf { all.isNotEmpty() }.orEmpty())
    }

    companion object {
        private val PING_TIMEOUT: Duration = Duration.ofSeconds(3)
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(20)
        private val PROMPT_TIMEOUT: Duration = Duration.ofMinutes(3)

        /** The server's own default is 100; asking for more costs one round trip instead of two. */
        private const val PAGE_SIZE = 200

        private val HTTP: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

        fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

        /** Counted over the raw body: the shape of a message is the server's to change, its text is not. */
        fun mentionsIn(messages: String, needles: Collection<String>): Int =
            needles.filter { it.isNotEmpty() }
                .sumOf { needle -> messages.windowed(needle.length).count { it == needle } }
    }
}

/** Reads an OpenCode SSE stream on its own daemon thread until [close]. */
class EventSubscription(
    private val stream: InputStream,
    private val onEvent: (OpenCodeEvent) -> Unit,
) : AutoCloseable {

    @Volatile
    private var closed = false

    private val thread = Thread({ run() }, "OpenCode events").apply {
        isDaemon = true
        start()
    }

    /**
     * Whether events are still arriving.
     *
     * The reader ends on its own whenever the server restarts, and a subscription kept as live
     * because the field is not null is one nothing will ever replace.
     */
    val isAlive: Boolean get() = !closed && thread.isAlive

    private fun run() {
        try {
            stream.bufferedReader().forEachLine { line ->
                if (!closed && line.startsWith("data:")) {
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isNotEmpty()) parse(payload)?.let { deliver(it) }
                }
            }
        } catch (e: Exception) {
            if (!closed) LOG.info("OpenCode event stream ended: ${e.message}")
        }
    }

    /** One handler that throws is one event missed, never the end of the stream. */
    private fun deliver(event: OpenCodeEvent) {
        runCatching { onEvent(event) }
            .onFailure { if (!closed) LOG.info("Could not handle an OpenCode event: ${it.message}") }
    }

    private fun parse(payload: String): OpenCodeEvent? {
        // A payload this build cannot read is one event missed, never a broken stream.
        val event = runCatching { openCodeJson.decodeFromString<EventPayload>(payload) }.getOrNull() ?: return null
        val properties = event.properties
        return when (event.type) {
            "server.connected" -> OpenCodeEvent.Connected
            "session.idle" -> properties.sessionId?.let { OpenCodeEvent.SessionIdle(it) }
            // Not "busy" alone: a session in provider backoff reports retry, and reading that as
            // finished greyed Stop and said idle while the agent was still going.
            "session.status" -> properties.sessionId?.let {
                val type = properties.status?.type.orEmpty()
                if (type == IDLE) OpenCodeEvent.SessionIdle(it) else OpenCodeEvent.SessionBusy(it, true)
            }
            "file.edited" -> properties.file?.let { OpenCodeEvent.FileEdited(it) }
            "session.diff" -> properties.sessionId?.let { OpenCodeEvent.SessionDiff(it) }
            else -> null
        }
    }

    override fun close() {
        closed = true
        try {
            stream.close()
        } catch (_: Exception) {
        }
        thread.interrupt()
    }

    private companion object {
        val LOG = Logger.getInstance(EventSubscription::class.java)
    }
}
