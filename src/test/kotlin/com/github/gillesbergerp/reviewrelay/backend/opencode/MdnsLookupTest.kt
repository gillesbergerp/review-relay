package com.github.gillesbergerp.reviewrelay.backend.opencode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class MdnsLookupTest {

    /** A real answer from `opencode serve --mdns --port 45999`, captured off the wire. */
    private val advertisement: ByteArray = Base64.getDecoder().decode(
        "AACEAAAAAAEAAAAKBV9odHRwBF90Y3AFbG9jYWwAAAwAAQAAcIAAIQ5vcGVuY29kZS00NTk5OQVfaHR0cARfd" +
            "GNwBWxvY2FsAA5vcGVuY29kZS00NTk5OQVfaHR0cARfdGNwBWxvY2FsAAAhAAEAAAB4ABYAAAAAs68Ib3Bl" +
            "bmNvZGUFbG9jYWwADm9wZW5jb2RlLTQ1OTk5BV9odHRwBF90Y3AFbG9jYWwAABAAAQAAEZQABwZwYXRoPS8" +
            "Ib3BlbmNvZGUFbG9jYWwAAAEAAQAAAHgABArShgIIb3BlbmNvZGUFbG9jYWwAAAEAAQAAAHgABMCoAQcIb3" +
            "BlbmNvZGUFbG9jYWwAAAEAAQAAAHgABKwRAAEIb3BlbmNvZGUFbG9jYWwAAAEAAQAAAHgABKwdIAEIb3Blb" +
            "mNvZGUFbG9jYWwAABwAAQAAAHgAEP6AAAAAAAAA8Y7msxi7c5wIb3BlbmNvZGUFbG9jYWwAABwAAQAAAHgA" +
            "EP6AAAAAAAAAjXLKU1Lwcq4Ib3BlbmNvZGUFbG9jYWwAABwAAQAAAHgAEP6AAAAAAAAABxEZWgvZV8EIb3B" +
            "lbmNvZGUFbG9jYWwAABwAAQAAAHgAEP6AAAAAAAAALgXhWtLV818="
    )

    @Test
    fun `the advertised port is read out of the instance name`() {
        assertEquals(listOf(45999), MdnsLookup.portsIn(advertisement, advertisement.size))
    }

    @Test
    fun `a message naming another service yields no port`() {
        val other = advertisement.copyOf()
        // The instance names four of the records, so all of them have to stop being ours.
        var found = 0
        var at = String(other, Charsets.US_ASCII).indexOf("opencode-")
        while (at > 0) {
            "printer00".forEachIndexed { i, c -> other[at + i] = c.code.toByte() }
            found++
            at = String(other, Charsets.US_ASCII).indexOf("opencode-")
        }
        assertTrue(found >= 2)
        assertEquals(emptyList<Int>(), MdnsLookup.portsIn(other, other.size))
    }

    @Test
    fun `a truncated packet is not read past its end`() {
        for (size in 0..advertisement.size) {
            MdnsLookup.portsIn(advertisement, size)
        }
    }
}
