package ru.mars.tv.chat

import ru.mars.network.model.ContextStatusUpdate

/**
 * Тон бейджа статуса контекста — цвет подбирает хост по палитре ТВ-темы
 * (`R.color.mars_status_*`), сама политика остаётся чистой JVM (без android.* и R).
 */
enum class TvContextTone {
    /** Контекст подгружен и доступен (зелёный). */
    LOADED,

    /** Контекст подгружен, но уже сжат — полезно видеть с дистанции (жёлтый). */
    COMPACTED,

    /** Контекст не поднялся: ответ пойдёт без истории (красный). */
    ERROR,
}

/** Готовая к показу подпись бейджа: текст + тон. */
data class TvContextBadge(val text: String, val tone: TvContextTone)

/**
 * Бейдж статуса префилл-контекста для ТВ-чата — аналог мобильного
 * `ContextStatusPresenter`, но рассчитанный на экран с дистанции.
 *
 * Кадр приходит по НЕ-sealed side-каналу [ContextStatusUpdate] (sealed `ChatEvent`
 * не расширяем: исчерпывающие `when` в app-mobile/app-tv остались бы целы).
 * Источник — WebUI `context_status`; на ТВ вместо строки статус-бара рисуется
 * отдельный бейдж крупным кеглем: короткие сегменты через « · », без мелкого
 * текста и без служебных кодов статуса.
 *
 * Чистая логика без android.* — хост ([ru.mars.tv.TvChatActivity]) только
 * подставляет текст/тон и гасит бейдж таймером.
 */
object TvContextStatusPresenter {

    /**
     * Сколько мс бейдж виден до автосброса. Дольше мобильных 4 с: ТВ-подпись
     * читают с дистанции, плюс она не конкурирует со строкой статуса хода —
     * висит отдельным чипом и не залипает поверх «Марс печатает…».
     */
    const val DISPLAY_DURATION_MS = 6_000L

    /** Дефолтная подпись источника — паритет с WebUI (`prefill.label || 'session recall'`). */
    private const val DEFAULT_LABEL = "session recall"

    /** Максимум символов подписи источника: длинный label не должен растянуть чип на весь экран. */
    private const val MAX_LABEL_CHARS = 28

    /**
     * Бейдж для кадра [update] в контексте сессии [activeSessionId] или null —
     * показывать нечего (чужая сессия, `not_configured`, неизвестный статус).
     * Каждый кадр ПЕРЕЗАПИСЫВАЕТ бейдж, а не накапливает строки.
     */
    fun badge(update: ContextStatusUpdate, activeSessionId: String?): TvContextBadge? {
        if (!isForActiveSession(update, activeSessionId)) return null
        val label = shortLabel(update.label)
        return when (update.status) {
            "loaded" -> TvContextBadge(
                text = line("Контекст загружен", label, update),
                tone = if (update.compacted) TvContextTone.COMPACTED else TvContextTone.LOADED,
            )
            "error" -> TvContextBadge(
                text = line("Контекст недоступен", label, update),
                tone = TvContextTone.ERROR,
            )
            else -> null
        }
    }

    /**
     * Привязка к активной сессии — паритет с WebUI
     * (`(d.session_id || activeSid) !== activeSid`): кадр без session_id считается
     * своим, иначе идентификаторы должны совпасть — статус чужого диалога не
     * должен протекать в текущий экран.
     */
    fun isForActiveSession(update: ContextStatusUpdate, activeSessionId: String?): Boolean =
        (update.sessionId ?: activeSessionId) == activeSessionId

    /**
     * Сегменты подписи через « · »: заголовок, источник, объём контекста, признак
     * сжатия. Пустые сегменты выпадают — бейдж не содержит « ·  · ».
     */
    private fun line(head: String, label: String, update: ContextStatusUpdate): String {
        val parts = ArrayList<String>(4)
        parts += head
        parts += label
        if (update.messageCount > 0) parts += "${update.messageCount} сообщ."
        if (update.compacted) parts += "сжат"
        return parts.joinToString(" · ")
    }

    /** Источник подписи: пустой label -> дефолт WebUI, слишком длинный — обрезка с многоточием. */
    private fun shortLabel(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return DEFAULT_LABEL
        return if (trimmed.length <= MAX_LABEL_CHARS) trimmed else trimmed.take(MAX_LABEL_CHARS - 1) + "…"
    }
}
