package ru.mars.tv.insights

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.InsightsSnapshot
import ru.mars.network.model.ServerLogFile
import ru.mars.network.model.ServerLogTail
import ru.mars.network.model.ServerLogWindow
import ru.mars.network.repository.InsightsRepository
import ru.mars.network.repository.InsightsResult
import ru.mars.network.repository.LogsRepository
import ru.mars.network.repository.ServerLogsResult
import ru.mars.tv.MainThreadTvDispatcher
import ru.mars.tv.TvOfflineCacheStatus
import ru.mars.tv.TvOfflineSnapshotPolicy
import ru.mars.tv.TvUiDispatcher
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Результат transport-слоя вкладки «Аналитика»: снимок либо причина отказа. */
sealed interface TvInsightsFetchResult {
    data class Success(val snapshot: InsightsSnapshot) : TvInsightsFetchResult
    /** Причина для UI — без сырых исключений/JSON/URL (гигиена сообщений). */
    data class Failure(val reason: String) : TvInsightsFetchResult
}

/** Результат transport-слоя вкладки «Логи»: окно либо причина отказа. */
sealed interface TvLogsFetchResult {
    data class Success(val window: ServerLogWindow) : TvLogsFetchResult
    data class Failure(val reason: String) : TvLogsFetchResult
}

/**
 * Data-seam вкладки «Аналитика» (паттерн TvCronGateway). На устройстве —
 * [RepositoryTvInsightsGateway]; unit-тесты подставляют fake без HTTP.
 */
interface TvInsightsGateway {
    /** Блокирующий вызов; исполняется ТОЛЬКО на background-executor контроллера. */
    fun fetchInsights(days: Int): TvInsightsFetchResult
}

/** Data-seam вкладки «Логи» — то же разделение, свой контракт результата. */
interface TvLogsGateway {
    fun fetchLogs(file: ServerLogFile, tail: ServerLogTail): TvLogsFetchResult
}

/**
 * Продакшн-реализация: [InsightsRepository.getInsights] (`GET /api/insights`) —
 * read-only маршрут из белого списка нативного токена. Токен читается
 * провайдером и никогда не покидает этот класс.
 */
class RepositoryTvInsightsGateway(
    private val repository: InsightsRepository,
    private val baseUrl: String,
    private val credentialProvider: () -> ConsumedDeviceCredential?,
) : TvInsightsGateway {

    override fun fetchInsights(days: Int): TvInsightsFetchResult {
        val credential = credentialProvider() ?: return TvInsightsFetchResult.Failure(NO_CREDENTIAL)
        return try {
            when (val result = runBlocking { repository.getInsights(baseUrl, credential, days) }) {
                is InsightsResult.Snapshot -> TvInsightsFetchResult.Success(result.snapshot)
                is InsightsResult.Failed -> TvInsightsFetchResult.Failure(result.message)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TvInsightsFetchResult.Failure("сбой запроса")
        }
    }

    companion object {
        /** Явная причина при отсутствующей паре устройства (экран pairing не пройден). */
        const val NO_CREDENTIAL = "устройство не сопряжено"
    }
}

/**
 * Продакшн-реализация: [LogsRepository.getLogs] (`GET /api/logs`) — read-only
 * маршрут из белого списка нативного токена; `InvalidRequest` (400) недостижим
 * через enum-параметры и озвучивается честно, если серверный whitelist изменится.
 */
class RepositoryTvLogsGateway(
    private val repository: LogsRepository,
    private val baseUrl: String,
    private val credentialProvider: () -> ConsumedDeviceCredential?,
) : TvLogsGateway {

    override fun fetchLogs(file: ServerLogFile, tail: ServerLogTail): TvLogsFetchResult {
        val credential =
            credentialProvider() ?: return TvLogsFetchResult.Failure(RepositoryTvInsightsGateway.NO_CREDENTIAL)
        return try {
            when (val result = runBlocking { repository.getLogs(baseUrl, credential, file, tail) }) {
                is ServerLogsResult.Window -> TvLogsFetchResult.Success(result.window)
                is ServerLogsResult.InvalidRequest -> TvLogsFetchResult.Failure(TvLogsPolicy.ERROR_REQUEST)
                is ServerLogsResult.Failed -> TvLogsFetchResult.Failure(result.message)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TvLogsFetchResult.Failure("сбой запроса")
        }
    }
}

/**
 * Surface TV-экрана «Аналитика/Логи»: две независимые вкладки, каждая со своими
 * состояниями loading/data/error. Контроллер вызывает эти методы на
 * [TvUiDispatcher]-потоке.
 */
interface TvInsightsSurface {
    fun showInsightsLoading()
    fun showInsights(snapshot: InsightsSnapshot, status: TvOfflineCacheStatus, badgeText: String?)
    /** Явное сообщение о недоступности; никогда не содержит сырой JSON/HTML. */
    fun showInsightsError(message: String)

    fun showLogsLoading()
    fun showLogs(window: ServerLogWindow, status: TvOfflineCacheStatus, badgeText: String?)
    fun showLogsError(message: String)
}

/**
 * Оркестрация экрана «Аналитика» (табы Аналитика/Логи; seam-паттерн
 * [ru.mars.tv.cron.TvCronController]):
 *
 * - [loadInsights]/[loadLogs] синхронно объявляют loading на UI-потоке, затем
 *   один фоновый вызов гейтвея на общем [executor]; результат возвращается
 *   через [uiDispatcher];
 * - у вкладок НЕЗАВИСИМЫЕ generation guards: смена периода не отменяет
 *   загрузку логов и наоборот; [destroy] инвалидирует все in-flight результаты;
 * - offline ([TvOfflineSnapshotPolicy]): последний удачный снимок/окно кэшируются
 *   в памяти контроллера (в рамках жизни экрана); при сетевом сбое пригодный
 *   кэш показывается как CACHED с возрастной плашкой вместо ошибки — фокус
 *   пульта не переставляется на «Повторить». Окна аналитики живут 24 ч
 *   ([TvInsightsPolicy.CACHE_MAX_AGE_MS]), окна логов — 12 ч
 *   ([TvLogsPolicy.CACHE_MAX_AGE_MS]).
 *
 * Все мутируемые поля конфайнятся: generation/destroyed — к UI-потоку,
 * lastGood* — к executor-потоку (пишутся и читаются только внутри execute{}).
 */
class TvInsightsController(
    private val surface: TvInsightsSurface,
    private val insightsGateway: TvInsightsGateway,
    private val logsGateway: TvLogsGateway,
    private val executor: Executor = SHARED_EXECUTOR,
    private val uiDispatcher: TvUiDispatcher = MainThreadTvDispatcher(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    // Конфайнмент к UI-dispatcher потоку: пишутся только из load*()/destroy().
    private var insightsGeneration = 0
    private var logsGeneration = 0
    private var destroyed = false

    // Конфайнмент к executor-потоку (единственный daemon-поток SHARED_EXECUTOR).
    private var lastGoodSnapshot: InsightsSnapshot? = null
    private var lastGoodSnapshotAtMs: Long? = null
    private var lastGoodWindow: ServerLogWindow? = null
    private var lastGoodWindowAtMs: Long? = null

    private val insightsOffline = TvOfflineSnapshotPolicy(TvInsightsPolicy.CACHE_MAX_AGE_MS)
    private val logsOffline = TvOfflineSnapshotPolicy(TvLogsPolicy.CACHE_MAX_AGE_MS)

    /** Загрузка/перезагрузка вкладки «Аналитика» за период [days]. Только с UI-потока. */
    fun loadInsights(days: Int) {
        if (destroyed) return
        val token = ++insightsGeneration
        surface.showInsightsLoading()
        executor.execute {
            val outcome = insightsGateway.fetchInsights(days)
            if (outcome is TvInsightsFetchResult.Success) {
                lastGoodSnapshot = outcome.snapshot
                lastGoodSnapshotAtMs = clock()
            }
            val decision = when (outcome) {
                is TvInsightsFetchResult.Success -> insightsOffline.onLiveSuccess()
                is TvInsightsFetchResult.Failure ->
                    insightsOffline.onFetchFailure(lastGoodSnapshot != null, lastGoodSnapshotAtMs, clock())
            }
            val failureReason = (outcome as? TvInsightsFetchResult.Failure)?.reason
            val cached = lastGoodSnapshot
            uiDispatcher.dispatch {
                if (destroyed || token != insightsGeneration) return@dispatch
                when {
                    outcome is TvInsightsFetchResult.Success ->
                        surface.showInsights(outcome.snapshot, decision.status, decision.badgeText)
                    decision.showCached && cached != null ->
                        surface.showInsights(cached, decision.status, decision.badgeText)
                    else ->
                        surface.showInsightsError(
                            "${TvInsightsController.INSIGHTS_ERROR_PREFIX}: ${failureReason ?: "нет данных"}",
                        )
                }
            }
        }
    }

    /** Загрузка/перезагрузка вкладки «Логи» (файл + хвост). Только с UI-потока. */
    fun loadLogs(file: ServerLogFile = TvLogsPolicy.DEFAULT_FILE, tail: ServerLogTail = TvLogsPolicy.DEFAULT_TAIL) {
        if (destroyed) return
        val token = ++logsGeneration
        surface.showLogsLoading()
        executor.execute {
            val outcome = logsGateway.fetchLogs(file, tail)
            if (outcome is TvLogsFetchResult.Success) {
                lastGoodWindow = outcome.window
                lastGoodWindowAtMs = clock()
            }
            val decision = when (outcome) {
                is TvLogsFetchResult.Success -> logsOffline.onLiveSuccess()
                is TvLogsFetchResult.Failure ->
                    logsOffline.onFetchFailure(lastGoodWindow != null, lastGoodWindowAtMs, clock())
            }
            val failureReason = (outcome as? TvLogsFetchResult.Failure)?.reason
            val cached = lastGoodWindow
            uiDispatcher.dispatch {
                if (destroyed || token != logsGeneration) return@dispatch
                when {
                    outcome is TvLogsFetchResult.Success ->
                        surface.showLogs(outcome.window, decision.status, decision.badgeText)
                    decision.showCached && cached != null ->
                        surface.showLogs(cached, decision.status, decision.badgeText)
                    else ->
                        surface.showLogsError("${LOGS_ERROR_PREFIX}: ${failureReason ?: "нет данных"}")
                }
            }
        }
    }

    /** Из Activity#onDestroy: поздние фоновые результаты игнорируются. */
    fun destroy() {
        destroyed = true
        insightsGeneration++
        logsGeneration++
    }

    companion object {
        const val INSIGHTS_ERROR_PREFIX = "Аналитика недоступна"
        const val LOGS_ERROR_PREFIX = TvLogsPolicy.ERROR_GENERIC

        /** Общий фоновый исполнитель загрузок (HTTP + парсинг), daemon-поток. */
        val SHARED_EXECUTOR: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tv-insights-io").apply { isDaemon = true }
        }
    }
}
