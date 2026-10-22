package ru.mars.mobile

import android.content.Context
import android.content.SharedPreferences

/**
 * Хранилище локального буфера прерванного хода (см. [InterruptedTurnPolicy]).
 *
 * Запись троттлится: SSE-дельта приходит десятки раз в секунду, а на диске достаточно
 * «последнего видимого текста» с точностью до ~0.5 с — иначе буфер сам стал бы
 * источником main-thread I/O (тот самый ANR, который эта волна лечит). Пишем через
 * `apply()` (асинхронно), поэтому кадр стрима не ждёт диск.
 *
 * Хранилище приватное и стирается на всех путях auth-exit (см. [AuthExitStores]):
 * текст ответа — это данные пользователя, они не должны переживать выход из аккаунта.
 */
class InterruptedTurnStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var lastWriteMs = 0L
    private var lastText: String? = null

    /**
     * Кадр стрима: запоминает текущий видимый текст ответа.
     * Ход закончился ([streaming] == false) → буфер больше не нужен и стирается.
     */
    fun rememberFrame(sessionId: String?, streamId: String?, streaming: Boolean, text: String) {
        if (!streaming || sessionId.isNullOrBlank() || text.isBlank()) {
            clearIfPresent()
            return
        }
        val now = clock()
        if (text == lastText) return
        // Троттлинг: между записями не чаще minIntervalMs (диск — не в кадре стрима).
        if (lastText != null && now - lastWriteMs < minIntervalMs) return
        lastText = text
        lastWriteMs = now
        prefs.edit()
            .putString(KEY_SESSION, sessionId)
            .putString(KEY_STREAM, streamId)
            .putString(KEY_TEXT, text)
            .putLong(KEY_SAVED_AT, now)
            .apply()
    }

    /** Чтение буфера при открытии диалога. */
    fun load(): InterruptedTurnSnapshot? {
        val sessionId = prefs.getString(KEY_SESSION, null) ?: return null
        val text = prefs.getString(KEY_TEXT, null) ?: return null
        return InterruptedTurnSnapshot(
            sessionId = sessionId,
            streamId = prefs.getString(KEY_STREAM, null),
            text = text,
            savedAtMs = prefs.getLong(KEY_SAVED_AT, 0L),
        )
    }

    /** Буфер отработал (или ход завершился) — стираем, чтобы не всплыл позже. */
    fun clear() {
        lastText = null
        lastWriteMs = 0L
        prefs.edit().clear().apply()
    }

    private fun clearIfPresent() {
        if (lastText != null || prefs.contains(KEY_TEXT)) clear()
    }

    companion object {
        /** Приватное хранилище буфера; чистится на auth-exit вместе с черновиками. */
        const val PREFS_NAME = "mars-interrupted-turn"
        const val KEY_SESSION = "session_id"
        const val KEY_STREAM = "stream_id"
        const val KEY_TEXT = "text"
        const val KEY_SAVED_AT = "saved_at"

        /** Не чаще двух записей в секунду. */
        const val DEFAULT_MIN_INTERVAL_MS = 500L
    }
}
