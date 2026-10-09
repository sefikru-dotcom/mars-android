package ru.mars.mobile

import android.content.SharedPreferences

/**
 * Локальное хранилище черновиков несент-сообщений по диалогам.
 *
 * Один черновик на ключ диалога: реальный `session_id` или отдельный ключ нового чата
 * ([SessionDraftController.NEW_CHAT_KEY]). Данные приватные (SharedPreferences MODE_PRIVATE),
 * объём ограничен политикой [SessionDraftController] (число черновиков + длина текста) —
 * ничего не логируется и не содержит секретов: только текст, который пользователь сам набрал.
 *
 * Порт [SessionDraftStorage] платформенно-независим, поэтому вся политика черновиков
 * покрыта JVM unit-тестами; Android-кодом является только адаптер [PrefsSessionDraftStore].
 */

/** Одна сохранённая запись черновика: текст и момент последнего сохранения (для LRU-вытеснения). */
data class StoredDraft(val text: String, val updatedAt: Long)

/** Порт хранения черновиков; приложение реализует его через SharedPreferences. */
interface SessionDraftStorage {
    /** Запись по ключу диалога; null, если черновика нет. */
    fun read(key: String): StoredDraft?

    /** Сохраняет/перезаписывает черновик по ключу. */
    fun write(key: String, draft: StoredDraft)

    /** Удаляет черновик по ключу (если его нет — no-op). */
    fun remove(key: String)

    /** Все сохранённые черновики (ключ -> запись) для проверки лимитов и вытеснения. */
    fun snapshot(): Map<String, StoredDraft>
}

/**
 * [SessionDraftStorage] поверх приватных SharedPreferences: текст под `draft:text:<key>`,
 * метка времени под `draft:ts:<key>`. Ключи диалогов — произвольные строки, коллизий с
 * другими настройками нет: файл prefs выделенный.
 */
class PrefsSessionDraftStore(private val prefs: SharedPreferences) : SessionDraftStorage {

    override fun read(key: String): StoredDraft? {
        val text = prefs.getString(TEXT_PREFIX + key, null) ?: return null
        return StoredDraft(text, prefs.getLong(TS_PREFIX + key, 0L))
    }

    override fun write(key: String, draft: StoredDraft) {
        prefs.edit()
            .putString(TEXT_PREFIX + key, draft.text)
            .putLong(TS_PREFIX + key, draft.updatedAt)
            .apply()
    }

    override fun remove(key: String) {
        prefs.edit()
            .remove(TEXT_PREFIX + key)
            .remove(TS_PREFIX + key)
            .apply()
    }

    override fun snapshot(): Map<String, StoredDraft> =
        prefs.all
            .filterKeys { it.startsWith(TEXT_PREFIX) }
            .mapKeys { it.key.removePrefix(TEXT_PREFIX) }
            .mapValues { (key, text) -> StoredDraft(text as String, prefs.getLong(TS_PREFIX + key, 0L)) }

    private companion object {
        const val TEXT_PREFIX = "draft:text:"
        const val TS_PREFIX = "draft:ts:"
    }
}
