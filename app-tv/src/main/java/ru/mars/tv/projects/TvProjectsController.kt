package ru.mars.tv.projects

import org.json.JSONObject
import ru.mars.network.cache.ProjectsOfflineCache
import ru.mars.tv.MainThreadTvDispatcher
import ru.mars.tv.TvOfflineCacheStatus
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

    /**
     * Метрики готовы к отрисовке: [status] — источник ([TvOfflineCacheStatus.LIVE] —
     * свежая сеть, [TvOfflineCacheStatus.CACHED] — офлайн-снимок), [badgeText] —
     * готовая плашка для live-региона («Обновлено только что» / «Снимок из кэша
     * (N мин. назад)»). CACHED рисуется как обычные метрики: экран не пустеет,
     * фокус пульта не переставляется.
     */
    fun showMetrics(metrics: TvProjectsMetrics, status: TvOfflineCacheStatus, badgeText: String?)

    /** Явное сообщение о недоступности; никогда не содержит сырой JSON/HTML. */
    fun showError(message: String)
}

/**
 * Оркестрация экрана живых метрик (seam-паттерн [ru.mars.tv.TvInteractiveController]):
 *
 * - [load] синхронно объявляет [TvProjectsSurface.showLoading] на UI-потоке, затем
 *   один фоновый вызов [TvProjectsGateway.fetchMetrics] на [executor] и парсинг
 *   [TvProjectsMetricsParser] там же; результат возвращается через [uiDispatcher].
 * - Офлайн-кэш ([ProjectsOfflineCache], необязательный): в кэш сохраняется сырое тело
 *   ТОЛЬКО успешно разобранного ответа (снимок, который нечем отрисовать, кэша не
 *   стоит); при сетевом сбое контроллер достаёт снимок и отдаёт его через
 *   [TvProjectsOfflinePolicy] как CACHED с возрастной плашкой вместо ошибки — экран
 *   не пустеет, фокус пульта остаётся на месте. Все обращения к кэшу конфайнятся к
 *   однопоточному [executor] (контракт «не потокобезопасен»).
 * - Generation guard: результат устаревшей загрузки отбрасывается, если стартовала
 *   более новая; [destroy] инвалидирует все in-flight результаты, поздние вызовы
 *   игнорируются, новые загрузки не стартуют.
 * - Ошибки явные: транспортный отказ без пригодного снимка — «Метрики недоступны:
 *   <причина>», битый ответ — «Метрики недоступны: некорректный ответ сервера».
 *   Нечитаемый кэшированный снимок отображается как исходный сетевой отказ (честная
 *   причина пустого экрана — сеть). Сырое тело ответа и тексты исключений в UI не
 *   попадают никогда.
 * - Пустой payload — НЕ ошибка: surface получает метрики с пустым списком сервисов
 *   и null-хостом, а UI отрисовывает их как явную недоступность секций.
 *
 * Все мутируемые поля конфайнятся к UI-потоку (контракт TvInteractiveController).
 */
class TvProjectsController(
    private val surface: TvProjectsSurface,
    private val gateway: TvProjectsGateway,
    /** Офлайн-кэш сырого JSON метрик; null — работа без кэша (прежнее поведение). */
    private val offlineCache: ProjectsOfflineCache? = null,
    /** Часы для расчёта возраста снимка; инъекция делает тесты детерминированными. */
    private val clock: () -> Long = { System.currentTimeMillis() },
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
                    is Outcome.Metrics ->
                        surface.showMetrics(outcome.metrics, outcome.status, outcome.badgeText)
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

    /**
     * Фоновое разрешение загрузки: сеть -> парсинг -> сохранение в кэш -> LIVE, либо
     * сбой -> снимок из кэша через [TvProjectsOfflinePolicy] (CACHED) или явная ошибка.
     * Исполняется ТОЛЬКО на [executor] — обращения к кэшу однопоточные.
     */
    private fun fetchOutcome(): Outcome = try {
        when (val result = gateway.fetchMetrics()) {
            is TvProjectsFetchResult.Success -> {
                val metrics = TvProjectsMetricsParser.parse(JSONObject(result.body))
                // Кэшируем только разобранный ответ: битый снимок при следующем сбое
                // был бы нечитаем и бесполезен.
                offlineCache?.saveProjectsJson(result.body)
                val decision = TvProjectsOfflinePolicy.onLiveSuccess()
                Outcome.Metrics(metrics, decision.status, decision.badgeText)
            }
            is TvProjectsFetchResult.Failure -> cachedFallback(result.reason)
        }
    } catch (e: Exception) {
        // Битый JSON или неожиданный сбой — явное сообщение без сырого ответа.
        Outcome.Error("$ERROR_PREFIX: некорректный ответ сервера")
    }

    /**
     * Сетевой сбой: пригодный снимок из кэша парсится и уходит как CACHED; всё
     * остальное (нет снимка, просрочен, нечитаем) — явная ошибка с исходной сетевой
     * причиной: честный ответ на «почему пусто» — отказ сети, а не кэша.
     */
    private fun cachedFallback(networkReason: String): Outcome {
        val decision = TvProjectsOfflinePolicy.onFetchFailure(
            cachedJson = offlineCache?.loadProjectsJson(),
            savedAtMs = offlineCache?.getSavedTimestamp(),
            nowMs = clock(),
        )
        val json = decision.cachedJson
        if (decision.status == TvOfflineCacheStatus.CACHED && json != null) {
            return try {
                Outcome.Metrics(
                    metrics = TvProjectsMetricsParser.parse(JSONObject(json)),
                    status = decision.status,
                    badgeText = decision.badgeText,
                )
            } catch (e: Exception) {
                Outcome.Error("$ERROR_PREFIX: $networkReason")
            }
        }
        return Outcome.Error("$ERROR_PREFIX: $networkReason")
    }

    private sealed interface Outcome {
        data class Metrics(
            val metrics: TvProjectsMetrics,
            val status: TvOfflineCacheStatus,
            val badgeText: String?,
        ) : Outcome

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
