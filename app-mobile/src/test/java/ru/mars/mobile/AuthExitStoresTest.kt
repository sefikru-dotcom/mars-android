package ru.mars.mobile

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.credential.DeviceCredentialStore
import ru.mars.network.model.ConsumedDeviceCredential

/**
 * Тесты P0-фабрики [AuthExitStores]: единый контракт выхода из авторизации.
 *
 * Проверяется:
 *  - набор cleanup-хранилищ полный (черновики, история музыки, офлайн-кэш сессий,
 *    задачи, проекты, очередь неотправленных), без дублей и в зафиксированном порядке;
 *  - чистая фабрика [AuthExitStores.cleanup] строит ровно по одному хранилищу на файл
 *    и запускает их очистку через [AuthExitCleanup];
 *  - override подменяет хранилище конкретного файла (экран передаёт живой экземпляр);
 *  - фабрика [AuthExitStores.logoutFlow] сохраняет семантику [LogoutFlow]: revoke
 *    best-effort, локальный credential чистится безусловно (сетевой контракт не меняется).
 */
class AuthExitStoresTest {

    private class RecordingStore : ClearableLocalStore {
        var clears = 0
        override fun clearAll() { clears++ }
    }

    @Test
    fun `prefs files cover full auth-exit cleanup set without duplicates`() {
        assertEquals(
            "cleanup обязан покрывать все приватные plaintext-хранилища",
            setOf(
                "mars-session-drafts",
                "mars-music-search-history",
                "mars-offline-cache",
                "mars_todos_offline",
                "mars_projects_offline",
                "mars_knowledge_offline",
                "mars_music_offline",
                "mars-pending-messages",
            ),
            AuthExitStores.PREFS_FILES.toSet(),
        )
        assertEquals(
            "дубли файлов означали бы двойную очистку",
            AuthExitStores.PREFS_FILES.size,
            AuthExitStores.PREFS_FILES.distinct().size,
        )
    }

    @Test
    fun `cleanup factory builds one store per file in fixed order and clears all`() {
        val requested = mutableListOf<String>()
        val stores = mutableMapOf<String, RecordingStore>()
        val cleanup = AuthExitStores.cleanup { name ->
            requested += name
            RecordingStore().also { stores[name] = it }
        }

        assertEquals(AuthExitStores.PREFS_FILES, requested)
        assertFalse("cleanup не должен быть выполнен до run()", cleanup.hasRun)

        assertTrue(cleanup.run())
        assertEquals(AuthExitStores.PREFS_FILES.size, stores.size)
        stores.values.forEach { store ->
            assertEquals("каждое хранилище стирается ровно один раз", 1, store.clears)
        }
        assertFalse(cleanup.draftPersistenceAllowed)
    }

    @Test
    fun `context factory contract - override replaces only its own file`() {
        // Чистая проекция context-варианта: overrides[name] ?: storeFor(name).
        val override = RecordingStore()
        val overrides = mapOf(AuthExitStores.PENDING_QUEUE_PREFS to override)
        val built = mutableListOf<String>()
        val cleanup = AuthExitStores.cleanup { name ->
            overrides[name] ?: RecordingStore().also { built += name }
        }

        cleanup.run()
        assertEquals(1, override.clears)
        assertFalse("переопределённый файл не строится заново", AuthExitStores.PENDING_QUEUE_PREFS in built)
        assertEquals(
            AuthExitStores.PREFS_FILES - AuthExitStores.PENDING_QUEUE_PREFS,
            built,
        )
    }

    @Test
    fun `logoutFlow factory revokes best-effort and clears credential unconditionally`() = runBlocking {
        val credential = ConsumedDeviceCredential(
            accessToken = "token-1",
            tokenType = "Bearer",
            deviceId = "device-1",
            scopes = listOf("chat:send"),
            expiresIn = 3600,
        )
        val store = FakeCredentialStore(credential)
        val revoked = mutableListOf<Pair<String, String>>()

        AuthExitStores.logoutFlow(store, "https://example.invalid") { deviceId, accessToken ->
            revoked += deviceId to accessToken
            Result.failure(IllegalStateException("HTTP 401"))
        }.logout()

        assertEquals(listOf("device-1" to "token-1"), revoked)
        assertEquals("failed revoke must not keep the stale credential", 1, store.clearCount)

        val throwing = FakeCredentialStore(credential)
        AuthExitStores.logoutFlow(throwing, "https://example.invalid") { _, _ ->
            throw IOException("network down")
        }.logout()
        assertEquals(1, throwing.clearCount)
    }

    @Test
    fun `logoutFlow factory skips revoke when no credential stored`() = runBlocking {
        val store = FakeCredentialStore(null)
        var revokeCalled = false
        AuthExitStores.logoutFlow(store, "https://example.invalid") { _, _ ->
            revokeCalled = true
            Result.success(Unit)
        }.logout()
        assertFalse(revokeCalled)
        assertEquals(1, store.clearCount)
    }

    private class FakeCredentialStore(private val credential: ConsumedDeviceCredential?) : DeviceCredentialStore {
        var clearCount = 0
        override fun save(credential: ConsumedDeviceCredential) = Unit
        override fun read(): ConsumedDeviceCredential? = credential
        override fun clear() { clearCount++ }
    }
}
