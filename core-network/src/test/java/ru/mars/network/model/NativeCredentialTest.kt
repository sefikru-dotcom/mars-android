package ru.mars.network.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCredentialTest {
    @Test
    fun exchangeResponseProducesNativeCredential() {
        val credential = PairingExchangeResponseParser.parse(
            """ { "access_token":"secret-token", "token_type":"Bearer", "expires_in":3600, "device_id":"device-9", "scopes":["chat:read"] } """
        ).oneTimeCredential()
        val consumed = credential.consume()!!
        assertEquals("secret-token", consumed.accessToken)
        assertEquals("Bearer", consumed.tokenType)
        assertEquals("device-9", consumed.deviceId)
    }

    @Test
    fun credentialCanBeConsumedOnlyOnce() {
        val credential = OneTimeDeviceCredential("token", "Bearer", "device", listOf("scope"), 3600)
        assertEquals("token", credential.consume()!!.accessToken)
        assertNull(credential.consume())
    }

    @Test
    fun credentialRedactsSecretsFromStringRepresentation() {
        val credential = OneTimeDeviceCredential("super-secret", "Bearer", "device-1", listOf("scope"), 3600)
        assertFalse(credential.toString().contains("super-secret"))
        assertTrue(credential.toString().contains("[REDACTED]"))
    }
}