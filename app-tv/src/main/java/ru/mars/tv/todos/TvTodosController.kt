package ru.mars.tv.todos

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.TodoSnapshot
import ru.mars.network.repository.TodosRepository
import ru.mars.tv.MainThreadTvDispatcher
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
    fun showTodos(data: TvTodosData)
    /** Явное сообщение о недоступности; никогда не содержит сырой JSON/HTML. */
    fun showError(message: String)
}

/**
 * Оркестрация экрана задач (seam-паттерн [ru.mars.tv.projects.TvProjectsController]):
 *
 * - [load] синхронно объявляет [TvTodosSurface.showLoading] на UI-потоке, затем один
 *   фоновый вызов [TvTodosGateway.fetchTodos] на [executor]; результат возвращается
 *   через [uiDispatcher].
 * - Generation guard: результат устаревшей загрузки отбрасывается, если стартовала
 *   более новая; [destroy] инвалидирует все in-flight результаты, новые загрузки
 *   не стартуют.
 * - Ошибки явные: «Задачи недоступны: <причина>». Данные без сессии/снапшота —
 *   не ошибка: surface получает [TvTodosData] как есть, честную копию выбирает политика.
 *
 * Все мутируемые поля конфайнятся к UI-потоку (контракт TvInteractiveController).
 */
class TvTodosController(
    private val surface: TvTodosSurface,
    private val gateway: TvTodosGateway,
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
            val outcome = gateway.fetchTodos()
            uiDispatcher.dispatch {
                if (destroyed || token != generation) return@dispatch
                when (outcome) {
                    is TvTodosFetchResult.Success -> surface.showTodos(outcome.data)
                    is TvTodosFetchResult.Failure -> surface.showError("$ERROR_PREFIX: ${outcome.reason}")
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
        const val ERROR_PREFIX = "Задачи недоступны"

        /** Общий фоновый исполнитель загрузок (HTTP + парсинг), daemon-поток. */
        val SHARED_EXECUTOR: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tv-todos-io").apply { isDaemon = true }
        }
    }
}
