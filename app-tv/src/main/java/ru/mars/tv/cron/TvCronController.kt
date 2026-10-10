package ru.mars.tv.cron

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.CronJobsResponse
import ru.mars.network.repository.CronRepository
import ru.mars.tv.MainThreadTvDispatcher
import ru.mars.tv.TvUiDispatcher
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Результат transport-слоя: ответ планировщика либо человекочитаемая причина отказа. */
sealed interface TvCronFetchResult {
    data class Success(val response: CronJobsResponse) : TvCronFetchResult
    /** Причина для UI — без сырых исключений/JSON/URL (гигиена сообщений). */
    data class Failure(val reason: String) : TvCronFetchResult
}

/**
 * Data-seam между [TvCronController] и core-network (паттерн TvProjectsGateway).
 * На устройстве — [RepositoryTvCronGateway]; unit-тесты подставляют fake без HTTP.
 */
interface TvCronGateway {
    /** Блокирующий вызов; исполняется ТОЛЬКО на background-executor контроллера. */
    fun fetchJobs(): TvCronFetchResult
}

/**
 * Продакшн-реализация: [CronRepository.getCronJobs] (`GET /api/crons`) на общем baseUrl
 * клиентов Марса. Токен читается провайдером (AndroidKeyStoreCredentialStore) и никогда
 * не покидает этот класс. Транспортный отказ репозиторий гасит в пустой ответ
 * (fail-soft контракт) — его честно озвучивает [TvCronPolicy.EMPTY_LIST].
 */
class RepositoryTvCronGateway(
    private val repository: CronRepository,
    private val baseUrl: String,
    private val credentialProvider: () -> ConsumedDeviceCredential?,
) : TvCronGateway {

    override fun fetchJobs(): TvCronFetchResult {
        val credential = credentialProvider() ?: return TvCronFetchResult.Failure(NO_CREDENTIAL)
        return try {
            runBlocking {
                TvCronFetchResult.Success(repository.getCronJobs(baseUrl, credential))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TvCronFetchResult.Failure("сбой запроса")
        }
    }

    companion object {
        /** Явная причина при отсутствующей паре устройства (экран pairing не пройден). */
        const val NO_CREDENTIAL = "устройство не сопряжено"
    }
}

/**
 * Surface TV-экрана планировщика: loading / список задач с системным статусом / ошибка.
 * Пустой список и `cron_unavailable` — НЕ ошибки: их различает [TvCronPolicy] из одного
 * и того же [CronJobsResponse]. Контроллер вызывает эти методы на [TvUiDispatcher]-потоке.
 */
interface TvCronSurface {
    fun showLoading()
    fun showJobs(response: CronJobsResponse)
    /** Явное сообщение о недоступности; никогда не содержит сырой JSON/HTML. */
    fun showError(message: String)
}

/**
 * Оркестрация экрана мониторинга cron (seam-паттерн [ru.mars.tv.projects.TvProjectsController]):
 *
 * - [load] синхронно объявляет [TvCronSurface.showLoading] на UI-потоке, затем один
 *   фоновый вызов [TvCronGateway.fetchJobs] на [executor]; результат возвращается
 *   через [uiDispatcher].
 * - Generation guard: результат устаревшей загрузки отбрасывается, если стартовала
 *   более новая; [destroy] инвалидирует все in-flight результаты, новые загрузки
 *   не стартуют.
 * - Ошибки явные: «Расписания недоступны: <причина>».
 *
 * Все мутируемые поля конфайнятся к UI-потоку (контракт TvInteractiveController).
 */
class TvCronController(
    private val surface: TvCronSurface,
    private val gateway: TvCronGateway,
    private val executor: Executor = SHARED_EXECUTOR,
    private val uiDispatcher: TvUiDispatcher = MainThreadTvDispatcher(),
) {

    // Конфайнмент к UI-dispatcher потоку: пишутся только из load()/destroy().
    private var generation = 0
    private var destroyed = false

    /** Запустить загрузку расписаний (первичную, периодическую или retry). Только с UI-потока. */
    fun load() {
        if (destroyed) return
        val token = ++generation
        surface.showLoading()
        executor.execute {
            val outcome = gateway.fetchJobs()
            uiDispatcher.dispatch {
                if (destroyed || token != generation) return@dispatch
                when (outcome) {
                    is TvCronFetchResult.Success -> surface.showJobs(outcome.response)
                    is TvCronFetchResult.Failure -> surface.showError("$ERROR_PREFIX: ${outcome.reason}")
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
        const val ERROR_PREFIX = "Расписания недоступны"

        /** Общий фоновый исполнитель загрузок (HTTP + парсинг), daemon-поток. */
        val SHARED_EXECUTOR: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tv-cron-io").apply { isDaemon = true }
        }
    }
}
