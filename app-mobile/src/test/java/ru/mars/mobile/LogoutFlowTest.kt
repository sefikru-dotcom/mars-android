package ru.mars.mobile

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import ru.mars.network.credential.DeviceCredentialStore
import ru.mars.network.model.ConsumedDeviceCredential

class LogoutFlowTest {
    private class FakeStore(private val credential: ConsumedDeviceCredential?) : DeviceCredentialStore {
        var clearCount = 0
        override fun save(credential: ConsumedDeviceCredential) = Unit
        override fun read(): ConsumedDeviceCredential? = credential
        override fun clear() { clearCount++ }
    }

    private val credential = ConsumedDeviceCredential(
        accessToken = "token-1",
        tokenType = "Bearer",
        deviceId = "device-1",
        scopes = listOf("chat:send"),
        expiresIn = 3600,
    )

    @Test
    fun clearsLocalCredentialWhenRevokeSucceeds() = runBlocking {
        val store = FakeStore(credential)
        val revoked = mutableListOf<Pair<String, String>>()
        LogoutFlow(store) { deviceId, accessToken ->
            revoked += deviceId to accessToken
            Result.success(Unit)
        }.logout()
        assertEquals(listOf("device-1" to "token-1"), revoked)
        assertEquals(1, store.clearCount)
    }

    @Test
    fun clearsLocalCredentialWhenRevokeFailsWith401() = runBlocking {
        val store = FakeStore(credential)
        LogoutFlow(store) { _, _ ->
            Result.failure(IllegalStateException("HTTP 401"))
        }.logout()
        assertEquals("failed revoke must not keep the stale credential", 1, store.clearCount)
    }

    @Test
    fun clearsLocalCredentialWhenRevokeThrows() = runBlocking {
        val store = FakeStore(credential)
        LogoutFlow(store) { _, _ -> throw IOException("network down") }.logout()
        assertEquals(1, store.clearCount)
    }

    @Test
    fun clearsStoreWithoutRevokingWhenNoCredentialStored() = runBlocking {
        val store = FakeStore(null)
        var revokeCalled = false
        LogoutFlow(store) { _, _ ->
            revokeCalled = true
            Result.success(Unit)
        }.logout()
        assertFalse(revokeCalled)
        assertEquals(1, store.clearCount)
    }

    @Test
    fun clearsLocalCredentialEvenWhenStoreReadThrows() = runBlocking {
        val store = object : DeviceCredentialStore {
            var clearCount = 0
            override fun save(credential: ConsumedDeviceCredential) = Unit
            override fun read(): ConsumedDeviceCredential? = throw IllegalStateException("keystore broken")
            override fun clear() { clearCount++ }
        }
        LogoutFlow(store) { _, _ -> Result.success(Unit) }.logout()
        assertEquals(1, store.clearCount)
    }
}
