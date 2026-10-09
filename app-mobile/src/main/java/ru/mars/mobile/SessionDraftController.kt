package ru.mars.mobile

/**
 * Политика черновиков несент-сообщений по диалогам — чистая логика без Android-фреймворка
 * (хранение через порт [SessionDraftStorage]). Хост (ChatActivity) дёргает тонкие хуки:
 *
 * - [saveDraft] ДО смены текущего диалога (переход между чатами, «новый чат», onPause при
 *   пересоздании экрана) — набранный текст не теряется и не перетирает чужой черновик;
 * - [draftFor] при открытии диалога — восстановление своего черновика;
 * - [onSendAccepted] после локального принятия отправки/стира — черновик очищается;
 * - [onSessionRemoved] при удалении или финише сессии — её черновик больше не нужен.
 *
 * Хранилище ограничено: не больше [maxDrafts] записей (вытесняется самая старая по
 * [StoredDraft.updatedAt]) и не больше [maxDraftChars] символов на черновик. Новый чат
 * (ещё без session_id) имеет собственный ключ [NEW_CHAT_KEY], поэтому его текст живёт
 * отдельно от черновиков реальных диалогов.
 */
class SessionDraftController(
    private val storage: SessionDraftStorage,
    private val maxDrafts: Int = DEFAULT_MAX_DRAFTS,
    private val maxDraftChars: Int = DEFAULT_MAX_DRAFT_CHARS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** Черновик диалога: null, когда его нет. [sessionId] == null означает новый чат. */
    fun draftFor(sessionId: String?): String? =
        storage.read(keyFor(sessionId))?.text?.takeIf { it.isNotEmpty() }

    /**
     * Сохраняет набранный текст как черновик [sessionId] (null — новый чат).
     * Пустой/пробельный текст удаляет существующий черновик; длинный усекается до
     * [maxDraftChars]; при переполнении [maxDrafts] вытесняется самый старый черновик.
     */
    fun saveDraft(sessionId: String?, text: String) {
        val key = keyFor(sessionId)
        if (text.isBlank()) {
            storage.remove(key)
            return
        }
        evictOldestIfFull(key)
        storage.write(key, StoredDraft(text.take(maxDraftChars), clock()))
    }

    /** Отправка принята локально (текст ушёл/поле очищено) — черновик диалога удаляется. */
    fun onSendAccepted(sessionId: String?) {
        storage.remove(keyFor(sessionId))
    }

    /** Сессия удалена или завершена — её черновик очищается. */
    fun onSessionRemoved(sessionId: String) {
        storage.remove(keyFor(sessionId))
    }

    /** Ключ хранения: реальный session_id либо отдельный ключ нового чата. */
    fun keyFor(sessionId: String?): String =
        sessionId?.takeIf { it.isNotBlank() } ?: NEW_CHAT_KEY

    private fun evictOldestIfFull(incomingKey: String) {
        val all = storage.snapshot()
        if (all.containsKey(incomingKey) || all.size < maxDrafts) return
        val oldest = all.minByOrNull { it.value.updatedAt }?.key ?: return
        storage.remove(oldest)
    }

    companion object {
        /** Ключ черновика нового чата (session_id ещё не существует). */
        const val NEW_CHAT_KEY = "***"

        /** Сколько диалогов могут одновременно держать черновик. */
        const val DEFAULT_MAX_DRAFTS = 40

        /** Потолок длины одного черновика (SharedPreferences — не место для мегабайтов). */
        const val DEFAULT_MAX_DRAFT_CHARS = 20_000
    }
}
