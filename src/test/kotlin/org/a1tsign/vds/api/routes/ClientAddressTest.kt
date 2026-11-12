package org.a1tsign.vds.api.routes

import kotlin.test.Test
import kotlin.test.assertEquals

class ClientAddressTest {
    @Test
    fun `ignores X-Forwarded-For unless trusted`() {
        assertEquals("10.0.0.2", clientAddress("10.0.0.2", listOf("203.0.113.7"), trustForwardedFor = false))
    }

    @Test
    fun `takes the entry appended by the nearest proxy, not a client-supplied one`() {
        assertEquals("203.0.113.7", clientAddress("10.0.0.2", listOf("1.1.1.1, 203.0.113.7"), trustForwardedFor = true))
        assertEquals("203.0.113.7", clientAddress("10.0.0.2", listOf("1.1.1.1", "203.0.113.7"), trustForwardedFor = true))
    }

    @Test
    fun `falls back to the peer when the header is absent or empty`() {
        assertEquals("10.0.0.2", clientAddress("10.0.0.2", null, trustForwardedFor = true))
        assertEquals("10.0.0.2", clientAddress("10.0.0.2", listOf(" , "), trustForwardedFor = true))
    }
}
