package com.github.gillesbergerp.reviewrelay.review.export

import com.github.gillesbergerp.reviewrelay.util.json.Json
import com.github.gillesbergerp.reviewrelay.util.json.get
import com.github.gillesbergerp.reviewrelay.util.json.items
import com.github.gillesbergerp.reviewrelay.util.json.string
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * The GitHub CLI, run where the repository is.
 *
 * Through `gh` rather than the REST API directly because it already holds the reviewer's
 * credentials: nothing here stores a token, and a plugin that asked for one would be asking for
 * push rights to read a pull request.
 */
internal object Gh {

    class Failed(val output: String) : Exception(output) {

        /** gh answers a failure with a banner and a hint, of which the first line is the reason. */
        val said: String get() = output.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "gh said nothing"
    }

    /** Blocking; call off the EDT. [input] is written to stdin, for `gh api --input -`. */
    fun run(directory: String, vararg args: String, input: String? = null): String {
        val ran = execute(directory, args.toList(), input)
        if (ran.code != 0) throw Failed(reason(ran).ifBlank { "gh exited ${ran.code}" })
        return ran.out
    }

    /**
     * Why it failed, from both streams.
     *
     * `gh api` puts the status on stderr and the API's own account of the refusal - which field,
     * and what was wrong with it - on stdout. Reading only stderr left a 422 as the bare words
     * "Unprocessable Entity", which names neither the comment at fault nor the reason.
     */
    private fun reason(ran: Ran): String {
        val status = ran.said.trim()
        val explained = apiMessage(ran.out) ?: return status
        return if (status.isBlank()) explained else "$status - $explained"
    }

    private fun apiMessage(out: String): String? {
        val body = Json.parseOrNull(out) ?: return null
        val message = body["message"].string?.takeIf { it.isNotBlank() }
        val fields = body["errors"].items.mapNotNull { error ->
            val field = error["field"].string ?: return@mapNotNull error["message"].string
            listOfNotNull(field, error["code"].string).joinToString(": ")
        }
        return listOfNotNull(message, fields.takeIf { it.isNotEmpty() }?.joinToString("; "))
            .joinToString(" - ")
            .takeIf { it.isNotBlank() }
    }

    /**
     * Blocking; call off the EDT. The exit code alone.
     *
     * What gh wrote is dropped rather than returned: gh's prose is not an interface, and a question
     * whose answer is the exit code has no business carrying it anywhere it could be repeated.
     */
    fun exitCode(directory: String, vararg args: String): Int = execute(directory, args.toList(), null).code

    private class Ran(val code: Int, val out: String, val said: String)

    /**
     * Through the platform's own runner, for two things a bare ProcessBuilder does not give.
     *
     * It applies the login shell's PATH, without which an IDE started from the Dock cannot find a
     * gh that is installed; and its timeout covers the read, where waiting only after reading the
     * whole of stdout meant a gh stuck on the network was never timed out at all.
     */
    private fun execute(directory: String, args: List<String>, input: String?): Ran {
        val command = GeneralCommandLine(listOf("gh") + args)
            .withWorkingDirectory(Path.of(directory))
            .withCharset(Charsets.UTF_8)
        val handler = CapturingProcessHandler(command)
        // gh acts on a body from stdin only once it is closed, and the process is already running.
        input?.let { handler.processInput.write(it.toByteArray(Charsets.UTF_8)) }
        handler.processInput.close()

        val done = handler.runProcess(TIMEOUT.inWholeMilliseconds.toInt(), true)
        if (done.isTimeout) throw Failed("gh ${args.firstOrNull()} did not answer in $TIMEOUT")
        // Kept apart from stdout: gh writes its notices to stderr, and they were parsed as JSON.
        return Ran(done.exitCode, done.stdout, done.stderr)
    }

    /** Long enough for a paginated API call over a slow link, short enough not to hang a task. */
    private val TIMEOUT = 60.seconds
}
