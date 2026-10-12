package ru.mars.tv.memory

import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.mars.network.http.HttpClients
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.tv.MainThreadTvDispatcher
import ru.mars.tv.TvOfflineCacheStatus
import ru.mars.tv.TvOfflineSnapshotPolicy
import ru.mars.tv.TvUiDispatcher
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Результат transport-слоя: снимок памяти либо причина отказа. */
sealed interface TvMemoryFetchResult {
    data class Success(val snapshot: TvMemorySnapshot) : TvMemoryFetchResult
    /** Причина для UI — без сырых исключений/JSON/URL (гигиена сообщений). */
    data class Failure(val reason: String) : TvMemoryFetchResult
}

/**
 * Data-seam между [TvMemoryController] и сетью (паттерн TvSpacesGateway).
 * На устройстве — [HttpTvMemoryGateway]; unit-тесты подставляют fake без HTTP.
 */
interface TvMemoryGateway {
    /** Блокирующий вызов; исполняется ТОЛЬКО на background-executor контроллера. */
    fun fetchMemory(): TvMemoryFetchResult
}

/**
 * Продакшн-реализация: `GET /api/memory` (контракт hermes-webui
 * api/route_memory.py). Маршрут входит в `NATIVE_BEARER_EXACT_PATHS` белого
 * списка нативного device-токена — GET разрешён; мутация /api/memory/write
 * токеном не принимается, поэтому экран строго read-only. В отличие от
 * fail-soft репозиториев core-network, транспортный отказ и нераспарсенный
 * ответ различимы: пустые секции — валидные данные, а сбой — всегда
 * [TvMemoryFetchResult.Failure] (экран никогда не «пустеет» молча).
 * Токен читается провайдером и никогда не покидает этот класс.
 */
class HttpTvMemoryGateway(
    private val baseUrl: String,
    private val credentialProvider: () -> ConsumedDeviceCredential?,
    private val client: OkHttpClient = HttpClients.rest,
) : TvMemoryGateway {

    override fun fetchMemory(): TvMemoryFetchResult {
        val credential = credentialProvider() ?: return TvMemoryFetchResult.Failure(NO_CREDENTIAL)
        return try {
            val url = baseUrl.toHttpUrl().newBuilder().addPathSegments("api/memory").build().toString()
            val payload = client.newCall(
                Request.Builder().url(url)
                    .header("Authorization", "Bearer ${credential.accessToken}")
                    .get().build()
            ).execute().use { if (it.isSuccessful) it.body?.string() else null }
            when {
                payload == null -> TvMemoryFetchResult.Failure("сбой запроса")
                else -> TvMemoryPolicy.parse(payload)?.let(TvMemoryFetchResult::Success)
                    ?: TvMemoryFetchResult.Failure("ответ не распарсился")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TvMemoryFetchResult.Failure("сбой запроса")
        }
    }

    companion object {
        /** Явная причина при отсутствующей паре устройства (экран pairing не пройден). */
        const val NO_CREDENTIAL = "устройство не сопряжено"
    }
}

/**
 * Surface TV-экрана «Память»: loading / снимок с системным статусом / ошибка.
 * Пустая секция — НЕ ошибка: её отличает [TvMemoryPolicy.emptyLine].
 * Контроллер вызывает эти методы на [TvUiDispatcher]-потоке.
 */
interface TvMemorySurface {
    fun showLoading()
    fun showMemory(snapshot: TvMemorySnapshot, status: TvOfflineCacheStatus, badgeText: String?)
    /** Явное сообщение о недоступности; никогда не содержит сырой JSON/HTML. */
    fun showError(message: String)
}

/**
 * Оркестрация экрана «Память» (seam-паттерн [ru.mars.tv.spaces.TvSpacesController]):
 *
 * - [load] синхронно объявляет [TvMemorySurface.showLoading] на UI-потоке, затем
 *   один фоновый вызов [TvMemoryGateway.fetchMemory] на [executor]; результат
 *   возвращается через [uiDispatcher];
 * - Generation guard: устаревший результат отбрасывается; [destroy] инвалидирует
 *   все in-flight;
 * - Offline ([TvOfflineSnapshotPolicy]): последний удачный снимок кэшируется в
 *   памяти контроллера; при сетевом сбое пригодный кэш (не старше
 *   [TvMemoryPolicy.CACHE_MAX_AGE_MS], 24 ч) показывается как CACHED с возрастной
 *   плашкой вместо ошибки — фокус пульта не переставляется на «Повторить».
 *
 * Все мутируемые поля конфайнятся: generation/destroyed — к UI-потоку,
 * lastGood* — к executor-потоку (единственный daemon-поток SHARED_EXECUTOR).
 */
class TvMemoryController(
    private val surface: TvMemorySurface,
    private val gateway: TvMemoryGateway,
    private val executor: Executor = SHARED_EXECUTOR,
    private val uiDispatcher: TvUiDispatcher = MainThreadTvDispatcher(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    // Конфайнмент к UI-dispatcher потоку: пишутся только из load()/destroy().
    private var generation = 0
    private var destroyed = false

    // Конфайнмент к executor-потоку.
    private var lastGood: TvMemorySnapshot? = null
    private var lastGoodAtMs: Long? = null

    private val offline = TvOfflineSnapshotPolicy(TvMemoryPolicy.CACHE_MAX_AGE_MS)

    /** Запустить загрузку памяти (первичную, периодическую или retry). Только с UI-потока. */
    fun load() {
        if (destroyed) return
        val token = ++generation
        surface.showLoading()
        executor.execute {
            val outcome = gateway.fetchMemory()
            if (outcome is TvMemoryFetchResult.Success) {
                lastGood = outcome.snapshot
                lastGoodAtMs = clock()
            }
            val decision = when (outcome) {
                is TvMemoryFetchResult.Success -> offline.onLiveSuccess()
                is TvMemoryFetchResult.Failure -> offline.onFetchFailure(lastGood != null, lastGoodAtMs, clock())
            }
            val failureReason = (outcome as? TvMemoryFetchResult.Failure)?.reason
            val cached = lastGood
            uiDispatcher.dispatch {
                if (destroyed || token != generation) return@dispatch
                when {
                    outcome is TvMemoryFetchResult.Success ->
                        surface.showMemory(outcome.snapshot, decision.status, decision.badgeText)
                    decision.showCached && cached != null ->
                        surface.showMemory(cached, decision.status, decision.badgeText)
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
        const val ERROR_PREFIX = "Память недоступна"

        /** Общий фоновый исполнитель загрузок (HTTP + парсинг), daemon-поток. */
        val SHARED_EXECUTOR: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tv-memory-io").apply { isDaemon = true }
        }
    }
}
