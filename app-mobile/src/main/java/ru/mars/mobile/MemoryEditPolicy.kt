package ru.mars.mobile

/**
 * Чистые правила правки секций памяти с телефона (без Android, тестируется на JVM).
 *
 * Серверный контракт: `POST /api/memory/write` (hermes-webui api/route_memory.py
 * `_handle_memory_write`):
 * - `section` принимает ТОЛЬКО `memory|user|soul` — AGENTS.md (вкладка «Контекст»)
 *   через этот эндпоинт не пишется, поэтому [writableSection] для PROJECT — null;
 * - `content` обязан быть непустым (helpers.py `require` — пустая строка → 400);
 * - сервер делает ПОЛНУЮ перезапись файла секции, поэтому черновик стартует
 *   из текущего текста, а сохранение без изменений запрещает [canSave].
 */
object MemoryEditPolicy {

    const val EDIT_LABEL = "Править"
    const val SAVE_LABEL = "Сохранить"

    /** Wire-имя секции для `POST /api/memory/write`; null — вкладка только для чтения. */
    fun writableSection(tab: MemoryTab): String? = when (tab) {
        MemoryTab.MEMORY -> "memory"
        MemoryTab.USER -> "user"
        MemoryTab.SOUL -> "soul"
        // AGENTS.md сервер не принимает: `section must be "memory", "user", or "soul"` → 400.
        MemoryTab.PROJECT -> null
    }

    /**
     * Правка доступна только для записываемой вкладки с известным содержимым:
     * во время загрузки, при сбое сети/входа и без данных править нечего —
     * черновик стартовал бы из пустоты и затёр секцию на сервере.
     */
    fun canEdit(tab: MemoryTab, state: MemorySectionState): Boolean =
        writableSection(tab) != null &&
            (state is MemorySectionState.Body || state is MemorySectionState.Empty)

    /** Пустой content сервер отвергает (400), а перезапись байт-в-байт бессмысленна. */
    fun canSave(draft: String, original: String): Boolean = draft.isNotBlank() && draft != original

    /** Почему [canSave] вернул false — текст для инлайн-ошибки редактора. */
    fun saveBlockReason(draft: String, original: String): String? = when {
        draft.isBlank() -> "Секция не может быть пустой"
        draft == original -> "Нет изменений"
        else -> null
    }

    fun dialogTitle(tab: MemoryTab): String = "Правка: ${tab.fileName}"

    fun savedMessage(tab: MemoryTab): String = "Сохранено: ${tab.fileName}"
}
