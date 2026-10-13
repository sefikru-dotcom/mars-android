package ru.mars.mobile

import ru.mars.network.model.ContextStatusUpdate

/**
 * Видимая строка статуса префилл-контекста для мобильного чата. Эталон —
 * WebUI (static/messages.js, listener `context_status`): `loaded` →
 * «Context loaded: <label>», `error` → «Context unavailable: <label>»,
 * остальные статусы (`not_configured` и пр.) не показываются.
 *
 * Чистая презентационная логика без android.* — тонкий wiring в ChatActivity
 * лишь подставляет строку в статус-бар и сбрасывает её таймером. Кадр приходит
 * по НЕ-sealed side-каналу [ContextStatusUpdate] (sealed ChatEvent не
 * расширяем: исчерпывающие when app-mobile/app-tv остались бы целы).
 */
object ContextStatusPresenter {
    /** Сколько мс строка статуса видна до автосброса — не залипает поверх «Марс печатает…». */
    const val DISPLAY_DURATION_MS = 4_000L

    /** Дефолтная подпись источника — паритет с WebUI (`prefill.label || 'session recall'`). */
    private const val DEFAULT_LABEL = "session recall"

    /**
     * Строка статуса для кадра [update] в контексте сессии [activeSessionId]
     * или null — показывать нечего (кадр чужой сессии, `not_configured`,
     * неизвестный статус). Каждый вызов возвращает одну строку: статус
     * обновляется/сбрасывается, а не накапливается.
     */
    fun statusLine(update: ContextStatusUpdate, activeSessionId: String?): String? {
        if (!isForActiveSession(update, activeSessionId)) return null
        val label = update.label?.takeIf { it.isNotBlank() } ?: DEFAULT_LABEL
        return when (update.status) {
            "loaded" -> "Контекст загружен: $label"
            "error" -> "Контекст недоступен: $label"
            else -> null
        }
    }

    /**
     * Привязка к активной сессии — паритет с WebUI
     * (`(d.session_id || activeSid) !== activeSid`): кадр без session_id
     * считается своим, иначе идентификаторы должны совпасть — статус чужого
     * диалога не должен протекать в текущий экран.
     */
    fun isForActiveSession(update: ContextStatusUpdate, activeSessionId: String?): Boolean =
        (update.sessionId ?: activeSessionId) == activeSessionId
}
