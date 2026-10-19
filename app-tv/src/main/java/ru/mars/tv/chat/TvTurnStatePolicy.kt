package ru.mars.tv.chat

import ru.mars.network.model.ActiveTurnInfo
import ru.mars.tv.TvBackoffConfig
import ru.mars.tv.TvStreamRecoveryPolicy

/**
 * Состояние серверного хода, которое НЕЛЬЗЯ переподключить, — читаемое с дистанции
 * (чистая политика, JVM-тесты).
 *
 * Разрыв: [ru.mars.tv.TvChatActivity.openSession] при `canReattach == false` молча
 * рисовал историю и всё. Если ход при этом ЖДЁТ решения (approval/clarify), стоит в
 * очереди или выполняется без журнала, пользователь видел «пустой» диалог и не знал,
 * что Марс от него ждёт ответа, — на ТВ это тупик: пульт не подсказывает, что делать.
 *
 * Политика различает три честных состояния и возвращает готовую строку баннера:
 * - [Tone.ATTENTION] — сервер ждёт решения (approval/clarify), журнал недоступен;
 * - [Tone.LIVE] — ход идёт, но переподключиться не к чему (stream_id потерян);
 * - [Tone.QUEUED] — сообщение в очереди, ход ещё не стартовал.
 *
 * Пустой ответ (`null`) означает «терять нечего»: ход завершился, его ответ уже в
 * истории — выдумывать тревогу не нужно.
 *
 * [watchDelayMs] — расписание фоновой перепроверки состояния (`GET /api/session`)
 * на переиспользованном [TvStreamRecoveryPolicy]: экспоненциальный backoff с потолком
 * и ограниченным числом попыток, чтобы ТВ не долбил сервер, пока пользователь решает
 * задачу в другом клиенте.
 */
object TvTurnStatePolicy {

    /** Тон баннера: цвет выбирает хост по палитре (в тексте никаких глифов). */
    enum class Tone { ATTENTION, LIVE, QUEUED }

    /** Готовая строка баннера: [text] — то, что видно с дивана, [tone] — тон. */
    data class Notice(val text: String, val tone: Tone)

    /** Сколько попыток фоновой перепроверки делает экран, прежде чем ждать ручного обновления. */
    const val MAX_WATCH_ATTEMPTS = 6

    /** Кнопка-подсказка действия: баннер сам является кнопкой, текст говорит, что будет. */
    const val REFRESH_HINT = "Нажмите, чтобы обновить."

    /** Баннер «ход ждёт подтверждения, журнал недоступен». */
    const val APPROVAL_TEXT = "Требуется подтверждение — ход ждёт решения."

    /** Баннер «ход ждёт ответа на вопрос, журнал недоступен». */
    const val CLARIFY_TEXT = "Марс ждёт ответа на вопрос."

    /** Баннер «ход идёт, но переподключиться не удалось». */
    const val LIVE_TEXT = "Ход выполняется, но переподключиться не удалось."

    /** Баннер «сообщение в очереди, ход ещё не стартовал». */
    const val QUEUED_TEXT = "Сообщение в очереди — ход ещё не запущен."

    /** Префикс критичного внимания (severity = critical). */
    const val CRITICAL_PREFIX = "Критично: "

    /**
     * Баннер для [activeTurn] или `null`, когда показывать нечего.
     *
     * `canReattach == true` намеренно даёт `null`: ход переподключён, его прогресс
     * показывает обычная статус-строка — второй индикатор на 10 футов был бы шумом.
     */
    fun notice(activeTurn: ActiveTurnInfo?): Notice? {
        if (activeTurn == null) return null
        if (activeTurn.canReattach) return null
        val attention = activeTurn.attention
        if (attention != null) {
            val base = when (attention.kind.trim().lowercase()) {
                "approval" -> APPROVAL_TEXT
                "clarify" -> CLARIFY_TEXT
                else -> "Марс ждёт вашего решения (${attention.kind.trim()})."
            }
            val critical = attention.severity?.trim()?.lowercase() == "critical"
            val head = if (critical) CRITICAL_PREFIX + base.replaceFirstChar { it.lowercase() } else base
            val count = if (attention.count > 1) " Решений: ${attention.count}." else ""
            return Notice(head + count + " " + REFRESH_HINT, Tone.ATTENTION)
        }
        if (activeTurn.isStreaming) return Notice("$LIVE_TEXT $REFRESH_HINT", Tone.LIVE)
        if (activeTurn.hasPendingUserMessage) return Notice("$QUEUED_TEXT $REFRESH_HINT", Tone.QUEUED)
        return null
    }

    /**
     * Задержка перед [attempt]-й (1-based) перепроверкой состояния; `null` — попытки
     * исчерпаны ([MAX_WATCH_ATTEMPTS]) и экран ждёт ручного обновления.
     */
    fun watchDelayMs(attempt: Int, config: TvBackoffConfig = TvBackoffConfig()): Long? {
        if (attempt < 1 || attempt > MAX_WATCH_ATTEMPTS) return null
        return TvStreamRecoveryPolicy.backoffDelayMs(attempt, config)
    }
}
