package com.github.gillesbergerp.reviewrelay.backend.opencode

import com.intellij.openapi.diagnostic.Logger
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Finds an OpenCode server by the mDNS service it publishes under `opencode serve --mdns`.
 *
 * The one way a server that chose its own port can be found: `--port` defaults to 0, so nothing but
 * the server knows where it ended up. It advertises `opencode-<port>._http._tcp.local`, which is why
 * only the instance name is read here and no SRV record is asked for.
 */
object MdnsLookup {

    private val LOG = Logger.getInstance(MdnsLookup::class.java)

    private const val GROUP = "224.0.0.251"
    private const val PORT = 5353
    private const val SERVICE = "_http._tcp.local"

    private val LISTEN = 1500.milliseconds

    /** Shorter than [LISTEN] so the clock is checked while answers are still arriving. */
    private val RECEIVE = 300.milliseconds

    /** The published name, whose numeric tail is the port the server listens on. */
    private val INSTANCE = Regex("opencode-(\\d{2,5})")

    /** Servers heard from within [timeout]. */
    fun find(timeout: Duration = LISTEN): List<OpenCodeAddress> {
        val found = LinkedHashMap<String, OpenCodeAddress>()
        try {
            // Answers come back to the group, never to the asking socket, so this has to be a member
            // of it on the mDNS port - which the OS responder and anything else holds at the same time.
            MulticastSocket(PORT).use { socket ->
                socket.reuseAddress = true
                socket.soTimeout = RECEIVE.inWholeMilliseconds.toInt()
                val group = InetSocketAddress(InetAddress.getByName(GROUP), PORT)
                val interfaces = usableInterfaces()
                interfaces.forEach { runCatching { socket.joinGroup(group, it) } }
                ask(socket, group, interfaces)

                val until = System.currentTimeMillis() + timeout.inWholeMilliseconds
                val buffer = ByteArray(8192)
                while (System.currentTimeMillis() < until) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    for (port in portsIn(packet.data, packet.length)) {
                        val host = packet.address?.hostAddress ?: continue
                        found["$host:$port"] = OpenCodeAddress(host, port)
                    }
                }
                interfaces.forEach { runCatching { socket.leaveGroup(group, it) } }
            }
        } catch (e: Exception) {
            LOG.info("mDNS lookup failed: ${e.message}")
        }
        return found.values.toList()
    }

    /** On every interface: a server on a virtual adapter is not reached over the default route. */
    private fun ask(socket: MulticastSocket, group: InetSocketAddress, interfaces: List<NetworkInterface>) {
        val query = query(SERVICE)
        val packet = DatagramPacket(query, query.size, group)
        interfaces.forEach { nic ->
            runCatching {
                socket.networkInterface = nic
                socket.send(packet)
            }
        }
    }

    private fun usableInterfaces(): List<NetworkInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && it.supportsMulticast() }
    }.getOrDefault(emptyList())

    private fun query(service: String): ByteArray {
        val out = ArrayList<Byte>(64)
        // Header: no id, no flags, one question, no records.
        repeat(4) { out.add(0) }
        out.addAll(listOf(0, 1))
        repeat(6) { out.add(0) }
        service.split('.').forEach { label ->
            out.add(label.length.toByte())
            label.forEach { out.add(it.code.toByte()) }
        }
        out.add(0)
        out.addAll(listOf(0, 12, 0, 1))
        return out.toByteArray()
    }

    /**
     * Every port named by a record in the message.
     *
     * The instance name turns up whether the responder put it in the answer or only among the
     * additional records, so the whole record section is walked rather than the answers alone.
     */
    internal fun portsIn(data: ByteArray, length: Int): List<Int> {
        if (length < 12) return emptyList()
        val ports = mutableListOf<Int>()
        var at = 12
        repeat(read16(data, 4)) {
            at = skipName(data, at, length)
            at += 4
        }
        val records = read16(data, 6) + read16(data, 8) + read16(data, 10)
        repeat(records) {
            if (at >= length) return ports.distinct()
            // SRV and TXT are named by the instance; PTR only points at it from its data.
            val owner = at
            at = skipName(data, at, length)
            if (at + 10 > length) return ports.distinct()
            val type = read16(data, at)
            val rdLength = read16(data, at + 8)
            at += 10
            portOf(nameAt(data, owner, length))?.let(ports::add)
            if (type == 12) portOf(nameAt(data, at, length))?.let(ports::add)
            at += rdLength
        }
        return ports.distinct()
    }

    private fun portOf(name: String): Int? = INSTANCE.find(name)?.groupValues?.get(1)?.toIntOrNull()

    private fun read16(data: ByteArray, at: Int): Int =
        ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)

    private fun isPointer(data: ByteArray, at: Int): Boolean = (data[at].toInt() and 0xC0) == 0xC0

    private fun skipName(data: ByteArray, from: Int, length: Int): Int {
        var at = from
        while (at < length) {
            val size = data[at].toInt() and 0xFF
            when {
                size == 0 -> return at + 1
                isPointer(data, at) -> return at + 2
                else -> at += size + 1
            }
        }
        return length
    }

    /** Follows the compression pointers a responder uses to avoid repeating the service name. */
    private fun nameAt(data: ByteArray, from: Int, length: Int): String {
        val name = StringBuilder()
        var at = from
        var hops = 0
        while (at in 0 until length && hops < 16) {
            val size = data[at].toInt() and 0xFF
            when {
                size == 0 -> break
                isPointer(data, at) -> {
                    at = ((size and 0x3F) shl 8) or (data[at + 1].toInt() and 0xFF)
                    hops++
                }
                else -> {
                    if (at + 1 + size > length) break
                    if (name.isNotEmpty()) name.append('.')
                    name.append(String(data, at + 1, size, Charsets.US_ASCII))
                    at += size + 1
                }
            }
        }
        return name.toString()
    }
}

/** Where a discovered server listens, which mDNS gives as an address rather than a name. */
data class OpenCodeAddress(val host: String, val port: Int)
