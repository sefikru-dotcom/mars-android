package ru.mars.mobile

import ru.mars.network.model.MemoryContent

/**
 * Вкладки экрана «Память Марса»: секция ответа `GET /api/memory` ↔ подпись чипа ↔
 * имя файла на сервере (показывается в сообщениях пустой секции и в clipboard-метке).
 */
enum class MemoryTab(val label: String, val fileName: String) {
    MEMORY("Память", "MEMORY.md"),
    USER("Профиль", "USER.md"),
    SOUL("SOUL", "SOUL.md"),
    PROJECT("Контекст", "AGENTS.md"),
}

/** Что показывает тело экрана памяти для выбранной вкладки. */
sealed interface MemorySectionState {
    /** Запрос к серверу в полёте. */
    data object Loading : MemorySectionState

    /** Сервер недоступен, нет креденшила или ответ не распарсился. */
    data class Failure(val message: String) : MemorySectionState

    /** Ответ получен, но именно эта секция пуста. */
    data class Empty(val message: String) : MemorySectionState

    /** Готовый markdown-текст секции (уже trimmed). */
    data class Body(val text: String) : MemorySectionState
}

/**
 * Чистое состояние вкладки памяти (без Android, тестируется на JVM).
 *
 * Правила:
 * - загрузка важнее всего: пока запрос в полёте — [MemorySectionState.Loading];
 * - нет ответа — [MemorySectionState.Failure] с сообщением (первая загрузка без
 *   сети и повторная после ошибки неразличимы для пользователя);
 * - секция есть, но пустая/пробельная — [MemorySectionState.Empty] с именем файла;
 * - иначе [MemorySectionState.Body] с обрезанным текстом.
 */
object MemoryTabPolicy {

    const val DEFAULT_FAILURE = "Не удалось загрузить память"
    const val SIGNED_OUT_FAILURE = "Нужен вход: откройте приложение и пройдите пару"

    /** Текст выбранной секции; до загрузки ответа — пустая строка. */
    fun textFor(tab: MemoryTab, content: MemoryContent?): String = when (tab) {
        MemoryTab.MEMORY -> content?.memory.orEmpty()
        MemoryTab.USER -> content?.user.orEmpty()
        MemoryTab.SOUL -> content?.soul.orEmpty()
        MemoryTab.PROJECT -> content?.projectContext.orEmpty()
    }

    fun resolve(
        tab: MemoryTab,
        content: MemoryContent?,
        loading: Boolean,
        failure: String? = null,
    ): MemorySectionState {
        if (loading) return MemorySectionState.Loading
        if (content == null) {
            return MemorySectionState.Failure(failure?.takeIf(String::isNotBlank) ?: DEFAULT_FAILURE)
        }
        val text = textFor(tab, content).trim()
        if (text.isEmpty()) return MemorySectionState.Empty("Секция ${tab.fileName} пуста")
        return MemorySectionState.Body(text)
    }

    /** Копировать есть что, только когда в выбранной секции непробельный текст. */
    fun canCopy(tab: MemoryTab, content: MemoryContent?): Boolean = textFor(tab, content).isNotBlank()
}
