package ru.mars.tv.todos

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import ru.mars.network.cache.TodosOfflineCache
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.TodoSnapshot
import ru.mars.network.repository.TodosRepository
import ru.mars.tv.MainThreadTvDispatcher
import ru.mars.tv.TvOfflineCacheStatus
import ru.mars.tv.TvUiDispatcher
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Load result of the «Задачи» screen: the resolved session title plus its latest
 * `todo_state` snapshot (`null` snapshot = the session never wrote todos — a valid
 * state, [ru.mars.network.repository.SessionTodos] contract; `null` title with a
 * `null` snapshot = no candidate session at all).
 */
data class TvTodosData(
    val sessionTitle: String?,
    val snapshot: TodoSnapshot?,
)

/** Результат transport-слоя: данные либо человекочитаемая причина отказа. */
sealed interface TvTodosFetchResult {
    data class Success(val data: TvTodosData) : TvTodosFetchResult
    /** Причина для UI — без сырых исключений/JSON/URL (гигиена сообщений). */
    data class Failure(val reason: String) : TvTodosFetchResult
}

/**
 * Data-seam между [TvTodosController] и core-network (паттерн TvProjectsGateway).
 * На устройстве — [RepositoryTvTodosGateway]; unit-тесты подставляют fake без HTTP.
 */
interface TvTodosGateway {
    /** Блокирующий вызов; исполняется ТОЛЬКО на background-executor контроллера. */
    fun fetchTodos(): TvTodosFetchResult
}

/**
 * Продакшн-реализация: [TodosRepository] (GET /api/sessions, затем GET /api/session
 * с `todo_state`) на общем baseUrl клиентов Марса. Токен читается провайдером
 * (AndroidKeyStoreCredentialStore) и никогда не покидает этот класс. Отсутствующая
 * пара — явная причина [NO_CREDENTIAL]; тексты исключений наружу не выпускаются.
 */
class RepositoryTvTodosGateway(
    private val repository: TodosRepository,
    private val baseUrl: String,
    private val credentialProvider: () -> ConsumedDeviceCredential?,
) : TvTodosGateway {

    override fun fetchTodos(): TvTodosFetchResult {
        val credential = credentialProvider() ?: return TvTodosFetchResult.Failure(NO_CREDENTIAL)
        return try {
            runBlocking {
                val todos = repository.fetchCurrentTodos(baseUrl, credential)
                TvTodosFetchResult.Success(TvTodosData(todos?.session?.title, todos?.snapshot))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TvTodosFetchResult.Failure("сбой запроса")
        }
    }

    companion object {
        /** Явная причина при отсутствующей паре устройства (экран pairing не пройден). */
        const val NO_CREDENTIAL = "устройство не сопряжено"
    }
}

/**
 * Surface TV-экрана задач: loading / данные / ошибка. Состояние «задачи не ставились»,
 * пустой список и пустая выборка фильтра — НЕ ошибки: их различает
 * [TvTodosPolicy.summaryLine]/[TvTodosPolicy.emptyLine] из одних и тех же данных.
 * Контроллер вызывает эти методы на [TvUiDispatcher]-потоке (в продакшне — UI).
 */
interface TvTodosSurface {
    fun showLoading()

    /**
     * Данные готовы к отрисовке: [status] — источник ([TvOfflineCacheStatus.LIVE] —
     * свежая сеть, [TvOfflineCacheStatus.CACHED] — офлайн-снимок), [badgeText] —
     * готовая плашка для live-региона («Обновлено только что» / «Снимок из кэша
     * (N мин. назад)»). CACHED рисуется как обычные данные: экран не пустеет,
     * фокус пульта не переставляется.
     */
    fun showTodos(data: TvTodosData, status: TvOfflineCacheStatus, badgeText: String?)

    /** Явное сообщение о недоступности; никогда не содержит сырой JSON/HTML. */
    fun showError(message: String)
}

/**
 * Оркестрация экрана задач (seam-паттерн [ru.mars.tv.projects.TvProjectsController]):
 *
 * - [load] синхронно объявляет [TvTodosSurface.showLoading] на UI-потоке, затем один
 *   фоновый вызов [TvTodosGateway.fetchTodos] на [executor]; результат возвращается
 *   через [uiDispatcher].
 * - Офлайн-кэш ([TodosOfflineCache], необязательный): успешный снапшот сохраняется на
 *   фоновом потоке сразу после получения; при сетевом сбое контроллер достаёт снимок и
 *   отдаёт его через [TvTodosOfflinePolicy] как CACHED с возрастной плашкой вместо
 *   ошибки — экран не пустеет, фокус пульта остаётся на месте. Все обращения к кэшу
 *   конфайнятся к однопоточному [executor] (контракт «не потокобезопасен»).
 * - Generation guard: результат устаревшей загрузки отбрасывается, если стартовала
 *   более новая; [destroy] инвалидирует все in-flight результаты, новые загрузки
 *   не стартуют.
 * - Ошибки явные: «Задачи недоступны: <причина>» — только когда показывать нечего
 *   (сбой сети И кэш пуст/просрочен/не внедрён). Данные без сессии/снапшота —
 *   не ошибка: surface получает [TvTodosData] как есть, честную копию выбирает политика.
 *
 * Все мутируемые поля конфайнятся к UI-потоку (контракт TvInteractiveController).
 */
class TvTodosController(
    private val surface: TvTodosSurface,
    private val gateway: TvTodosGateway,
    /** Офлайн-кэш `todo_state`; null — работа без кэша (прежнее поведение). */
    private val offlineCache: TodosOfflineCache? = null,
    /** Часы для расчёта возраста снимка; инъекция делает тесты детерминированными. */
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val executor: Executor = SHARED_EXECUTOR,
    private val uiDispatcher: TvUiDispatcher = MainThreadTvDispatcher(),
) {

    // Конфайнмент к UI-dispatcher потоку: пишутся только из load()/destroy().
    private var generation = 0
    private var destroyed = false

    /** Запустить загрузку задач (первичную, периодическую или retry). Только с UI-потока. */
    fun load() {
        if (destroyed) return
        val token = ++generation
        surface.showLoading()
        executor.execute {
            val outcome = resolveOutcome()
            uiDispatcher.dispatch {
                if (destroyed || token != generation) return@dispatch
                when (outcome) {
                    is Outcome.Data -> {
                        val data = outcome.decision.data ?: return@dispatch
                        surface.showTodos(data, outcome.decision.status, outcome.decision.badgeText)
                    }
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
     * Фоновое разрешение загрузки: сеть -> сохранение в кэш -> LIVE, либо сбой ->
     * снимок из кэша через [TvTodosOfflinePolicy] (CACHED) или явная ошибка (ERROR).
     * Исполняется ТОЛЬКО на [executor] — обращения к кэшу однопоточные.
     */
    private fun resolveOutcome(): Outcome {
        return when (val result = gateway.fetchTodos()) {
            is TvTodosFetchResult.Success -> {
                // Кэшируем только реальный снапшот: null («задачи не ставились») не
                // должен вытеснять последний живой снимок. Глобальный слот (null-id):
                // экран всегда показывает «текущие» задачи, не список сессий.
                result.data.snapshot?.let { offlineCache?.saveTodos(GLOBAL_SLOT, it) }
                Outcome.Data(TvTodosOfflinePolicy.onLiveSuccess(result.data))
            }
            is TvTodosFetchResult.Failure -> {
                val decision = TvTodosOfflinePolicy.onFetchFailure(
                    cachedSnapshot = offlineCache?.loadTodos(GLOBAL_SLOT),
                    savedAtMs = offlineCache?.getSnapshotTimestamp(GLOBAL_SLOT),
                    nowMs = clock(),
                )
                if (decision.status == TvOfflineCacheStatus.ERROR) {
                    Outcome.Error("$ERROR_PREFIX: ${result.reason}")
                } else {
                    Outcome.Data(decision)
                }
            }
        }
    }

    private sealed interface Outcome {
        data class Data(val decision: TvTodosOfflineDecision) : Outcome
        data class Error(val message: String) : Outcome
    }

    companion object {
        const val ERROR_PREFIX = "Задачи недоступны"

        /** Глобальный слот кэша: «текущие» задачи экрана без привязки к id сессии. */
        private val GLOBAL_SLOT: String? = null

        /** Общий фоновый исполнитель загрузок (HTTP + парсинг), daemon-поток. */
        val SHARED_EXECUTOR: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tv-todos-io").apply { isDaemon = true }
        }
    }
}
