package com.github.gillesbergerp.reviewrelay.backend.mcp

import com.github.gillesbergerp.reviewrelay.util.localUrl
import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.extensions.PluginId
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.util.CharsetUtil
import org.jetbrains.ide.BuiltInServerManager
import org.jetbrains.ide.HttpRequestHandler
import org.jetbrains.io.response
import org.jetbrains.io.responseStatus
import org.jetbrains.io.send

/**
 * Serves the review over MCP, on the IDE's own web server.
 *
 * Our own endpoint rather than the platform's toolset extension point, which publishes the whole IDE
 * tool surface to whoever connects and binds a call to a project by exact path, never a worktree.
 */
class McpEndpoint : HttpRequestHandler() {

    // checkPrefix matches the URI from its second character, so the prefix must not carry the slash.
    override fun isSupported(request: FullHttpRequest): Boolean =
        request.method() == HttpMethod.POST && checkPrefix(request.uri(), PATH.removePrefix("/"))

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
    ): Boolean {
        val body = request.content().toString(CharsetUtil.UTF_8)
        val answer = McpProtocol.respond(body, version())
            ?: return accepted(request, context)

        // Streamable HTTP lets the server answer a POST either way, and JSON is the whole of what a
        // tools-only server has to say.
        val payload = Unpooled.copiedBuffer(answer, Charsets.UTF_8)
        response("application/json; charset=utf-8", payload).send(context.channel(), request)
        return true
    }

    /** A notification is acknowledged and nothing more; a body here would be parsed as an answer. */
    private fun accepted(request: FullHttpRequest, context: ChannelHandlerContext): Boolean {
        responseStatus(HttpResponseStatus.ACCEPTED, HttpUtil.isKeepAlive(request), context.channel())
        return true
    }

    private fun version(): String =
        PluginManager.getInstance().findEnabledPlugin(PluginId.getId(PLUGIN_ID))?.version ?: "0"

    companion object {

        const val PATH = "/api/review-relay/mcp"

        fun url(): String = localUrl(BuiltInServerManager.getInstance().port) + PATH

        private const val PLUGIN_ID = "com.github.gillesbergerp.reviewrelay"
    }
}
