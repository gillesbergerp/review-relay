package com.github.gillesbergerp.reviewrelay.backend.mcp

import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpVersion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which requests reach the endpoint at all, which is what the agent's whole connection rests on:
 * unsupported here is a 404 from the IDE's server, with nothing said about why.
 */
class McpEndpointTest {

    private val endpoint = McpEndpoint()

    private fun post(uri: String): FullHttpRequest =
        DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri)

    @Test
    fun `the url handed to the agent is the one served`() {
        assertTrue(endpoint.isSupported(post(McpEndpoint.PATH)))
    }

    @Test
    fun `a trailing slash or a query string is the same endpoint`() {
        assertTrue(endpoint.isSupported(post("${McpEndpoint.PATH}/")))
        assertTrue(endpoint.isSupported(post("${McpEndpoint.PATH}?session=1")))
    }

    @Test
    fun `another path on the same server is not ours`() {
        assertFalse(endpoint.isSupported(post("/api/about")))
        assertFalse(endpoint.isSupported(post("/api/review-relay")))
        assertFalse(endpoint.isSupported(post("/api/review-relay/mcpx")))
    }

    @Test
    fun `only a post carries a request`() {
        val get = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, McpEndpoint.PATH)
        assertFalse(endpoint.isSupported(get))
    }
}
