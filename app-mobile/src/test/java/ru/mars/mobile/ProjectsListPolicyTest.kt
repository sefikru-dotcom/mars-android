package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ProjectEntry
import ru.mars.network.model.ProjectsSnapshot
import ru.mars.network.repository.ProjectsFeed

/**
 * Тесты чистой политики секции «Проекты» ([ProjectsListPolicy]): выбор источника
 * (сервер / кэш / вшитый список), подпись бейджа и маппинг строки сервера в карточку.
 *
 * Ключевые продуктовые гарантии:
 *  - живой ответ сервера всегда главнее кэша и статики, даже пустой;
 *  - вшитый [MarsProjects.all] — ТОЛЬКО окончательный фолбэк и помечен предупреждением;
 *  - серверный `path` не превращается в ссылку (кнопка «Открыть сервис» — для URL).
 */
class ProjectsListPolicyTest {

    private val now = 1_700_000_000_000L

    private fun entry(
        name: String,
        path: String = "",
        profile: String? = null,
        id: String = "",
    ) = ProjectEntry(id = id, name = name, path = path, profile = profile)

    private fun snapshot(
        vararg projects: ProjectEntry,
        activeProfile: String = "default",
    ) = ProjectsSnapshot(
        projects = projects.toList(),
        allProfiles = false,
        activeProfile = activeProfile,
        otherProfileCount = 0,
    )

    @Test
    fun `live server list wins over cache and bundled fallback`() {
        val decision = ProjectsListPolicy.decide(
            feed = ProjectsFeed.Live(snapshot(entry("Квартал", "/root/kvartal", "default"), entry("Альпен"))),
            staticProjects = MarsProjects.all,
            nowMs = now,
        )

        assertEquals(ProjectsListSource.SERVER, decision.source)
        assertEquals(ProjectsListPolicy.BADGE_LIVE, decision.badgeText)
        assertEquals(ProjectsListTone.OK, decision.tone)
        assertEquals("live_data", decision.reason)
        assertEquals(listOf("Квартал", "Альпен"), decision.cards.map { it.name })
        assertEquals("Вшитый список не должен подмешиваться", 2, decision.cards.size)
    }

    @Test
    fun `server path becomes description and never a link`() {
        val decision = ProjectsListPolicy.decide(
            feed = ProjectsFeed.Live(snapshot(entry("Квартал", "/root/kvartal", "default"))),
            staticProjects = MarsProjects.all,
            nowMs = now,
        )

        val card = decision.cards.single()
        assertEquals("/root/kvartal", card.description)
        assertTrue("path — серверный путь, а не адрес сервиса", card.url.isEmpty())
        assertEquals(ProjectsListPolicy.ACTIVE_BADGE, card.status)
    }

    @Test
    fun `empty live list is an honest empty state not a static fallback`() {
        val decision = ProjectsListPolicy.decide(
            feed = ProjectsFeed.Live(snapshot()),
            staticProjects = MarsProjects.all,
            nowMs = now,
        )

        assertEquals(ProjectsListSource.SERVER, decision.source)
        assertTrue("пустой ответ — факт, а не повод рисовать вшитый список", decision.cards.isEmpty())
        assertEquals(ProjectsListPolicy.BADGE_EMPTY, decision.badgeText)
        assertEquals("live_empty", decision.reason)
    }

    @Test
    fun `project of another profile is badged with its profile name`() {
        val decision = ProjectsListPolicy.decide(
            feed = ProjectsFeed.Live(
                snapshot(entry("Офис", "/root/office", "office"), entry("Квартал", "/root/kvartal", "default")),
            ),
            staticProjects = MarsProjects.all,
            nowMs = now,
        )

        assertEquals("office", decision.cards[0].status)
        assertEquals(ProjectsListPolicy.ACTIVE_BADGE, decision.cards[1].status)
    }

    @Test
    fun `cached snapshot is labelled with cache age`() {
        val savedAt = now - 5 * 60_000L
        val decision = ProjectsListPolicy.decide(
            feed = ProjectsFeed.Cached(snapshot(entry("Квартал", "/root/kvartal")), savedAtMs = savedAt),
            staticProjects = MarsProjects.all,
            nowMs = now,
        )

        assertEquals(ProjectsListSource.CACHE, decision.source)
        assertEquals(OfflineCacheUiPolicy.cacheBadgeText(savedAt, now), decision.badgeText)
        assertTrue(decision.badgeText.startsWith(OfflineCacheUiPolicy.BADGE_PREFIX))
        assertEquals("cached_snapshot", decision.reason)
        assertEquals("Квартал", decision.cards.single().name)
    }

    @Test
    fun `unavailable feed is the only path to the bundled list and it is marked`() {
        val decision = ProjectsListPolicy.decide(
            feed = ProjectsFeed.Unavailable("no_snapshot"),
            staticProjects = MarsProjects.all,
            nowMs = now,
        )

        assertEquals(ProjectsListSource.STATIC, decision.source)
        assertEquals(MarsProjects.all, decision.cards)
        assertEquals(ProjectsListPolicy.BADGE_STATIC, decision.badgeText)
        assertEquals("устаревшие данные не должны выглядеть живыми", ProjectsListTone.WARNING, decision.tone)
        assertEquals("static_fallback", decision.reason)
        assertFalse("фолбэк не должен притворяться серверным ответом", decision.badgeText == ProjectsListPolicy.BADGE_LIVE)
    }
}
