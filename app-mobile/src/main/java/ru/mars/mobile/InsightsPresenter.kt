package ru.mars.mobile

import kotlinx.coroutines.CancellationException
import ru.mars.network.model.InsightsSnapshot
import ru.mars.network.repository.InsightsResult

/**
 * Презентер вкладки Insights экрана мониторинга: оркестрирует загрузку usage-
 * снимка (порт [fetch] — в app это `InsightsRepository().getInsights(baseUrl,
 * credential, days = …)`) и offline-safe состояния. Чистый JVM-класс без
 * Android-импортов: сеть и детектор офлайна инжектятся, поэтому переходы
 * состояний полностью покрыты unit-тестами.
 *
 * Контракт состояний:
 * - нет credential -> [InsightsPhase.NO_AUTH] (загрузка даже не начинается);
 * - [InsightsResult.Snapshot] -> READY (строки [InsightsPolicy.rows]) или EMPTY
 *   ([InsightsPolicy.isEmpty]);
 * - [InsightsResult.Failed] (auth-guard 401/403, неожиданный 500, транспорт)
 *   -> ERROR; если прошлый снимок уже был, его строки остаются на экране
 *   (offline-safe), а ошибка уходит в notice;
 * - смена периода ([selectPeriod]) — только whitelisted дни
 *   ([InsightsPolicy.sanitizePeriod]) и перезагрузка.
 *
 * Конкурентные загрузки (быстрые клики по чипам/«Повторить»): каждый [load]
 * увеличивает [loadGeneration] и публикует результат, только если поколение
 * осталось последним — устаревший ответ более раннего запроса отбрасывается и
 * не затирает свежее состояние (stale-race guard).
 *
 * Отмена корутины экрана ([CancellationException]) пробрасывается дальше, а не
 * превращается в ERROR: отмена — не сетевая ошибка, состояние не мутируется.
 *
 * Экран read-only: презентер не порождает никаких мутаций — только GET снимка.
 */
class InsightsPresenter(
    private val hasCredential: () -> Boolean,
    private val fetch: suspend (days: Int) -> InsightsResult,
    private val isOffline: () -> Boolean = { false },
) {

    var state: InsightsUiState = InsightsUiState(
        phase = InsightsPhase.LOADING,
        message = InsightsPolicy.MSG_LOADING,
    )
        private set

    private var onChanged: (InsightsUiState) -> Unit = {}

    /** Поколение загрузки: результат устаревшего конкурентного [load] отбрасывается. */
    private var loadGeneration = 0

    /** Подписка на состояния; сразу реплеит текущее. Одна подписка на экран. */
    fun subscribe(listener: (InsightsUiState) -> Unit) {
        onChanged = listener
        listener(state)
    }

    /** Загрузка/перезагрузка снимка за текущий период. Идемпотентна по состоянию. */
    suspend fun load() {
        if (!hasCredential()) {
            publish(
                InsightsUiState(
                    phase = InsightsPhase.NO_AUTH,
                    periodDays = state.periodDays,
                    message = InsightsPolicy.MSG_NO_AUTH,
                )
            )
            return
        }
        val generation = ++loadGeneration
        publish(
            state.copy(
                phase = InsightsPhase.LOADING,
                notice = null,
                message = if (state.rows.isEmpty()) InsightsPolicy.MSG_LOADING else null,
                canRetry = false,
            )
        )
        val result = try {
            fetch(state.periodDays)
        } catch (cancellation: CancellationException) {
            throw cancellation // отмена экрана — не «ошибка сети»; состояние не трогаем
        } catch (error: Exception) {
            InsightsResult.Failed(error.message ?: "Network error")
        }
        if (generation != loadGeneration) return // в полёте более новая загрузка — эта устарела
        when (result) {
            is InsightsResult.Snapshot -> publish(ready(result.snapshot))
            else -> publish(failure(InsightsPolicy.errorMessage(result) ?: InsightsPolicy.MSG_ERROR, result))
        }
    }

    /**
     * Смена периода: whitelisted дни ([InsightsPolicy.sanitizePeriod]), затем
     * перезагрузка. Возвращает применённый период (для подсветки чипа).
     */
    suspend fun selectPeriod(days: Int): Int {
        val sanitized = InsightsPolicy.sanitizePeriod(days)
        if (sanitized != state.periodDays) {
            publish(state.copy(periodDays = sanitized))
        }
        load()
        return sanitized
    }

    private fun ready(snapshot: InsightsSnapshot): InsightsUiState {
        val rows = InsightsPolicy.rows(snapshot)
        return if (InsightsPolicy.isEmpty(snapshot) || rows.none { it !is InsightsRow.Section && it !is InsightsRow.Note }) {
            InsightsUiState(
                phase = InsightsPhase.EMPTY,
                periodDays = state.periodDays,
                message = InsightsPolicy.MSG_EMPTY,
            )
        } else {
            InsightsUiState(
                phase = InsightsPhase.READY,
                periodDays = state.periodDays,
                rows = rows,
            )
        }
    }

    private fun failure(message: String, result: InsightsResult): InsightsUiState {
        val offline = isOffline()
        val stale = state.rows.isNotEmpty()
        return InsightsUiState(
            phase = InsightsPhase.ERROR,
            periodDays = state.periodDays,
            rows = state.rows,
            notice = when {
                stale && offline -> InsightsPolicy.NOTICE_OFFLINE_STALE
                offline -> InsightsPolicy.NOTICE_OFFLINE
                else -> null
            },
            message = when {
                stale -> null
                offline && result is InsightsResult.Failed -> InsightsPolicy.MSG_ERROR_OFFLINE
                else -> message
            },
            canRetry = true,
        )
    }

    private fun publish(next: InsightsUiState) {
        state = next
        onChanged(next)
    }
}
