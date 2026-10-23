package ru.mars.network.repository

import ru.mars.network.cache.ProjectsOfflineCache
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.ProjectsSnapshot

/**
 * Источник списка проектов для экрана: сеть, офлайн-снимок или «данных нет».
 *
 * - [Live] — ответ сервера (в том числе валидный пустой список: сеть отработала);
 * - [Cached] — последний успешный снимок из [ProjectsOfflineCache] с моментом
 *   сохранения, чтобы хост подписал возраст («Кэш (N мин назад)»);
 * - [Unavailable] — показывать нечего: сбоя сети/кэша хватает, чтобы UI честно
 *   ушёл на статический фолбэк вместо выдуманных данных. [reason] — стабильный
 *   машиночитаемый тег (`no_snapshot`, `snapshot_expired`, …), [message] — текст
 *   отказа сети для логов/диагностики.
 */
sealed interface ProjectsFeed {
    data class Live(val snapshot: ProjectsSnapshot) : ProjectsFeed
    data class Cached(val snapshot: ProjectsSnapshot, val savedAtMs: Long) : ProjectsFeed
    data class Unavailable(val reason: String, val message: String = "") : ProjectsFeed
}

/**
 * Чтение списка проектов с офлайн-фолбэком — единая точка, где решается «сеть или
 * снимок» (паттерн `TvProjectsOfflinePolicy`, но без Android: JVM-тестируемо).
 *
 * Правила:
 * - успешный разбор ответа — [ProjectsFeed.Live], а сырое тело сохраняется в кэш:
 *   снимок, который нечем отрисовать, кэша не стоит, поэтому пишем только после
 *   разбора;
 * - сбой сети/не-2xx/битое тело — [ProjectsFeed.Cached] с пригодным снимком
 *   (возраст в пределах [maxAgeMs], метка времени есть, JSON разбирается);
 * - снимка нет, он просрочен, без метки или битый — [ProjectsFeed.Unavailable]:
 *   устаревший список хуже честного «данных нет», а битый JSON не должен
 *   выглядеть как пустой список проектов.
 *
 * [readCache] — мгновенный показ снимка на старте экрана, до ответа сети (как
 * `showCachedMetrics` у метрик): та же проверка пригодности, без HTTP.
 */
class ProjectsFeedLoader(
    private val repository: ProjectsRepository = ProjectsRepository(),
    private val cache: ProjectsOfflineCache? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
) {

    /** Снимок из кэша без обращения к сети (мгновенный старт экрана). */
    fun readCache(): ProjectsFeed = cachedOrUnavailable()

    /** Сеть, а при её отказе — снимок из кэша. */
    suspend fun load(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        allProfiles: Boolean = false,
    ): ProjectsFeed = when (val result = repository.getProjects(baseUrl, credential, allProfiles)) {
        is ProjectsResult.Loaded -> {
            // Пишем ровно то, что прислал сервер: кэш не теряет незнакомые поля.
            cache?.saveProjectsJson(result.rawJson)
            ProjectsFeed.Live(result.snapshot)
        }
        is ProjectsResult.Failed -> cachedOrUnavailable(result.message)
    }

    /**
     * Пригодный снимок или [ProjectsFeed.Unavailable]. Возраст клампится в ноль:
     * уехавшие назад часы (NTP, ручная правка) не должны «просрочивать» свежий снимок.
     */
    private fun cachedOrUnavailable(message: String = ""): ProjectsFeed {
        val store = cache ?: return ProjectsFeed.Unavailable(REASON_NO_SNAPSHOT, message)
        val json = store.loadProjectsJson()?.takeIf { it.isNotBlank() }
            ?: return ProjectsFeed.Unavailable(REASON_NO_SNAPSHOT, message)
        val savedAtMs = store.getSavedTimestamp()
            ?: return ProjectsFeed.Unavailable(REASON_UNVERIFIED, message)
        val ageMs = (clock() - savedAtMs).coerceAtLeast(0L)
        if (ageMs > maxAgeMs) return ProjectsFeed.Unavailable(REASON_EXPIRED, message)
        val snapshot = ProjectsRepository.parse(json)
            ?: return ProjectsFeed.Unavailable(REASON_UNREADABLE, message)
        return ProjectsFeed.Cached(snapshot, savedAtMs)
    }

    companion object {
        /**
         * Окно устаревания снимка — 24 часа, паритет с экранной политикой
         * (`OfflineCacheUiPolicy.PROJECTS_MAX_AGE_MS`): список проектов меняется
         * редко, но сутки — предел, после которого лучше честный фолбэк.
         */
        const val DEFAULT_MAX_AGE_MS = 24L * 60 * 60 * 1000

        const val REASON_NO_SNAPSHOT = "no_snapshot"
        const val REASON_UNVERIFIED = "snapshot_unverified"
        const val REASON_EXPIRED = "snapshot_expired"
        const val REASON_UNREADABLE = "snapshot_unreadable"
    }
}
