package ru.mars.mobile

/** Решение [StreamRenderThrottle] на очередное событие SSE-стрима. */
sealed interface StreamRenderDecision {
    /** Перерисовать немедленно (передний фронт или окно уже закрыто). */
    data object RenderNow : StreamRenderDecision

    /**
     * Поставить ОДИН отложенный (trailing) рендер через [delayMs]: события до него
     * накапливаются в состоянии и будут показаны разом, новых таймеров не плодят.
     */
    data class ScheduleAfter(val delayMs: Long) : StreamRenderDecision

    /** Trailing-рендер уже запланирован: событие поглощено батчем. */
    data object Ignore : StreamRenderDecision
}

/**
 * Троттлинг перерисовки транскрипта на кадрах стрима (leading + trailing) — чистая
 * JVM-политика в стиле [SessionListRefreshThrottle]: время инжектируется, Handler нет.
 *
 * Проблема: раньше ChatActivity вызывал renderMessages() на КАЖДЫЙ SSE-чанк — десятки
 * перерисовок в секунду на главном потоке; при длинной истории кадр не успевал → ANR.
 * Политика схлопывает кадры: первое событие окна рисуется сразу (ощущение живого
 * ответа), остальные накапливаются и выходят ровно одним trailing-рендером в конце
 * окна [minIntervalMs].
 *
 * Контракт хоста (ChatActivity):
 * - каждый СОСТОЯВШИЙСЯ рендер (leading, trailing, терминальный, любой renderMessages())
 *   вызывает [onRendered] — закрывает окно и снимает trailing-флаг;
 * - терминальные события (Done/Error/«Стоп») и возврат на передний план рисуют
 *   безусловно: хост отменяет отложенный flush и вызывает [onRendered] + рендер —
 *   хвост чанков никогда не теряется;
 * - отмена запланированного flush БЕЗ рендера (ход убит: [teardownLiveTurn]-путь)
 *   обязана вызвать [onTrailingCancelled], иначе флаг залипнет и стрим перестанет рисоваться.
 *
 * Все вызовы — на main-потоке (диспетчер Activity/collect), атомики не нужны.
 */
class StreamRenderThrottle(private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS) {

    private var lastRenderAtMs: Long? = null
    private var trailingScheduled = false

    /** Реакция на очередной кадр стрима; [nowMs] — монотонные часы хоста (elapsedRealtime). */
    fun onStreamEvent(nowMs: Long): StreamRenderDecision {
        val last = lastRenderAtMs
        return when {
            last == null -> {
                lastRenderAtMs = nowMs
                StreamRenderDecision.RenderNow
            }
            trailingScheduled -> StreamRenderDecision.Ignore
            nowMs - last >= minIntervalMs -> {
                lastRenderAtMs = nowMs
                StreamRenderDecision.RenderNow
            }
            else -> {
                trailingScheduled = true
                StreamRenderDecision.ScheduleAfter(minIntervalMs - (nowMs - last))
            }
        }
    }

    /** Состоявшийся рендер (leading/trailing/терминальный/полный): окно сбрасывается. */
    fun onRendered(nowMs: Long) {
        lastRenderAtMs = nowMs
        trailingScheduled = false
    }

    /** Хост отменил запланированный trailing flush без рендера: снять флаг, окно не трогать. */
    fun onTrailingCancelled() {
        trailingScheduled = false
    }

    /** True, если сейчас висит отложенный trailing flush (хост проверяет при onResume). */
    fun hasPendingFlush(): Boolean = trailingScheduled

    companion object {
        /** ~20 рендеров в секунду: стрим выглядит живым, но перерисовок на порядок меньше. */
        const val DEFAULT_MIN_INTERVAL_MS = 48L
    }
}
