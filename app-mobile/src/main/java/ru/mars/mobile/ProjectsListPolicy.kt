package ru.mars.mobile

import ru.mars.network.model.ProjectEntry
import ru.mars.network.repository.ProjectsFeed

/** Откуда взялся список проектов на экране. */
enum class ProjectsListSource {
    /** Ответ `GET /api/projects` (в том числе валидный пустой список). */
    SERVER,

    /** Последний успешный снимок из офлайн-кэша. */
    CACHE,

    /** Окончательный фолбэк — список, вшитый в приложение ([MarsProjects.all]). */
    STATIC,
}

/**
 * Тон бейджа секции: экран переводит его в цвет темы
 * ([MarsTheme.STATUS_ONLINE]/[MarsTheme.TEXT_MUTED]/[MarsTheme.STATUS_WARNING]),
 * сама политика от Android не зависит.
 */
enum class ProjectsListTone { OK, NEUTRAL, WARNING }

/**
 * Готовое к рендеру решение [ProjectsListPolicy.decide]: источник, карточки проектов,
 * текст бейджа секции, тон бейджа и стабильный машиночитаемый [reason] для тестов.
 */
data class ProjectsListDecision(
    val source: ProjectsListSource,
    val cards: List<ProjectStatus>,
    val badgeText: String,
    val tone: ProjectsListTone,
    val reason: String,
)

/**
 * Чистая политика выбора источника для секции «Проекты» экрана [ProjectsActivity]
 * (без Android и I/O — JVM-тесты: `ProjectsListPolicyTest`).
 *
 * Правила (продуктовый контракт экрана):
 * - живой ответ сервера — [ProjectsListSource.SERVER], даже если список пуст:
 *   пустой ответ — это факт «проектов нет», а не повод показать вшитый список;
 * - сбой сети/битый ответ при пригодном снимке — [ProjectsListSource.CACHE] с бейджем
 *   возраста из [OfflineCacheUiPolicy] (окно пригодности проверяет
 *   [ru.mars.network.repository.ProjectsFeedLoader], политика лишь подписывает возраст);
 * - показывать нечего — [ProjectsListSource.STATIC]: вшитый список остаётся
 *   ОКОНЧАТЕЛЬНЫМ фолбэком и помечается предупреждающим бейджем, чтобы устаревшие
 *   данные не выглядели живыми.
 */
object ProjectsListPolicy {

    /** Бейдж проекта активного профиля (совпадает с вшитым списком). */
    const val ACTIVE_BADGE = "Активен"

    /** Бейдж секции при живом ответе сервера. */
    const val BADGE_LIVE = "Сервер"

    /** Бейдж секции при валидном пустом ответе сервера. */
    const val BADGE_EMPTY = "Пусто"

    /** Бейдж секции на окончательном фолбэке. */
    const val BADGE_STATIC = "Из приложения"

    /**
     * Решение по фактическому источнику [feed]. [nowMs] нужен только для подписи
     * возраста снимка.
     */
    fun decide(
        feed: ProjectsFeed,
        staticProjects: List<ProjectStatus>,
        nowMs: Long,
    ): ProjectsListDecision = when (feed) {
        is ProjectsFeed.Live -> {
            val cards = feed.snapshot.projects.map { toCard(it, feed.snapshot.activeProfile) }
            ProjectsListDecision(
                source = ProjectsListSource.SERVER,
                cards = cards,
                badgeText = if (cards.isEmpty()) BADGE_EMPTY else BADGE_LIVE,
                tone = if (cards.isEmpty()) ProjectsListTone.NEUTRAL else ProjectsListTone.OK,
                reason = if (cards.isEmpty()) "live_empty" else "live_data",
            )
        }

        is ProjectsFeed.Cached -> ProjectsListDecision(
            source = ProjectsListSource.CACHE,
            cards = feed.snapshot.projects.map { toCard(it, feed.snapshot.activeProfile) },
            badgeText = OfflineCacheUiPolicy.cacheBadgeText(feed.savedAtMs, nowMs),
            tone = ProjectsListTone.NEUTRAL,
            reason = "cached_snapshot",
        )

        is ProjectsFeed.Unavailable -> ProjectsListDecision(
            source = ProjectsListSource.STATIC,
            cards = staticProjects,
            badgeText = BADGE_STATIC,
            tone = ProjectsListTone.WARNING,
            reason = "static_fallback",
        )
    }

    /**
     * Строка сервера -> карточка экрана.
     *
     * `url` пустой намеренно: `path` проекта — серверный путь, а не адрес сервиса;
     * выдумывать ссылку нельзя (кнопка «Открыть сервис» появляется только у настоящего
     * URL, см. `ProjectsActivity.addCard`). Описание — путь, если сервер его отдал.
     * Бейдж: проект активного профиля «Активен», чужой — именем своего профиля
     * (агрегат `?all_profiles=1` не должен выглядеть как один общий список).
     */
    fun toCard(entry: ProjectEntry, activeProfile: String): ProjectStatus {
        val profile = entry.profile?.takeIf { it.isNotBlank() }
        val belongsToActive = profile == null || activeProfile.isBlank() || profile == activeProfile
        return ProjectStatus(
            name = entry.name,
            url = "",
            description = entry.path,
            status = if (belongsToActive) ACTIVE_BADGE else profile!!,
        )
    }
}
