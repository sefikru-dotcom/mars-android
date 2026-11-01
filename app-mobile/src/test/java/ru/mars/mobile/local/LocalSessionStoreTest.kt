package ru.mars.mobile.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.mars.mobile.LocalSessionJson
import ru.mars.mobile.LocalSessionRetention
import ru.mars.mobile.LocalSessionStore
import ru.mars.network.local.LocalMessage
import ru.mars.network.local.LocalRole
import ru.mars.network.local.LocalSession
import ru.mars.network.local.LocalToolCall
import java.io.File

/**
 * Хранилище локальных диалогов: обрезка, лимиты, атомарность, терпимость к мусору.
 *
 * Каталог — обычный [File] во временной папке: [LocalSessionStore] и
 * [LocalSessionRetention] намеренно не знают про Android, поэтому проверяются на JVM
 * без Robolectric и без устройства. `Context` здесь не нужен ни разу — это и есть
 * смысл разделения: экран отдаёт `filesDir`, логика работает с `File`.
 */
class LocalSessionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(dir: File = tmp.newFolder()) = LocalSessionStore(dir)

    private fun messages(count: Int): List<LocalMessage> = (1..count).map { index ->
        LocalMessage(
            id = "m$index",
            role = LocalRole.USER,
            text = "сообщение $index",
            createdAt = index.toLong(),
        )
    }

    // --- обрезка сообщений --------------------------------------------------

    @Test
    fun trimMessagesKeepsTailWhenOverLimit() {
        val trimmed = LocalSessionRetention.trimMessages(messages(5), limit = 3)
        assertEquals(3, trimmed.size)
        // Хвост важнее начала: последние реплики — это текущий контекст диалога.
        assertEquals(listOf("m3", "m4", "m5"), trimmed.map { it.id })
    }

    @Test
    fun trimMessagesLeavesShortListUntouched() {
        val original = messages(3)
        assertEquals(original, LocalSessionRetention.trimMessages(original, limit = 200))
    }

    @Test
    fun trimMessagesWithZeroLimitYieldsEmpty() {
        assertTrue(LocalSessionRetention.trimMessages(messages(3), limit = 0).isEmpty())
    }

    // --- сохранение и чтение ------------------------------------------------

    @Test
    fun saveAndReadRoundTripPreservesMessages() {
        val store = store()
        val session = store.create(title = "Тест")
        store.save(session, messages(3))

        assertEquals(3, store.messages(session.id).size)
        assertEquals("Тест", store.session(session.id)?.title)
    }

    @Test
    fun saveTrimsToMaxMessagesAtTheStoreBoundary() {
        val store = store()
        val session = store.create()
        // Больше лимита: на диск должно лечь ровно MAX_MESSAGES, и именно хвост.
        store.save(session, messages(LocalSessionStore.MAX_MESSAGES + 25))

        val stored = store.messages(session.id)
        assertEquals(LocalSessionStore.MAX_MESSAGES, stored.size)
        assertEquals("m${LocalSessionStore.MAX_MESSAGES + 25}", stored.last().id)
    }

    @Test
    fun sessionWithoutFileReadsAsNull() {
        assertNull(store().session("нет-такого"))
    }

    // --- атомарность --------------------------------------------------------

    @Test
    fun saveLeavesNoTemporaryFile() {
        val dir = tmp.newFolder()
        val store = store(dir)
        val session = store.create()
        store.save(session, messages(2))

        val leftovers = dir.listFiles()!!.filter { it.name.endsWith(LocalSessionRetention.TEMP_SUFFIX) }
        assertTrue("после записи не должно остаться .tmp: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun overwriteReplacesContentWithoutDuplicates() {
        val store = store()
        val session = store.create()
        store.save(session, messages(2))
        store.save(session, messages(4))

        assertEquals(4, store.messages(session.id).size)
    }

    // --- терпимость к мусору ------------------------------------------------

    @Test
    fun corruptedFileDoesNotThrowAndIsSkipped() {
        val dir = tmp.newFolder()
        val store = store(dir)
        val session = store.create()
        store.save(session, messages(2))

        // Портим файл так, как это бывает после сбоя питания.
        File(dir, session.id + LocalSessionRetention.FILE_SUFFIX).writeText("{ это не json")

        assertTrue(store.sessions().isEmpty())
        assertTrue(store.messages(session.id).isEmpty())
    }

    @Test
    fun decodeToleratesUnknownFieldsAndMissingOptional() {
        val raw = """{"version":1,"id":"s1","title":"Т","updatedAt":5,
            "unknown":"поле","messages":[{"id":"m1","role":"user","text":"привет"}]}"""
        val decoded = LocalSessionJson.decode(raw)
        assertEquals("s1", decoded?.session?.id)
        assertEquals(1, decoded?.messages?.size)
        assertEquals("привет", decoded?.messages?.first()?.text)
    }

    @Test
    fun toolCallsSurviveJsonRoundTrip() {
        val call = LocalToolCall(id = "call_1", name = "set_alarm", argumentsJson = """{"hour":7}""")
        val message = LocalMessage(
            id = "m1",
            role = LocalRole.ASSISTANT,
            toolCalls = listOf(call),
        )
        val restored = LocalSessionJson.decode(
            LocalSessionJson.encode(LocalSession("s1", "Т", 1), listOf(message))
        )?.messages?.firstOrNull()

        assertFalse(restored == null)
        assertEquals("set_alarm", restored?.toolCalls?.firstOrNull()?.name)
        assertEquals("""{"hour":7}""", restored?.toolCalls?.firstOrNull()?.argumentsJson)
    }

    // --- эвикция диалогов ---------------------------------------------------

    @Test
    fun pruneDeletesOldestBeyondLimit() {
        val dir = tmp.newFolder()
        val store = store(dir)
        // Три диалога с разным updatedAt: самый старый должен уйти первым.
        repeat(3) { index ->
            val session = LocalSession("s$index", "Диалог $index", updatedAt = index.toLong())
            store.save(session, messages(1))
        }

        val removed = LocalSessionRetention.prune(dir, limit = 2)

        assertEquals(1, removed.size)
        assertEquals("s0", removed.first())
        assertEquals(2, store.sessions().size)
    }

    @Test
    fun pruneDoesNothingWhenWithinLimit() {
        val dir = tmp.newFolder()
        val store = store(dir)
        repeat(2) { index ->
            store.save(LocalSession("s$index", "Диалог $index", index.toLong()), messages(1))
        }
        assertTrue(LocalSessionRetention.prune(dir, limit = 5).isEmpty())
        assertEquals(2, store.sessions().size)
    }

    // --- безопасность пути --------------------------------------------------

    @Test
    fun sessionIdCannotEscapeDirectory() {
        val dir = tmp.newFolder()
        val file = LocalSessionRetention.fileFor(dir, "../../evil")
        assertEquals(dir.canonicalPath, file.canonicalFile.parentFile.canonicalPath)
    }
}
