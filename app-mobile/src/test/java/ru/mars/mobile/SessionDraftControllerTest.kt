package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты политики черновиков [SessionDraftController] на in-memory фейке порта
 * [SessionDraftStorage]: независимость диалогов и нового чата, очистка после
 * принятия отправки, сохранение до смены session_id без перетирания чужого
 * черновика, ограниченность хранения (число записей + длина текста).
 */
class SessionDraftControllerTest {

    /** In-memory фейк хранилища; order сохраняют детерминизм вытеснения. */
    private class FakeStorage : SessionDraftStorage {
        val entries = mutableMapOf<String, StoredDraft>()

        override fun read(key: String): StoredDraft? = entries[key]
        override fun write(key: String, draft: StoredDraft) { entries[key] = draft }
        override fun remove(key: String) { entries.remove(key) }
        override fun snapshot(): Map<String, StoredDraft> = entries.toMap()
    }

    private val storage = FakeStorage()
    private var now = 1_000L
    private val controller = SessionDraftController(storage, clock = { now })

    private fun tick() { now += 1_000 }

    @Test
    fun `two sessions keep independent drafts`() {
        controller.saveDraft("s1", "текст для первой")
        tick()
        controller.saveDraft("s2", "текст для второй")

        assertEquals("текст для первой", controller.draftFor("s1"))
        assertEquals("текст для второй", controller.draftFor("s2"))
    }

    @Test
    fun `new chat draft is separate from session drafts`() {
        controller.saveDraft(null, "черновик нового чата")
        tick()
        controller.saveDraft("s1", "черновик сессии")

        assertEquals("черновик нового чата", controller.draftFor(null))
        assertEquals("черновик сессии", controller.draftFor("s1"))
        assertEquals(SessionDraftController.NEW_CHAT_KEY, controller.keyFor(null))
    }

    @Test
    fun `switch between dialogs does not overwrite the other draft`() {
        // Имитация перехода A -> B -> A: saveDraft(old) ДО смены id, затем draftFor(new).
        controller.saveDraft("sA", "привет A")
        tick()

        // Переход на B: сохраняем черновик A (уже сохранён выше), открываем B — свой черновик.
        controller.saveDraft("sB", "привет B")
        assertEquals("привет B", controller.draftFor("sB"))
        assertEquals("привет A", controller.draftFor("sA"))

        // Возврат на A: B не перетёрт, A восстановим.
        tick()
        controller.saveDraft("sA", "привет A обновлён")
        assertEquals("привет B", controller.draftFor("sB"))
        assertEquals("привет A обновлён", controller.draftFor("sA"))
    }

    @Test
    fun `blank text removes existing draft`() {
        controller.saveDraft("s1", "набросок")
        controller.saveDraft("s1", "   ")

        assertNull(controller.draftFor("s1"))
        assertTrue(storage.entries.isEmpty())
    }

    @Test
    fun `draftFor returns null when nothing saved`() {
        assertNull(controller.draftFor("missing"))
        assertNull(controller.draftFor(null))
    }

    @Test
    fun `send accepted clears only the draft of that dialog`() {
        controller.saveDraft("s1", "отправленное")
        tick()
        controller.saveDraft("s2", "осталось")
        tick()
        controller.saveDraft(null, "новый чат остался")

        controller.onSendAccepted("s1")

        assertNull(controller.draftFor("s1"))
        assertEquals("осталось", controller.draftFor("s2"))
        assertEquals("новый чат остался", controller.draftFor(null))
    }

    @Test
    fun `send accepted from new chat clears the new-chat draft`() {
        controller.saveDraft(null, "первое сообщение")
        controller.onSendAccepted(null)

        assertNull(controller.draftFor(null))
    }

    @Test
    fun `removed session draft is cleaned without touching others`() {
        controller.saveDraft("gone", "черновик удалённой")
        tick()
        controller.saveDraft("alive", "черновик живой")

        controller.onSessionRemoved("gone")

        assertNull(controller.draftFor("gone"))
        assertEquals("черновик живой", controller.draftFor("alive"))
    }

    @Test
    fun `storage is bounded by draft count with oldest evicted`() {
        val bounded = SessionDraftController(storage, maxDrafts = 3, clock = { now })
        bounded.saveDraft("s1", "один"); tick()
        bounded.saveDraft("s2", "два"); tick()
        bounded.saveDraft("s3", "три"); tick()
        // Обновление s1 не должно вытеснять s2 (s1 становится свежайшим).
        bounded.saveDraft("s1", "один-обновлён"); tick()
        bounded.saveDraft("s4", "четыре")

        assertEquals(3, storage.entries.size)
        assertNull("самый старый (s2) вытеснен", bounded.draftFor("s2"))
        assertEquals("один-обновлён", bounded.draftFor("s1"))
        assertEquals("три", bounded.draftFor("s3"))
        assertEquals("четыре", bounded.draftFor("s4"))
    }

    @Test
    fun `re-saving the same dialog never evicts others beyond the cap`() {
        val bounded = SessionDraftController(storage, maxDrafts = 2, clock = { now })
        bounded.saveDraft("s1", "а"); tick()
        bounded.saveDraft("s2", "б"); tick()
        // Перезапись существующего ключа при полном хранилище — без вытеснения соседа.
        bounded.saveDraft("s1", "а2")

        assertEquals(2, storage.entries.size)
        assertEquals("а2", bounded.draftFor("s1"))
        assertEquals("б", bounded.draftFor("s2"))
    }

    @Test
    fun `draft text is truncated to the char bound`() {
        val bounded = SessionDraftController(storage, maxDraftChars = 10, clock = { now })
        bounded.saveDraft("s1", "0123456789abcdef")

        assertEquals("0123456789", bounded.draftFor("s1"))
    }

    @Test
    fun `blank session id falls back to the new-chat key`() {
        controller.saveDraft("  ", "как новый чат")

        assertEquals("как новый чат", controller.draftFor(null))
        assertEquals("как новый чат", controller.draftFor("  "))
    }
}
