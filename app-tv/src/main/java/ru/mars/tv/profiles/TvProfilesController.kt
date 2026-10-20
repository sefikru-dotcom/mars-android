package ru.mars.tv.profiles

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.ProfilesResponse
import ru.mars.network.repository.ProfilesRepository
import ru.mars.tv.MainThreadTvDispatcher
import ru.mars.tv.TvOfflineCacheStatus
import ru.mars.tv.TvOfflineSnapshotPolicy
import ru.mars.tv.TvUiDispatcher
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Результат transport-слоя: каталог профилей либо причина отказа. */
sealed interface TvProfilesFetchResult {
    data class Success(val response: ProfilesResponse) : TvProfilesFetchResult
    /** Причина для UI — без сырых исключений/JSON/URL (гигиена сообщений). */
    data class Failure(val reason: String) : TvProfilesFetchResult
}

/**
 * Data-seam между [TvProfilesController] и core-network (паттерн TvSpacesGateway).
 * На устройстве — [RepositoryTvProfilesGateway]; unit-тесты подставляют fake без HTTP.
 *
 * У интерфейса ровно один метод чтения: мутаций профиля в TV-клиенте нет by design.
 */
interface TvProfilesGateway {
    /** Блокирующий вызов; исполняется ТОЛЬКО на background-executor контроллера. */
    fun fetchProfiles(): TvProfilesFetchResult
}

/**
 * Продакшн-реализация: [ProfilesRepository.getProfiles] (`GET /api/profiles`).
 * Маршрут входит в `NATIVE_BEARER_EXACT_PATHS` белого списка нативного
 * device-токена (hermes-webui api/auth.py) — GET каталога разрешён; мутации
 * профиля токеном не принимаются, поэтому экран строго read-only. Репозиторий
 * fail-soft: транспортный отказ приходит как `null` и честно озвучивается
 * [TvProfilesFetchResult.Failure]. Токен читается провайдером и никогда не
 * покидает этот класс.
 */
class RepositoryTvProfilesGateway(
    private val repository: ProfilesRepository,
    private val baseUrl: String,
    private val credentialProvider: () -> ConsumedDeviceCredential?,
) : TvProfilesGateway {

    override fun fetchProfiles(): TvProfilesFetchResult {
        val credential = credentialProvider() ?: return TvProfilesFetchResult.Failure(NO_CREDENTIAL)
        return try {
            val response = runBlocking { repository.getProfiles(baseUrl, credential) }
            if (response != null) {
                TvProfilesFetchResult.Success(response)
            } else {
                TvProfilesFetchResult.Failure(REQUEST_FAILED)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TvProfilesFetchResult.Failure(REQUEST_FAILED)
        }
    }

    companion object {
        /** Явная причина при отсутствующей паре устройства (экран pairing не пройден). */
        const val NO_CREDENTIAL = "устройство не сопряжено"

        /** Отказ транспорта/разбора без подробностей (гигиена сообщений). */
        const val REQUEST_FAILED = "сбой запроса"
    }
}

/**
 * Surface TV-экрана «Профили»: loading / каталог с системным статусом / ошибка.
 * Пустой каталог — НЕ ошибка: его отличает [TvProfilesPolicy.EMPTY_LIST].
 * Контроллер вызывает эти методы на [TvUiDispatcher]-потоке.
 */
interface TvProfilesSurface {
    fun showLoading()
    fun showProfiles(response: ProfilesResponse, status: TvOfflineCacheStatus, badgeText: String?)
    /** Явное сообщение о недоступности; никогда не содержит сырой JSON/HTML. */
    fun showError(message: String)
}

/**
 * Оркестрация экрана «Профили» (seam-паттерн [ru.mars.tv.spaces.TvSpacesController]):
 *
 * - [load] синхронно объявляет [TvProfilesSurface.showLoading] на UI-потоке, затем
 *   один фоновый вызов [TvProfilesGateway.fetchProfiles] на [executor]; результат
 *   возвращается через [uiDispatcher];
 * - Generation guard: устаревший результат отбрасывается; [destroy] инвалидирует
 *   все in-flight;
 * - Offline ([TvOfflineSnapshotPolicy]): последний удачный каталог кэшируется в
 *   памяти контроллера; при сетевом сбое пригодный кэш (не старше
 *   [TvProfilesPolicy.CACHE_MAX_AGE_MS], 24 ч) показывается как CACHED с возрастной
 *   плашкой вместо ошибки — фокус пульта не переставляется на «Повторить».
 *
 * Контроллер не умеет менять профили: единственный его вызов — чтение каталога.
 *
 * Все мутируемые поля конфайнятся: generation/destroyed — к UI-потоку,
 * lastGood* — к executor-потоку (единственный daemon-поток SHARED_EXECUTOR).
 */
class TvProfilesController(
    private val surface: TvProfilesSurface,
    private val gateway: TvProfilesGateway,
    private val executor: Executor = SHARED_EXECUTOR,
    private val uiDispatcher: TvUiDispatcher = MainThreadTvDispatcher(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    // Конфайнмент к UI-dispatcher потоку: пишутся только из load()/destroy().
    private var generation = 0
    private var destroyed = false

    // Конфайнмент к executor-потоку.
    private var lastGood: ProfilesResponse? = null
    private var lastGoodAtMs: Long? = null

    private val offline = TvOfflineSnapshotPolicy(TvProfilesPolicy.CACHE_MAX_AGE_MS)

    /** Запустить загрузку каталога (первичную, периодическую или retry). Только с UI-потока. */
    fun load() {
        if (destroyed) return
        val token = ++generation
        surface.showLoading()
        executor.execute {
            val outcome = gateway.fetchProfiles()
            if (outcome is TvProfilesFetchResult.Success) {
                lastGood = outcome.response
                lastGoodAtMs = clock()
            }
            val decision = when (outcome) {
                is TvProfilesFetchResult.Success -> offline.onLiveSuccess()
                is TvProfilesFetchResult.Failure -> offline.onFetchFailure(lastGood != null, lastGoodAtMs, clock())
            }
            val failureReason = (outcome as? TvProfilesFetchResult.Failure)?.reason
            val cached = lastGood
            uiDispatcher.dispatch {
                if (destroyed || token != generation) return@dispatch
                when {
                    outcome is TvProfilesFetchResult.Success ->
                        surface.showProfiles(outcome.response, decision.status, decision.badgeText)
                    decision.showCached && cached != null ->
                        surface.showProfiles(cached, decision.status, decision.badgeText)
                    else ->
                        surface.showError(
                            "$ERROR_PREFIX: ${failureReason?.takeIf(String::isNotBlank) ?: NO_DATA}"
                        )
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
        const val ERROR_PREFIX = "Профили недоступны"

        /** Причина отказа без деталей (контроллер не сочиняет прозу). */
        const val NO_DATA = "нет данных"

        /** Общий фоновый исполнитель загрузок (HTTP + парсинг), daemon-поток. */
        val SHARED_EXECUTOR: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tv-profiles-io").apply { isDaemon = true }
        }
    }
}
