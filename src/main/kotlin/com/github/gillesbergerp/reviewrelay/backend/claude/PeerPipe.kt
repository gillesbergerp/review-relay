package com.github.gillesbergerp.reviewrelay.backend.claude

import com.github.gillesbergerp.reviewrelay.util.json.Json
import com.intellij.openapi.util.SystemInfo
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Path

/**
 * Hands a message to a running Claude Code session over the socket it advertises.
 *
 * Two newline-terminated objects, auth first: the session drops a connection that has not
 * authenticated, and one that sends no complete line within thirty seconds, so nothing is opened
 * until the whole payload is ready.
 */
object PeerPipe {

    class Undeliverable(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    fun send(session: ClaudeSessions.Session, content: String) {
        // Nothing here is answered, so a session that has gone is the one failure worth catching
        // before the write: on Windows a pipe with no listener takes the bytes and drops them.
        if (!alive(session.pid)) throw Undeliverable("that session is no longer running")
        val lines = Json.write(mapOf("type" to "auth", "token" to session.token)) + "\n" +
            Json.write(
                mapOf(
                    "type" to "user",
                    "message" to mapOf("role" to "user", "content" to content),
                    "priority" to "next",
                    "from" to "review-relay",
                )
            ) + "\n"
        write(session.pipe, lines.toByteArray(Charsets.UTF_8))
    }

    /** True when nothing can be told either way, which is not a reason to refuse to send. */
    private fun alive(pid: Int): Boolean =
        runCatching { ProcessHandle.of(pid.toLong()).map { it.isAlive }.orElse(false) }.getOrDefault(true)

    private fun write(path: String, payload: ByteArray) {
        try {
            if (SystemInfo.isWindows) writeToPipe(path, payload) else writeToSocket(path, payload)
        } catch (e: Undeliverable) {
            throw e
        } catch (e: Exception) {
            throw Undeliverable(e.message ?: e.javaClass.simpleName, e)
        }
    }

    /** A Windows named pipe is opened as a file; there is no socket to connect to. */
    private fun writeToPipe(path: String, payload: ByteArray) {
        RandomAccessFile(path, "rw").use { it.write(payload) }
    }

    private fun writeToSocket(path: String, payload: ByteArray) {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(Path.of(path)))
            val buffer = ByteBuffer.wrap(payload)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
    }
}
