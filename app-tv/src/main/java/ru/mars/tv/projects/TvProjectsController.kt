package ru.mars.tv.projects

import org.json.JSONObject
import ru.mars.tv.MainThreadTvDispatcher
import ru.mars.tv.TvUiDispatcher
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Surface TV-экрана проектов: loading / metrics / error (состояния хоста и сервисов
 * [TvProjectsActivity] доотрисовывает из [TvProjectsMetrics] — отсутствующая секция
 * показывается явно, через [TvProjectsCardSpec], без заглушек вроде «Онлайн»).
 * Контроллер вызывает эти методы на [TvUiDispatcher]-потоке (в продакшне — UI).
 */
interface TvProjectsSurface {
    fun showLoading()
    fun showMetrics(metrics: TvProjectsMetrics)
    /** Явное сообщение о недоступности; никогда не содержит сырой JSON/HTML. */
    fun showError(message: String)
}

/**
 * Оркестрация экрана живых метрик (seam-паттерн [ru.mars.tv.TvInteractiveController]):
 *
 * - [load] синхронно объявляет [TvProjectsSurface.showLoading] на UI-потоке, затем
 *   один фоновый вызов [TvProjectsGateway.fetchMetrics] на [executor] и парсинг
 *   [TvProjectsMetricsParser] там же; результат возвращается через [uiDispatcher].
 * - Generation guard: результат устаревшей загрузки отбрасывается, если стартовала
 *   более новая; [destroy] инвалидирует все in-flight результаты, поздние вызовы
 *   игнорируются, новые загрузки не стартуют.
 * - Ошибки явные: транспортный отказ — «Метрики недоступны: <причина>», битый
 *   ответ — «Метрики недоступны: некорректный ответ сервера». Сырое тело ответа
 *   и тексты исключений в UI не попадают никогда.
 * - Пустой payload — НЕ ошибка: surface получает метрики с пустым списком сервисов
 *   и null-хостом, а UI отрисовывает их как явную недоступность секций.
 *
 * Все мутируемые поля конфайнятся к UI-потоку (контракт TvInteractiveController).
 */
class TvProjectsController(
    private val surface: TvProjectsSurface,
    private val gateway: TvProjectsGateway,
    private val executor: Executor = SHARED_EXECUTOR,
    private val uiDispatcher: TvUiDispatcher = MainThreadTvDispatcher(),
) {

    // Конфайнмент к UI-dispatcher потоку: пишутся только из load()/destroy().
    private var generation = 0
    private var destroyed = false

    /** Запустить загрузку метрик (первичную, периодическую или retry). Только с UI-потока. */
    fun load() {
        if (destroyed) return
        val token = ++generation
        surface.showLoading()
        executor.execute {
            val outcome = fetchOutcome()
            uiDispatcher.dispatch {
                if (destroyed || token != generation) return@dispatch
                when (outcome) {
                    is Outcome.Metrics -> surface.showMetrics(outcome.metrics)
                    is Outcome.Error -> surface.showError(outcome.message)
                }
            }
        }
    }

    /** Из Activity#onDestroy: поздние фоновые результаты игнорируются. */
    fun destroy() {
        destroyed = true
        generation++
    }

    private fun fetchOutcome(): Outcome = try {
        when (val result = gateway.fetchMetrics()) {
            is TvProjectsFetchResult.Success ->
                Outcome.Metrics(TvProjectsMetricsParser.parse(JSONObject(result.body)))
            is TvProjectsFetchResult.Failure ->
                Outcome.Error("$ERROR_PREFIX: ${result.reason}")
        }
    } catch (e: Exception) {
        // Битый JSON или неожиданный сбой — явное сообщение без сырого ответа.
        Outcome.Error("$ERROR_PREFIX: некорректный ответ сервера")
    }

    private sealed interface Outcome {
        data class Metrics(val metrics: TvProjectsMetrics) : Outcome
        data class Error(val message: String) : Outcome
    }

    companion object {
        const val ERROR_PREFIX = "Метрики недоступны"

        /** Общий фоновый исполнитель загрузок (HTTP + парсинг), daemon-поток. */
        val SHARED_EXECUTOR: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tv-projects-io").apply { isDaemon = true }
        }
    }
}
