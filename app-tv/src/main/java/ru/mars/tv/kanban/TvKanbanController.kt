package ru.mars.tv.kanban

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.KanbanBoard
import ru.mars.network.model.KanbanStats
import ru.mars.network.repository.KanbanBoardResult
import ru.mars.network.repository.KanbanRepository
import ru.mars.network.repository.KanbanStatsResult
import ru.mars.tv.MainThreadTvDispatcher
import ru.mars.tv.TvOfflineCacheStatus
import ru.mars.tv.TvOfflineSnapshotPolicy
import ru.mars.tv.TvUiDispatcher
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Данные экрана «Канбан»: доска + best-effort статистика (`null` — stats недоступны). */
data class TvKanbanData(
    val board: KanbanBoard,
    val stats: KanbanStats?,
)

/** Результат transport-слоя: данные либо человекочитаемая причина отказа. */
sealed interface TvKanbanFetchResult {
    data class Success(val data: TvKanbanData) : TvKanbanFetchResult
    /** Причина для UI — без сырых исключений/JSON/URL (гигиена сообщений). */
    data class Failure(val reason: String) : TvKanbanFetchResult
}

/**
 * Data-seam между [TvKanbanController] и core-network (паттерн TvCronGateway).
 * На устройстве — [RepositoryTvKanbanGateway]; unit-тесты подставляют fake без HTTP.
 */
interface TvKanbanGateway {
    /** Блокирующий вызов; исполняется ТОЛЬКО на background-executor контроллера. */
    fun fetchBoard(): TvKanbanFetchResult
}

/**
 * Продакшн-реализация: [KanbanRepository.getBoard] (`GET /api/kanban/board`) плюс
 * best-effort [KanbanRepository.getStats] (`GET /api/kanban/stats`) на общем baseUrl
 * клиентов Марса. Оба маршрута в server-side read-only белом списке нативного
 * device-токена (GET/HEAD) — экран строго read-only, мутаций нет. Токен читается
 * провайдером (AndroidKeyStoreCredentialStore) и никогда не покидает этот класс.
 *
 * Отказ статистики НЕ роняет загрузку доски: `stats = null`, доска показывается
 * без сводки (mobile `KanbanPresenter` parity — stats best-effort). Отказ доски —
 * явная причина: 404 («доска не найдена») и прочие сбои различимы.
 */
class RepositoryTvKanbanGateway(
    private val repository: KanbanRepository,
    private val baseUrl: String,
    private val credentialProvider: () -> ConsumedDeviceCredential?,
) : TvKanbanGateway {

    override fun fetchBoard(): TvKanbanFetchResult {
        val credential = credentialProvider() ?: return TvKanbanFetchResult.Failure(NO_CREDENTIAL)
        return try {
            runBlocking {
                when (val board = repository.getBoard(baseUrl, credential)) {
                    is KanbanBoardResult.Board -> {
                        val stats = when (val stats = repository.getStats(baseUrl, credential)) {
                            is KanbanStatsResult.Stats -> stats.stats
                            else -> null // best-effort: доска важнее сводки
                        }
                        TvKanbanFetchResult.Success(TvKanbanData(board.board, stats))
                    }
                    is KanbanBoardResult.NotFound -> TvKanbanFetchResult.Failure("доска не найдена")
                    is KanbanBoardResult.Failed -> TvKanbanFetchResult.Failure("сбой запроса")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TvKanbanFetchResult.Failure("сбой запроса")
        }
    }

    companion object {
        /** Явная причина при отсутствующей паре устройства (экран pairing не пройден). */
        const val NO_CREDENTIAL = "устройство не сопряжено"
    }
}

/**
 * Surface TV-экрана канбана: loading / доска с системным статусом / ошибка.
 * Пустая доска (ноль колонок) — НЕ ошибка: её отличает [TvKanbanPolicy.emptyLine].
 * Контроллер вызывает эти методы на [TvUiDispatcher]-потоке.
 */
interface TvKanbanSurface {
    fun showLoading()

    /**
     * Данные готовы к отрисовке: [status] — источник ([TvOfflineCacheStatus.LIVE] —
     * свежая сеть, [TvOfflineCacheStatus.CACHED] — последний удачный снимок),
     * [badgeText] — готовая плашка для live-региона. CACHED рисуется как обычные
     * данные: экран не пустеет, фокус пульта не переставляется.
     */
    fun showBoard(data: TvKanbanData, status: TvOfflineCacheStatus, badgeText: String?)

    /** Явное сообщение о недоступности; никогда не содержит сырой JSON/HTML. */
    fun showError(message: String)
}

/**
 * Оркестрация read-only экрана канбана (seam-паттерн [ru.mars.tv.cron.TvCronController]):
 *
 * - [load] синхронно объявляет [TvKanbanSurface.showLoading] на UI-потоке, затем один
 *   фоновый вызов [TvKanbanGateway.fetchBoard] на [executor]; результат возвращается
 *   через [uiDispatcher].
 * - Офлайн-кэш последнего удачного ответа (в памяти экрана, [TvOfflineSnapshotPolicy]):
 *   при сетевом сбое показывается последний удачный снимок как CACHED с возрастной
 *   плашкой вместо ошибки; явная ошибка — только когда показывать нечего. Кэш
 *   конфайнен к однопоточному [executor].
 * - Generation guard: результат устаревшей загрузки отбрасывается; [destroy]
 *   инвалидирует все in-flight результаты, новые загрузки не стартуют.
 * - Ошибки явные: «Канбан недоступен: <причина>».
 *
 * Все мутируемые поля конфайнятся к UI-потоку (контракт TvInteractiveController).
 */
class TvKanbanController(
    private val surface: TvKanbanSurface,
    private val gateway: TvKanbanGateway,
    private val executor: Executor = SHARED_EXECUTOR,
    private val uiDispatcher: TvUiDispatcher = MainThreadTvDispatcher(),
    private val offlinePolicy: TvOfflineSnapshotPolicy = OFFLINE_POLICY,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    // Конфайнмент к UI-dispatcher потоку: пишутся только из load()/destroy().
    private var generation = 0
    private var destroyed = false

    // Конфайнмент к executor-потоку: последний удачный ответ и метка времени.
    private var lastGood: TvKanbanData? = null
    private var lastGoodAtMs: Long? = null

    /** Запустить загрузку доски (первичную, периодическую или retry). Только с UI-потока. */
    fun load() {
        if (destroyed) return
        val token = ++generation
        surface.showLoading()
        executor.execute {
            val outcome = gateway.fetchBoard()
            val decision = when (outcome) {
                is TvKanbanFetchResult.Success -> {
                    lastGood = outcome.data
                    lastGoodAtMs = clock()
                    offlinePolicy.onLiveSuccess()
                }
                is TvKanbanFetchResult.Failure ->
                    offlinePolicy.onFetchFailure(lastGood != null, lastGoodAtMs, clock())
            }
            val failureReason = (outcome as? TvKanbanFetchResult.Failure)?.reason
            val cached = lastGood
            uiDispatcher.dispatch {
                if (destroyed || token != generation) return@dispatch
                when {
                    outcome is TvKanbanFetchResult.Success ->
                        surface.showBoard(outcome.data, decision.status, decision.badgeText)
                    decision.showCached && cached != null ->
                        surface.showBoard(cached, decision.status, decision.badgeText)
                    else ->
                        surface.showError("$ERROR_PREFIX: ${failureReason ?: "нет данных"}")
                }
            }
        }
    }

    /** Из Activity#onDestroy: поздние фоновые результаты игнорируются. */
    fun destroy() {
        destroyed = true
        generation++
    }

    companion object {
        const val ERROR_PREFIX = "Канбан недоступен"

        /** Окно свежести снимка доски — операционные данные, 12 ч (паритет «Задач»). */
        val OFFLINE_POLICY = TvOfflineSnapshotPolicy(TvKanbanPolicy.CACHE_MAX_AGE_MS)

        /** Общий фоновый исполнитель загрузок (HTTP + парсинг), daemon-поток. */
        val SHARED_EXECUTOR: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tv-kanban-io").apply { isDaemon = true }
        }
    }
}
