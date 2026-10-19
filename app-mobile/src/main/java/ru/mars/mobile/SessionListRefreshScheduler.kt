package ru.mars.mobile

/**
 * Хост-цикл обновления списка диалогов поверх [SessionListRefreshThrottle].
 *
 * Зачем отдельный класс, а не пять строк в Activity: решение throttle (`Now`/`Skip`/
 * `Later` + «ровно один догоняющий refresh») — это ЧИСТАЯ логика, но живёт она в
 * цикле, где участвует время и запуск загрузки. В Activity такой цикл пришлось бы
 * проверять руками на устройстве; здесь время, старт загрузки и отложенный запуск
 * инжектируются, поэтому шторм триггеров проверяется обычным JVM-тестом
 * (`SessionListRefreshSchedulerTest`), а Activity остаётся тонким хостом.
 *
 * Контракт:
 * - [trigger] — пришёл триггер обновления (возврат на экран, подтверждённая мутация,
 *   восстановление сети). Слишком частый триггер откладывается на остаток интервала,
 *   а не дублирует запрос; новый отложенный запуск отменяет предыдущий (это делает
 *   [scheduleLater] хоста — так на экран попадает только последний).
 * - [startLoad] обязан вызвать `onFinished` на ЛЮБОМ исходе загрузки (успех, отказ,
 *   отмена): иначе throttle навсегда останется «в полёте» и список перестанет
 *   обновляться. Хост передаёт эту обязанность загрузке явно, чтобы её нельзя было
 *   забыть незаметно.
 * - Триггеры, пришедшие во время загрузки, схлопываются в ОДИН догоняющий refresh:
 *   данные, загруженные до возврата сети/до мутации, могут быть устаревшими.
 */
class SessionListRefreshScheduler(
    /** Текущее время в миллисекундах монотонного источника (`SystemClock.elapsedRealtime`). */
    private val nowMs: () -> Long,
    /** Запуск загрузки; `onFinished` вызывается по завершении (см. контракт класса). */
    private val startLoad: (onFinished: () -> Unit) -> Unit,
    /** Отложенный запуск: хост отменяет предыдущий отложенный запуск и ждёт [delayMs]. */
    private val scheduleLater: (delayMs: Long, run: () -> Unit) -> Unit,
    private val throttle: SessionListRefreshThrottle = SessionListRefreshThrottle(),
) {

    /** Триггер обновления списка. */
    fun trigger() {
        when (val decision = throttle.onTrigger(nowMs())) {
            SessionRefreshDecision.Now -> runNow()
            is SessionRefreshDecision.Later -> scheduleLater(decision.delayMs) { runNow() }
            SessionRefreshDecision.Skip -> Unit
        }
    }

    private fun runNow() {
        throttle.onStarted()
        startLoad {
            if (throttle.onFinished(nowMs())) runNow()
        }
    }
}
