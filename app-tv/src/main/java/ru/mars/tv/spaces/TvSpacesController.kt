package ru.mars.tv.spaces

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.WorkspacesResponse
import ru.mars.network.repository.WorkspaceRepository
import ru.mars.tv.MainThreadTvDispatcher
import ru.mars.tv.TvOfflineCacheStatus
import ru.mars.tv.TvOfflineSnapshotPolicy
import ru.mars.tv.TvUiDispatcher
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Результат transport-слоя: каталог пространств либо причина отказа. */
sealed interface TvSpacesFetchResult {
    data class Success(val response: WorkspacesResponse) : TvSpacesFetchResult
    /** Причина для UI — без сырых исключений/JSON/URL (гигиена сообщений). */
    data class Failure(val reason: String) : TvSpacesFetchResult
}

/**
 * Data-seam между [TvSpacesController] и core-network (паттерн TvCronGateway).
 * На устройстве — [RepositoryTvSpacesGateway]; unit-тесты подставляют fake без HTTP.
 */
interface TvSpacesGateway {
    /** Блокирующий вызов; исполняется ТОЛЬКО на background-executor контроллера. */
    fun fetchSpaces(): TvSpacesFetchResult
}

/**
 * Продакшн-реализация: [WorkspaceRepository.getWorkspaces] (`GET /api/workspaces`).
 * Маршрут входит в `NATIVE_BEARER_EXACT_PATHS` белого списка нативного device-токена
 * (hermes-webui api/auth.py) — GET списка разрешён; мутации (/api/workspaces/add и
 * пр.) токеном не принимаются, поэтому экран строго read-only. Репозиторий fail-soft:
 * транспортный отказ приходит как `null` и честно озвучивается [TvSpacesFetchResult.Failure].
 * Токен читается провайдером и никогда не покидает этот класс.
 */
class RepositoryTvSpacesGateway(
    private val repository: WorkspaceRepository,
    private val baseUrl: String,
    private val credentialProvider: () -> ConsumedDeviceCredential?,
) : TvSpacesGateway {

    override fun fetchSpaces(): TvSpacesFetchResult {
        val credential = credentialProvider() ?: return TvSpacesFetchResult.Failure(NO_CREDENTIAL)
        return try {
            val response = runBlocking { repository.getWorkspaces(baseUrl, credential) }
            if (response != null) {
                TvSpacesFetchResult.Success(response)
            } else {
                TvSpacesFetchResult.Failure("сбой запроса")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TvSpacesFetchResult.Failure("сбой запроса")
        }
    }

    companion object {
        /** Явная причина при отсутствующей паре устройства (экран pairing не пройден). */
        const val NO_CREDENTIAL = "устройство не сопряжено"
    }
}

/**
 * Surface TV-экрана «Пространства»: loading / каталог с системным статусом / ошибка.
 * Пустой каталог — НЕ ошибка: его отличает [TvSpacesPolicy.EMPTY_LIST].
 * Контроллер вызывает эти методы на [TvUiDispatcher]-потоке.
 */
interface TvSpacesSurface {
    fun showLoading()
    fun showSpaces(response: WorkspacesResponse, status: TvOfflineCacheStatus, badgeText: String?)
    /** Явное сообщение о недоступности; никогда не содержит сырой JSON/HTML. */
    fun showError(message: String)
}

/**
 * Оркестрация экрана «Пространства» (seam-паттерн [ru.mars.tv.cron.TvCronController]):
 *
 * - [load] синхронно объявляет [TvSpacesSurface.showLoading] на UI-потоке, затем
 *   один фоновый вызов [TvSpacesGateway.fetchSpaces] на [executor]; результат
 *   возвращается через [uiDispatcher];
 * - Generation guard: устаревший результат отбрасывается; [destroy] инвалидирует
 *   все in-flight;
 * - Offline ([TvOfflineSnapshotPolicy]): последний удачный каталог кэшируется в
 *   памяти контроллера; при сетевом сбое пригодный кэш (не старше
 *   [TvSpacesPolicy.CACHE_MAX_AGE_MS], 24 ч) показывается как CACHED с возрастной
 *   плашкой вместо ошибки — фокус пульта не переставляется на «Повторить».
 *
 * Все мутируемые поля конфайнятся: generation/destroyed — к UI-потоку,
 * lastGood* — к executor-потоку (единственный daemon-поток SHARED_EXECUTOR).
 */
class TvSpacesController(
    private val surface: TvSpacesSurface,
    private val gateway: TvSpacesGateway,
    private val executor: Executor = SHARED_EXECUTOR,
    private val uiDispatcher: TvUiDispatcher = MainThreadTvDispatcher(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    // Конфайнмент к UI-dispatcher потоку: пишутся только из load()/destroy().
    private var generation = 0
    private var destroyed = false

    // Конфайнмент к executor-потоку.
    private var lastGood: WorkspacesResponse? = null
    private var lastGoodAtMs: Long? = null

    private val offline = TvOfflineSnapshotPolicy(TvSpacesPolicy.CACHE_MAX_AGE_MS)

    /** Запустить загрузку каталога (первичную, периодическую или retry). Только с UI-потока. */
    fun load() {
        if (destroyed) return
        val token = ++generation
        surface.showLoading()
        executor.execute {
            val outcome = gateway.fetchSpaces()
            if (outcome is TvSpacesFetchResult.Success) {
                lastGood = outcome.response
                lastGoodAtMs = clock()
            }
            val decision = when (outcome) {
                is TvSpacesFetchResult.Success -> offline.onLiveSuccess()
                is TvSpacesFetchResult.Failure -> offline.onFetchFailure(lastGood != null, lastGoodAtMs, clock())
            }
            val failureReason = (outcome as? TvSpacesFetchResult.Failure)?.reason
            val cached = lastGood
            uiDispatcher.dispatch {
                if (destroyed || token != generation) return@dispatch
                when {
                    outcome is TvSpacesFetchResult.Success ->
                        surface.showSpaces(outcome.response, decision.status, decision.badgeText)
                    decision.showCached && cached != null ->
                        surface.showSpaces(cached, decision.status, decision.badgeText)
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
        const val ERROR_PREFIX = "Пространства недоступны"

        /** Общий фоновый исполнитель загрузок (HTTP + парсинг), daemon-поток. */
        val SHARED_EXECUTOR: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tv-spaces-io").apply { isDaemon = true }
        }
    }
}
