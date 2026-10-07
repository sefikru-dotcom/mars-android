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
            """{"pair_id":"pair-123","status":"pending"}"""
        )

        assertEquals("pair-123", response.pairId)
        assertEquals(PairingStatus.PENDING, response.status)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsApprovedStatusWithMissingCredentialFields() {
        PairingStatusResponseParser.parse("""{"pair_id":"pair-123","status":"approved","device_token":"token"}""")
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsUnknownStatus() {
        PairingStatusResponseParser.parse("""{"pair_id":"pair-123","status":"complete"}""")
    }
}
