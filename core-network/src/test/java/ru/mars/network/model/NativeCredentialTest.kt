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
            """ { "status" : "approved", "scopes" : [ "chat:read", "chat:send" ], "pair_id":"pair-123", "device_token":"secret-token", "device_id":"device-9", "expires_in":3600 } """
        )

        assertEquals(PairingStatus.APPROVED, response.status)
        assertEquals("secret-token", response.deviceToken)
        assertEquals("device-9", response.deviceId)
        assertEquals(listOf("chat:read", "chat:send"), response.scopes)
        assertEquals(3600, response.expiresIn)
    }

    @Test
    fun oneTimeCredentialCanBeReadOnlyOnce() {
        val credential = OneTimeDeviceCredential("token", "device", listOf("scope"), 3600)

        assertEquals("token", credential.consume()!!.deviceToken)
        assertNull(credential.consume())
    }

    @Test
    fun credentialRedactsSecretsFromStringRepresentation() {
        val credential = OneTimeDeviceCredential("super-secret", "device-1", listOf("scope"), 3600)

        assertFalse(credential.toString().contains("super-secret"))
        assertTrue(credential.toString().contains("[REDACTED]"))
    }
}
