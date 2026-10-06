package ru.mars.network.model

import org.junit.Test
import org.junit.Assert.assertEquals

class PairingModelsTest {
    @Test
    fun parsesCreateResponseWithRequiredServerFields() {
        val response = PairingResponseParser.parse(
            """{"pair_id":"pair-123","code":"482901","poll_secret":"poll-secret","expires_in":300,"status":"pending"}"""
        )

        assertEquals("pair-123", response.pairId)
        assertEquals("482901", response.code)
        assertEquals("poll-secret", response.pollSecret)
        assertEquals(300, response.expiresIn)
        assertEquals(PairingStatus.PENDING, response.status)
    }

    @Test
    fun parsesPollStatusAndOptionalExpiry() {
        val response = PairingStatusResponseParser.parse(
            """{"pair_id":"pair-123","status":"approved","expires_at":"2026-10-07T12:00:00Z"}"""
        )

        assertEquals("pair-123", response.pairId)
        assertEquals(PairingStatus.APPROVED, response.status)
        assertEquals("2026-10-07T12:00:00Z", response.expiresAt)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsUnknownStatus() {
        PairingStatusResponseParser.parse("""{"pair_id":"pair-123","status":"complete"}""")
    }
}
