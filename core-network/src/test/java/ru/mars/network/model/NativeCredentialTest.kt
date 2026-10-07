package ru.mars.network.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCredentialTest {
    @Test
    fun parsesApprovedStatusWithOneTimeDeviceCredential() {
        val response = PairingStatusResponseParser.parse(
            """{"pair_id":"pair-123","status":"approved","device_token":"secret-token","device_id":"device-9","scopes":["chat:read","chat:send"],"expires_at":"2026-10-07T12:00:00Z"}"""
        )

        assertEquals(PairingStatus.APPROVED, response.status)
        assertEquals("secret-token", response.deviceToken)
        assertEquals("device-9", response.deviceId)
        assertEquals(listOf("chat:read", "chat:send"), response.scopes)
        assertEquals("2026-10-07T12:00:00Z", response.expiresAt)
    }

    @Test
    fun oneTimeCredentialCanBeReadOnlyOnce() {
        val credential = OneTimeDeviceCredential("token", "device", listOf("scope"), "expiry")

        assertEquals("token", credential.consume().deviceToken)
        assertNull(credential.consume())
    }

    @Test
    fun credentialRedactsSecretsFromStringRepresentation() {
        val credential = OneTimeDeviceCredential("super-secret", "device-1", listOf("scope"), "expiry")

        assertFalse(credential.toString().contains("super-secret"))
        assertTrue(credential.toString().contains("[REDACTED]"))
    }
}
