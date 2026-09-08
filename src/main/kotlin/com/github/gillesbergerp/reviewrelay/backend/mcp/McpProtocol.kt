package com.github.gillesbergerp.reviewrelay.backend.mcp

import com.github.gillesbergerp.reviewrelay.util.json.Json
import com.github.gillesbergerp.reviewrelay.util.json.bool
import com.github.gillesbergerp.reviewrelay.util.json.get
import com.github.gillesbergerp.reviewrelay.util.json.int
import com.github.gillesbergerp.reviewrelay.util.json.string
import com.github.gillesbergerp.reviewrelay.review.ReviewText
import com.github.gillesbergerp.reviewrelay.review.model.CommentType
import com.github.gillesbergerp.reviewrelay.review.service.Proposed
import com.github.gillesbergerp.reviewrelay.review.service.Recorded
import com.github.gillesbergerp.reviewrelay.review.service.recordProposal
import com.github.gillesbergerp.reviewrelay.review.service.reason
import com.github.gillesbergerp.reviewrelay.review.service.recordReply
import com.google.gson.JsonElement
import com.intellij.openapi.project.Project

/** The review tools as JSON-RPC, with no notion of how the bytes arrived. */
internal object McpProtocol {

    /** The answer to send back, or null for a notification, which is never answered. */
    fun respond(
        body: String,
        version: String,
        lookup: (String?) -> ProjectLookup.Result = ProjectLookup::find,
    ): String? {
        val payload = Json.parseOrNull(body)
            ?: return envelope(null, Outcome.Failure(PARSE_ERROR, "Malformed JSON"))

        val request = Request(payload)
        val outcome = when (request.method) {
            "initialize" -> Outcome.Success(initialize(request.params, version))
            "ping" -> Outcome.Success(emptyMap<String, Any?>())
            "tools/list" -> Outcome.Success(ToolList(TOOLS.map { it.advertised() }))
            "tools/call" -> Outcome.Success(call(request.params, lookup))
            else ->
                if (request.method?.startsWith("notifications/") == true) return null
                else Outcome.Failure(METHOD_NOT_FOUND, "Unsupported method ${request.method}")
        }
        if (request.id == null) return null
        return envelope(request.id, outcome)
    }

    /**
     * A request as far as this server reads one.
     *
     * [id] stays a raw element: JSON-RPC lets a client choose a string, a number or null, and the
     * answer has to carry back what it sent rather than what we would have preferred.
     */
    private class Request(payload: JsonElement?) {
        val id: JsonElement? = payload["id"]
        val method: String? = payload["method"].string
        val params: JsonElement? = payload["params"]
    }

    private sealed interface Outcome {
        data class Success(val result: Any?) : Outcome
        data class Failure(val code: Int, val message: String) : Outcome
    }

    private data class Envelope(
        val jsonrpc: String = "2.0",
        val id: JsonElement?,
        val result: Any? = null,
        val error: Failed? = null,
    )

    private data class Failed(val code: Int, val message: String)

    private data class Initialized(
        val protocolVersion: String,
        val capabilities: Map<String, Any?>,
        val serverInfo: ServerInfo,
    )

    private data class ServerInfo(val name: String, val version: String)

    private data class ToolList(val tools: List<Advertised>)

    private data class Advertised(val name: String, val description: String, val inputSchema: McpSchema.Schema)

    /** A tool result, which carries a refusal as content the agent reads rather than as an error. */
    private data class ToolResult(val content: List<Content>, val isError: Boolean? = null)

    private data class Content(val type: String, val text: String)

    private fun initialize(params: JsonElement?, version: String) = Initialized(
        // Echo the version asked for: this server is tools-only and the shape has not moved.
        protocolVersion = params["protocolVersion"].string ?: PROTOCOL,
        capabilities = mapOf("tools" to emptyMap<String, Any?>()),
        serverInfo = ServerInfo(name = "Review Relay", version = version),
    )

    private fun call(
        params: JsonElement?,
        lookup: (String?) -> ProjectLookup.Result,
    ): ToolResult {
        val name = params["name"].string
        val tool = TOOLS.firstOrNull { it.name == name } ?: return refused("Unknown tool $name.")
        val arguments = params["arguments"]
        // Read as content rather than let it out of the handler: an exception from here reaches
        // netty, which closes the connection, leaving the agent no answer to read at all.
        return runCatching {
            withProject(arguments["directory"].string, lookup) { project -> tool.run(project, arguments) }
        }.getOrElse { refused("$name could not be run: ${reason(it)}") }
    }

    private fun comments(project: Project, arguments: JsonElement?): ToolResult {
        val asked = CommentsArguments(
            directory = arguments["directory"].string,
            includeClosed = arguments["includeClosed"].bool ?: false,
        )
        return text(ReviewJson.comments(project, asked.includeClosed))
    }

    private fun comment(project: Project, arguments: JsonElement?): ToolResult {
        val asked = CommentArguments(
            directory = arguments["directory"].string,
            id = arguments["id"].string?.trim(),
        )
        val handle = asked.id
        if (handle.isNullOrEmpty()) return refused("An id is required.")
        return ReviewJson.comment(project, handle)?.let { text(it) }
            ?: refused("No comment with id $handle in this review.")
    }

    private fun reply(project: Project, arguments: JsonElement?): ToolResult {
        val asked = ReplyArguments(
            directory = arguments["directory"].string,
            id = arguments["id"].string?.trim(),
            note = arguments["note"].string?.trim(),
        )
        if (asked.id.isNullOrEmpty() || asked.note.isNullOrEmpty()) {
            return refused("Both id and note are required.")
        }
        return when (val outcome = recordReply(project, asked.id, asked.note)) {
            is Recorded.NoSuchComment -> refused("No comment with id ${outcome.handle} in this review.")
            Recorded.Ok -> text("Recorded your answer on ${asked.id}.")
        }
    }

    private fun propose(project: Project, arguments: JsonElement?): ToolResult {
        val asked = ProposeArguments(
            directory = arguments["directory"].string,
            file = arguments["file"].string?.trim(),
            lineStart = arguments["lineStart"].int,
            lineEnd = arguments["lineEnd"].int,
            type = arguments["type"].string?.trim(),
            text = arguments["text"].string?.trim(),
            agent = arguments["agent"].string?.trim(),
        )
        if (asked.file.isNullOrEmpty() || asked.text.isNullOrEmpty()) {
            return refused("Both file and text are required.")
        }
        val type = CommentType.entries.firstOrNull { it.name.equals(asked.type, ignoreCase = true) }
            ?: CommentType.FIX
        val outcome = recordProposal(
            project = project,
            path = asked.file,
            lineStart = asked.lineStart,
            lineEnd = asked.lineEnd,
            type = type,
            text = asked.text,
            agent = asked.agent?.takeIf { it.isNotEmpty() } ?: "an agent",
        )
        return when (outcome) {
            is Proposed.NoSuchFile ->
                refused("No file at ${outcome.path} in this project. Give a path from the project root.")
            is Proposed.Enough ->
                refused("This review already holds ${outcome.cap} proposals. Stop and let the reviewer read them.")
            Proposed.AlreadyKnown -> text("Already proposed; nothing added.")
            is Proposed.Ok -> text("Proposed. The reviewer decides whether it becomes a comment.")
        }
    }

    private fun withProject(
        directory: String?,
        lookup: (String?) -> ProjectLookup.Result,
        action: (Project) -> ToolResult,
    ): ToolResult = when (val found = lookup(directory)) {
        is ProjectLookup.Result.Found -> action(found.project)
        ProjectLookup.Result.NoneOpen -> refused("No project is open in the IDE.")
        is ProjectLookup.Result.Ambiguous -> refused(
            "Several projects are open, so say which by passing the directory you are working in " +
                "as `directory`. Open: ${found.open.joinToString(", ")}"
        )
    }

    private fun envelope(id: JsonElement?, outcome: Outcome): String = Json.write(
        when (outcome) {
            is Outcome.Success -> Envelope(id = id, result = outcome.result)
            is Outcome.Failure -> Envelope(id = id, error = Failed(outcome.code, outcome.message))
        }
    )

    private fun text(body: String) = ToolResult(listOf(Content("text", body)))

    /** Reported as a tool result rather than a protocol error, so the agent reads it and retries. */
    private fun refused(message: String) = ToolResult(listOf(Content("text", message)), isError = true)

    private const val PROTOCOL = "2025-06-18"
    private const val PARSE_ERROR = -32700
    private const val METHOD_NOT_FOUND = -32601

    private const val DIRECTORY =
        "Absolute path of the directory you are working in. A git worktree resolves to the project " +
            "it belongs to, so either the worktree or the main checkout works. Required only when " +
            "several projects are open in the IDE."

    private const val HANDLE = "The comment's id, as review_comments gave it."

    /**
     * A tool as the agent sees it and as this server runs it, which cannot drift apart because
     * they are the same object: what tools/list advertises is what tools/call dispatches to, and
     * the schema is read off the class the arguments are parsed into.
     */
    private class McpTool(
        val name: String,
        val description: String,
        val arguments: Class<*>,
        val run: (Project, JsonElement?) -> ToolResult,
    ) {
        fun advertised() = Advertised(name, description, McpSchema.of(arguments))
    }

    private data class CommentsArguments(
        @field:McpSchema.Argument(DIRECTORY)
        val directory: String?,
        @field:McpSchema.Argument(
            "Include comments the reviewer already closed. Off by default; the open ones are the work."
        )
        val includeClosed: Boolean,
    )

    private data class CommentArguments(
        @field:McpSchema.Argument(DIRECTORY)
        val directory: String?,
        @field:McpSchema.Argument(HANDLE, required = true)
        val id: String?,
    )

    private data class ProposeArguments(
        @field:McpSchema.Argument(DIRECTORY)
        val directory: String?,
        @field:McpSchema.Argument(
            "Path of the file, from the project root or absolute.",
            required = true,
        )
        val file: String?,
        @field:McpSchema.Argument("First line it is about, counting from 1. Leave out to mean the whole file.")
        val lineStart: Int?,
        @field:McpSchema.Argument("Last line, when it is about a range.")
        val lineEnd: Int?,
        @field:McpSchema.Argument("FIX, CONSIDER or QUESTION. ${ReviewText.LEGEND} Defaults to FIX.")
        val type: String?,
        @field:McpSchema.Argument(
            "What you found, in Markdown. A fenced code block is read as a suggested replacement.",
            required = true,
        )
        val text: String?,
        @field:McpSchema.Argument("Your name, so the reviewer can see who proposed it.")
        val agent: String?,
    )

    private data class ReplyArguments(
        @field:McpSchema.Argument(DIRECTORY)
        val directory: String?,
        @field:McpSchema.Argument(HANDLE, required = true)
        val id: String?,
        @field:McpSchema.Argument("What you did about it, or why you did not.", required = true)
        val note: String?,
    )

    private val TOOLS = listOf(
        McpTool(
            name = "review_comments",
            description =
                "The reviewer's published review: every comment with its id, file, line range, " +
                    "text, and any suggested replacement. Call this when asked to address a review.",
            arguments = CommentsArguments::class.java,
            run = ::comments,
        ),
        McpTool(
            name = "review_comment",
            description =
                "One comment of the published review by its id, with everything said on it since. " +
                    "Call this to re-read a comment you are working on, rather than fetching the " +
                    "whole review again.",
            arguments = CommentArguments::class.java,
            run = ::comment,
        ),
        McpTool(
            name = "review_propose",
            description =
                "Propose one review comment on a place in the code. It is put in front of the " +
                    "reviewer to add to their review or dismiss: it reaches no other agent, changes " +
                    "no code, and is not part of the review until they say so. Call it once per " +
                    "finding, and only when asked to review something.",
            arguments = ProposeArguments::class.java,
            run = ::propose,
        ),
        McpTool(
            name = "review_reply",
            description =
                "Answer one review comment. Say what you changed, or why you did not. Call it once " +
                    "per comment you address; it records your answer beside the comment in the " +
                    "reviewer's IDE and changes no code.",
            arguments = ReplyArguments::class.java,
            run = ::reply,
        ),
    )
}
