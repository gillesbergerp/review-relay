package com.github.gillesbergerp.reviewrelay.backend.opencode

import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stream a backend keeps, and the two ways it used to end without anyone noticing. */
class EventSubscriptionTest {

    private fun sse(vararg payloads: String) =
        ByteArrayInputStream(payloads.joinToString("\n") { "data: $it" }.toByteArray())

    private fun idle(session: String) = """{"type":"session.idle","properties":{"sessionID":"$session"}}"""

    private fun collect(stream: ByteArrayInputStream, wanted: Int, onEvent: (OpenCodeEvent) -> Unit = {}) =
        CountDownLatch(wanted).let { latch ->
            val seen = mutableListOf<OpenCodeEvent>()
            val subscription = EventSubscription(stream) { event ->
                synchronized(seen) { seen.add(event) }
                try {
                    onEvent(event)
                } finally {
                    latch.countDown()
                }
            }
            assertTrue("the events never arrived", latch.await(5, TimeUnit.SECONDS))
            subscription to seen
        }

    @Test
    fun `events off the stream reach the handler`() {
        val (_, seen) = collect(sse(idle("ses_1"), idle("ses_2")), wanted = 2)

        assertEquals(listOf("ses_1", "ses_2"), seen.map { (it as OpenCodeEvent.SessionIdle).sessionId })
    }

    /** A server restart ends the reader, and a subscription still held is one nothing replaces. */
    @Test
    fun `a stream that has ended is no longer alive`() {
        val (subscription, _) = collect(sse(idle("ses_1")), wanted = 1)

        // The handler runs before the reader leaves its loop, so the end is a moment behind.
        for (attempt in 0 until 50) {
            if (!subscription.isAlive) break
            Thread.sleep(20)
        }
        assertFalse("the reader has nothing left to read", subscription.isAlive)
    }

    @Test
    fun `a closed subscription is not alive`() {
        val subscription = EventSubscription(sse(idle("ses_1"))) {}
        subscription.close()

        assertFalse(subscription.isAlive)
    }

    /** One handler that throws used to kill the reader, and with it every later event. */
    @Test
    fun `a handler that throws does not end the stream`() {
        val (_, seen) = collect(sse(idle("ses_1"), idle("ses_2")), wanted = 2) { event ->
            if ((event as OpenCodeEvent.SessionIdle).sessionId == "ses_1") error("the host blew up")
        }

        assertEquals(listOf("ses_1", "ses_2"), seen.map { (it as OpenCodeEvent.SessionIdle).sessionId })
    }
}
