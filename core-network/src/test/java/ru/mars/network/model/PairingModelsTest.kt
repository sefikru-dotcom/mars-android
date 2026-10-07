package ru.mars.network.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingModelsTest {
    @Test
    fun parsesCreateResponseWithRequiredServerFields() {
        val response = PairingResponseParser.parse(
            """{"pair_id":"pair-123","code":"482901","poll_secret":"poll-secret","expires_in":300,"status":"pending"}"""
        )
        assertEquals("pair-123", response.pairId)
        assertEquals(PairingStatus.PENDING, response.status)
    }

    @Test
    fun statusResponseIsMetadataOnlyEvenWhenLegacyTokenFieldsArePresent() {
        val response = PairingStatusResponseParser.parse(
            """{"pair_id":"pair-123","status":"approved","access_token":"must-not-be-read","device_id":"device-9"}"""
        )
        assertEquals(PairingStatus.APPROVED, response.status)
        assertEquals("PairingStatusResponse(pairId=pair-123, status=APPROVED)", response.toString())
    }

    @Test
    fun parsesV3ExchangeResponse() {
        val response = PairingExchangeResponseParser.parse(
            """{"access_token":"secret-token","token_type":"Bearer","expires_in":3600,"device_id":"device-9","scopes":["chat:read","chat:send"]}"""
        )
        assertEquals("secret-token", response.accessToken)
        assertEquals("Bearer", response.tokenType)
        assertEquals(3600, response.expiresIn)
        assertEquals("device-9", response.deviceId)
        assertEquals(listOf("chat:read", "chat:send"), response.scopes)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsMalformedExchangeResponse() {
        PairingExchangeResponseParser.parse("""{"access_token":"secret-token","token_type":"Bearer"}""")
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsUnknownStatus() {
        PairingStatusResponseParser.parse("""{"pair_id":"pair-123","status":"complete"}""")
    }

    @Test
    fun exchangeCredentialIsOneTimeAndRedacted() {
        val credential = PairingExchangeResponseParser.parse(
            """{"access_token":"super-secret","token_type":"Bearer","expires_in":3600,"device_id":"device-1","scopes":["scope"]}"""
        ).oneTimeCredential()
        assertFalse(credential.toString().contains("super-secret"))
        assertTrue(credential.toString().contains("[REDACTED]"))
        assertEquals("super-secret", credential.consume()!!.accessToken)
        assertNull(credential.consume())
    }
}