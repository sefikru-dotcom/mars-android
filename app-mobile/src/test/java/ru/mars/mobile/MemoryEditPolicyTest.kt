package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила правки секций памяти с телефона (контракт `POST /api/memory/write`,
 * hermes-webui api/route_memory.py `_handle_memory_write`):
 * - сервер принимает только section ∈ {memory, user, soul} — вкладка «Контекст»
 *   (AGENTS.md) НЕ записываема через этот эндпоинт;
 * - `require` (helpers.py) трактует пустую строку как отсутствующее поле → 400,
 *   поэтому blank-черновик несохраняем локально;
 * - запись без изменений — лишний трейс боевого файла, canSave её гасит.
 */
class MemoryEditPolicyTest {

    @Test
    fun writableSectionsFollowServerContract() {
        assertEquals("memory", MemoryEditPolicy.writableSection(MemoryTab.MEMORY))
        assertEquals("user", MemoryEditPolicy.writableSection(MemoryTab.USER))
        assertEquals("soul", MemoryEditPolicy.writableSection(MemoryTab.SOUL))
        assertNull("AGENTS.md не пишется через /api/memory/write", MemoryEditPolicy.writableSection(MemoryTab.PROJECT))
    }

    @Test
    fun canEditRequiresWritableTabAndLoadedState() {
        assertTrue(MemoryEditPolicy.canEdit(MemoryTab.MEMORY, MemorySectionState.Body("текст")))
        assertTrue("Пустую секцию можно заполнить с телефона", MemoryEditPolicy.canEdit(MemoryTab.SOUL, MemorySectionState.Empty("Секция SOUL.md пуста")))
        assertFalse(MemoryEditPolicy.canEdit(MemoryTab.PROJECT, MemorySectionState.Body("текст")))
        assertFalse(MemoryEditPolicy.canEdit(MemoryTab.MEMORY, MemorySectionState.Loading))
        assertFalse(MemoryEditPolicy.canEdit(MemoryTab.MEMORY, MemorySectionState.Failure("нет сети")))
    }

    @Test
    fun canSaveRejectsBlankAndUnchangedDraft() {
        assertTrue(MemoryEditPolicy.canSave("новый текст", "старый текст"))
        assertTrue("Из пустой секции — первая запись", MemoryEditPolicy.canSave("первая строка", ""))
        assertFalse("blank → серверный 400, незачем слать", MemoryEditPolicy.canSave("", "старый"))
        assertFalse(MemoryEditPolicy.canSave("   ", "старый"))
        assertFalse("Запись без изменений не нужна", MemoryEditPolicy.canSave("тот же", "тот же"))
    }

    @Test
    fun blankDraftMessageSeparatesEmptyFromUnchanged() {
        assertEquals(
            "Секция не может быть пустой",
            MemoryEditPolicy.saveBlockReason(draft = "  ", original = "старый"),
        )
        assertEquals("Нет изменений", MemoryEditPolicy.saveBlockReason(draft = "тот же", original = "тот же"))
        assertNull(MemoryEditPolicy.saveBlockReason(draft = "новый", original = "старый"))
    }

    @Test
    fun labelsMentionFileNameAndSection() {
        assertEquals("Правка: MEMORY.md", MemoryEditPolicy.dialogTitle(MemoryTab.MEMORY))
        assertEquals("Сохранено: USER.md", MemoryEditPolicy.savedMessage(MemoryTab.USER))
        assertTrue(MemoryEditPolicy.SAVE_LABEL.isNotBlank())
        assertTrue(MemoryEditPolicy.EDIT_LABEL.isNotBlank())
    }
}
