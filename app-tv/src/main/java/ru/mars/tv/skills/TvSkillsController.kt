package ru.mars.tv.skills

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

/** Результат transport-слоя: каталог со статистикой либо причина отказа. */
sealed interface TvSkillsFetchResult {
    data class Success(val data: TvSkillsData) : TvSkillsFetchResult
    /** Причина для UI — без сырых исключений/JSON/URL (гигиена сообщений). */
    data class Failure(val reason: String) : TvSkillsFetchResult
}

/**
 * Data-seam между [TvSkillsController] и сетью (паттерн TvSpacesGateway).
 * На устройстве — [HttpTvSkillsGateway]; unit-тесты подставляют fake без HTTP.
 */
interface TvSkillsGateway {
    /** Блокирующий вызов; исполняется ТОЛЬКО на background-executor контроллера. */
    fun fetchSkills(): TvSkillsFetchResult
}

/**
 * Продакшн-реализация: `GET /api/skills` (каталог) + `GET /api/skills/usage`
 * (статистика использования). Каталог входит в `NATIVE_BEARER_EXACT_PATHS`
 * белого списка нативного device-токена; мутация /api/skills/save токеном не
 * принимается — экран строго read-only.
 *
 * ВАЖНО про usage: маршрут `/api/skills/usage` в белый список нативного токена
 * НЕ входит (проверено по hermes-webui api/auth.py) — device-токен на нём
 * получает 401, поэтому статистика запрашивается fail-soft: отказ не роняет
 * загрузку каталога, [TvSkillsData.usage] остаётся `null` и экран честно
 * показывает каталог без счётчиков. Если сервер добавит маршрут в белый
 * список — бейджи «использован N раз» и сортировка по использованиям
 * заработают автоматически, без правки клиента.
 *
 * Транспортный отказ и нераспарсенный каталог различимы: сбой — всегда
 * [TvSkillsFetchResult.Failure], а не «пустой каталог».
 * Токен читается провайдером и никогда не покидает этот класс.
 */
class HttpTvSkillsGateway(
    private val baseUrl: String,
    private val credentialProvider: () -> ConsumedDeviceCredential?,
    private val client: OkHttpClient = HttpClients.rest,
) : TvSkillsGateway {

    override fun fetchSkills(): TvSkillsFetchResult {
        val credential = credentialProvider() ?: return TvSkillsFetchResult.Failure(NO_CREDENTIAL)
        return try {
            val catalogPayload = getRaw("api/skills", credential)
                ?: return TvSkillsFetchResult.Failure("сбой запроса")
            val skills = TvSkillsPolicy.parseCatalog(catalogPayload)
                ?: return TvSkillsFetchResult.Failure("ответ не распарсился")
            // Статистика — best effort: любой отказ (401 вне белого списка,
            // сетевой сбой, битый JSON) оставляет usage == null.
            val usage = getRaw("api/skills/usage", credential)?.let { TvSkillsPolicy.parseUsage(it) }
            TvSkillsFetchResult.Success(TvSkillsData(skills = skills, usage = usage))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TvSkillsFetchResult.Failure("сбой запроса")
        }
    }

    /** Один GET c Bearer-токеном; тело при 2xx, иначе null. */
    private fun getRaw(segments: String, credential: ConsumedDeviceCredential): String? {
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegments(segments).build().toString()
        return client.newCall(
            Request.Builder().url(url)
                .header("Authorization", "Bearer ${credential.accessToken}")
                .get().build()
        ).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }

    companion object {
        /** Явная причина при отсутствующей паре устройства (экран pairing не пройден). */
        const val NO_CREDENTIAL = "устройство не сопряжено"
    }
}

/**
 * Surface TV-экрана «Скиллы»: loading / каталог с системным статусом / ошибка.
 * Пустой каталог — НЕ ошибка: его отличает [TvSkillsPolicy.EMPTY_LIST].
 * Контроллер вызывает эти методы на [TvUiDispatcher]-потоке.
 */
interface TvSkillsSurface {
    fun showLoading()
    fun showSkills(data: TvSkillsData, status: TvOfflineCacheStatus, badgeText: String?)
    /** Явное сообщение о недоступности; никогда не содержит сырой JSON/HTML. */
    fun showError(message: String)
}

/**
 * Оркестрация экрана «Скиллы» (seam-паттерн [ru.mars.tv.spaces.TvSpacesController]):
 *
 * - [load] синхронно объявляет [TvSkillsSurface.showLoading] на UI-потоке, затем
 *   один фоновый вызов [TvSkillsGateway.fetchSkills] на [executor]; результат
 *   возвращается через [uiDispatcher];
 * - Generation guard: устаревший результат отбрасывается; [destroy] инвалидирует
 *   все in-flight;
 * - Offline ([TvOfflineSnapshotPolicy]): последний удачный снимок (каталог +
 *   статистика) кэшируется в памяти контроллера; при сетевом сбое пригодный кэш
 *   (не старше [TvSkillsPolicy.CACHE_MAX_AGE_MS], 24 ч) показывается как CACHED
 *   с возрастной плашкой вместо ошибки — фокус пульта не переставляется на
 *   «Повторить».
 *
 * Все мутируемые поля конфайнятся: generation/destroyed — к UI-потоку,
 * lastGood* — к executor-потоку (единственный daemon-поток SHARED_EXECUTOR).
 */
class TvSkillsController(
    private val surface: TvSkillsSurface,
    private val gateway: TvSkillsGateway,
    private val executor: Executor = SHARED_EXECUTOR,
    private val uiDispatcher: TvUiDispatcher = MainThreadTvDispatcher(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    // Конфайнмент к UI-dispatcher потоку: пишутся только из load()/destroy().
    private var generation = 0
    private var destroyed = false

    // Конфайнмент к executor-потоку.
    private var lastGood: TvSkillsData? = null
    private var lastGoodAtMs: Long? = null

    private val offline = TvOfflineSnapshotPolicy(TvSkillsPolicy.CACHE_MAX_AGE_MS)

    /** Запустить загрузку каталога (первичную, периодическую или retry). Только с UI-потока. */
    fun load() {
        if (destroyed) return
        val token = ++generation
        surface.showLoading()
        executor.execute {
            val outcome = gateway.fetchSkills()
            if (outcome is TvSkillsFetchResult.Success) {
                lastGood = outcome.data
                lastGoodAtMs = clock()
            }
            val decision = when (outcome) {
                is TvSkillsFetchResult.Success -> offline.onLiveSuccess()
                is TvSkillsFetchResult.Failure -> offline.onFetchFailure(lastGood != null, lastGoodAtMs, clock())
            }
            val failureReason = (outcome as? TvSkillsFetchResult.Failure)?.reason
            val cached = lastGood
            uiDispatcher.dispatch {
                if (destroyed || token != generation) return@dispatch
                when {
                    outcome is TvSkillsFetchResult.Success ->
                        surface.showSkills(outcome.data, decision.status, decision.badgeText)
                    decision.showCached && cached != null ->
                        surface.showSkills(cached, decision.status, decision.badgeText)
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
        const val ERROR_PREFIX = "Скиллы недоступны"

        /** Общий фоновый исполнитель загрузок (HTTP + парсинг), daemon-поток. */
        val SHARED_EXECUTOR: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tv-skills-io").apply { isDaemon = true }
        }
    }
}
