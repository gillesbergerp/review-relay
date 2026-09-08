package com.github.gillesbergerp.reviewrelay.backend.opencode

import com.github.gillesbergerp.reviewrelay.util.json.Json
import com.github.gillesbergerp.reviewrelay.util.json.bool
import com.github.gillesbergerp.reviewrelay.util.json.get
import com.github.gillesbergerp.reviewrelay.util.json.int
import com.github.gillesbergerp.reviewrelay.util.json.string
import com.google.gson.JsonElement
import com.github.gillesbergerp.reviewrelay.util.LOOPBACK
import com.github.gillesbergerp.reviewrelay.util.localUrl
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit

data class DiscoveryOptions(
    val explicitUrl: String? = null,
    val username: String? = null,
    val password: String? = null,
)

object ServerDiscovery {

    private val LOG = Logger.getInstance(ServerDiscovery::class.java)
    private const val DEFAULT_PORT = 4096

    private val HTTP: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    fun discover(options: DiscoveryOptions): OpenCodeEndpoint? {
        // Once for the whole walk: deriving it can spawn a registry read, and every candidate below
        // used to ask for it again - five times over while the server is down and nothing answers.
        val auth = authHeader(options)
        options.explicitUrl?.takeIf { it.isNotBlank() }?.let { url ->
            return OpenCodeEndpoint(url.trim().trimEnd('/'), auth, "configured")
        }
        // The proxy comes last of the local options: it is the only one that needs no password, and
        // the only one that cannot answer the paged session API as OpenCode itself does.
        val chamber = openChamber()
        return openCodeItself(chamber, auth)
            ?: managedServer(auth)
            ?: openChamberProxy(chamber)
            ?: advertisedServer(auth)
            ?: defaultServer(auth)
    }

    /** The desktop app's health, which names both the proxy port and the server behind it. */
    private fun openChamber(): OpenChamber? {
        val port = readJson(File(openChamberConfigDir(), "settings.json"))["desktopLocalPort"].int ?: return null
        val health = Json.parseOrNull(httpGet("${localUrl(port)}/health").orEmpty()) ?: return null
        if (health["status"].string != "ok") return null
        if (health["isOpenCodeReady"].bool == false) return null
        return OpenChamber(port, health)
    }

    /**
     * The OpenCode server the desktop app is running, reached directly.
     *
     * Preferred over the proxy, which flattens the paged session list into one unfiltered page and
     * drops the parameters that scope it. Needs the server password, and falls through without it.
     */
    private fun openCodeItself(chamber: OpenChamber?, auth: String?): OpenCodeEndpoint? {
        if (chamber == null || chamber.health["openCodeRunning"].bool == false) return null
        val port = chamber.health["openCodePort"].int ?: return null
        val endpoint = OpenCodeEndpoint(localUrl(port), auth, "opencode on port $port")
        return endpoint.takeIf { OpenCodeClient(it).ping() }
    }

    /** The same API re-exposed under /api without auth, for when the password is not to be had. */
    private fun openChamberProxy(chamber: OpenChamber?): OpenCodeEndpoint? {
        if (chamber == null) return null
        return OpenCodeEndpoint(
            "${localUrl(chamber.port)}/api",
            null,
            "OpenChamber on port ${chamber.port}",
        )
    }

    private class OpenChamber(val port: Int, val health: JsonElement)

    /** Servers OpenChamber spawned itself: reachable directly, but behind basic auth. */
    private fun managedServer(auth: String?): OpenCodeEndpoint? {
        val dir = File(openChamberConfigDir(), "managed-opencode")
        val ports = (dir.listFiles { f -> f.extension == "json" } ?: return null)
            .sortedByDescending { it.lastModified() }
            .mapNotNull { readJson(it)["port"].int }
        for (port in ports) {
            val endpoint = OpenCodeEndpoint(localUrl(port), auth, "managed on port $port")
            if (OpenCodeClient(endpoint).ping()) return endpoint
        }
        return null
    }

    /** A server started with --mdns, which is the only kind that says where it is. */
    private fun advertisedServer(auth: String?): OpenCodeEndpoint? =
        MdnsLookup.find().firstNotNullOfOrNull { found ->
            // mDNS is answered from the interface the server bound, which for one on this machine is
            // some virtual adapter rather than loopback; the same port is reachable on both.
            listOf(LOOPBACK, found.host).firstNotNullOfOrNull { host ->
                OpenCodeEndpoint(
                    "http://$host:${found.port}",
                    auth,
                    "announced on port ${found.port}",
                ).takeIf { OpenCodeClient(it).ping() }
            }
        }

    /** Only ever finds a server started with an explicit --port: the default is a port of its own choosing. */
    private fun defaultServer(auth: String?): OpenCodeEndpoint? {
        val endpoint = OpenCodeEndpoint(localUrl(DEFAULT_PORT), auth, "opencode serve")
        return endpoint.takeIf { OpenCodeClient(it).ping() }
    }

    fun authHeader(options: DiscoveryOptions): String? {
        val password = options.password?.takeIf { it.isNotBlank() } ?: serverPassword() ?: return null
        val username = options.username?.takeIf { it.isNotBlank() }
            ?: System.getenv("OPENCODE_SERVER_USERNAME")?.takeIf { it.isNotBlank() }
            ?: "opencode"
        val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))
        return "Basic $token"
    }

    private fun serverPassword(): String? =
        System.getenv("OPENCODE_SERVER_PASSWORD")?.takeIf { it.isNotBlank() } ?: windowsUserEnv()

    /**
     * A GUI process inherits the environment from whenever it was launched, so an IDE started
     * before the variable was set never sees it. The registry always has the current value.
     */
    private fun windowsUserEnv(): String? {
        if (!SystemInfo.isWindows) return null
        return try {
            val process = ProcessBuilder("reg", "query", "HKCU\\Environment", "/v", "OPENCODE_SERVER_PASSWORD")
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
            val output = process.inputStream.bufferedReader().readText()
            Regex("OPENCODE_SERVER_PASSWORD\\s+REG_(?:EXPAND_)?SZ\\s+(.*)")
                .find(output)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            LOG.info("Could not read OPENCODE_SERVER_PASSWORD from the registry: ${e.message}")
            null
        }
    }

    fun openChamberConfigDir(): File = File(System.getProperty("user.home"), ".config/openchamber")

    private fun readJson(file: File) = try {
        if (file.isFile) Json.parseOrNull(file.readText()) else null
    } catch (e: Exception) {
        LOG.info("Could not read ${file.path}: ${e.message}")
        null
    }

    private fun httpGet(url: String): String? = try {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build()
        val response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        response.body().takeIf { response.statusCode() in 200..299 }
    } catch (_: Exception) {
        null
    }
}
