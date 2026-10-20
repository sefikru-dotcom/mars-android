package ru.mars.tv.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MarsProfile
import ru.mars.network.model.ProfilesResponse

/**
 * Unit-тесты [TvProfilesPolicy] без Android-фреймворка: маппинг `GET /api/profiles`
 * в карточки TV-экрана — порядок сервера, подпись `display_name` с фолбэком на
 * техническое имя, короткая модель, бейджи, активный профиль, мета-строка и
 * стабильный фокус пульта.
 */
class TvProfilesPolicyTest {

    private val response = ProfilesResponse(
        profiles = listOf(
            MarsProfile(
                name = "main",
                displayName = "Марс",
                path = "/root/.hermes",
                isDefault = true,
                isActive = true,
                model = "openai/gpt-5",
                provider = "openai",
                enabledSkills = 3,
                totalSkills = 12,
            ),
            MarsProfile(
                name = "office",
                displayName = "Офис",
                path = "/root/office",
                model = "anthropic/claude",
                provider = "anthropic",
            ),
        ),
        active = "main",
        singleProfileMode = false,
    )

    @Test
    fun `rows keep server order and map every card field`() {
        val rows = TvProfilesPolicy.rows(response)
        assertEquals(listOf("main", "office"), rows.map { it.name })
        assertEquals(listOf("Марс", "Офис"), rows.map { it.title })
        assertEquals(listOf("main", "office"), rows.map { it.key })
        assertEquals(listOf("/root/.hermes", "/root/office"), rows.map { it.path })
        assertEquals("gpt-5", rows[0].model)
        assertEquals("openai", rows[0].provider)
        assertEquals(3, rows[0].enabledSkills)
        assertEquals(12, rows[0].totalSkills)
    }

    @Test
    fun `title falls back to the technical name when display name is blank`() {
        val rows = TvProfilesPolicy.rows(
            ProfilesResponse(profiles = listOf(MarsProfile(name = "office", displayName = "  "))),
        )
        assertEquals("office", rows.single().title)
    }

    @Test
    fun `model is shown without the provider prefix`() {
        val rows = TvProfilesPolicy.rows(
            ProfilesResponse(profiles = listOf(MarsProfile(name = "a", model = "vendor/model-x"))),
        )
        assertEquals("model-x", rows.single().model)
    }

    @Test
    fun `nameless profiles are dropped and duplicate names collapse to one focus key`() {
        val rows = TvProfilesPolicy.rows(
            ProfilesResponse(
                profiles = listOf(
                    MarsProfile(name = " "),
                    MarsProfile(name = "main", displayName = "Первый"),
                    MarsProfile(name = "main", displayName = "Дубликат"),
                ),
            ),
        )
        assertEquals(1, rows.size)
        assertEquals("Первый", rows.single().title)
        assertEquals(rows.map { it.key }.distinct(), rows.map { it.key })
    }

    @Test
    fun `active profile comes from the response field`() {
        val rows = TvProfilesPolicy.rows(response)
        assertTrue(rows[0].active)
        assertFalse(rows[1].active)
        assertEquals("main", TvProfilesPolicy.activeName(response))
    }

    @Test
    fun `is_active flag is the second source of the active profile`() {
        val rows = TvProfilesPolicy.rows(response.copy(active = null))
        assertTrue(rows[0].active)
        assertEquals("main", TvProfilesPolicy.activeName(response.copy(active = null)))
    }

    @Test
    fun `fallback active name is accepted only when it names a profile from the response`() {
        val noFlags = response.copy(
            active = null,
            profiles = response.profiles.map { it.copy(isActive = false) },
        )
        assertEquals("office", TvProfilesPolicy.activeName(noFlags, fallback = "office"))
        val rows = TvProfilesPolicy.rows(noFlags, fallbackActive = "office")
        assertFalse(rows[0].active)
        assertTrue(rows[1].active)
    }

    @Test
    fun `fallback naming a foreign profile never marks a row active`() {
        val stale = response.copy(active = null, profiles = listOf(MarsProfile(name = "main")))
        assertNull(TvProfilesPolicy.activeName(stale, fallback = "office"))
        assertFalse(TvProfilesPolicy.rows(stale, fallbackActive = "office").single().active)
    }

    @Test
    fun `blank fallback is ignored`() {
        val noFlags = response.copy(
            active = null,
            profiles = response.profiles.map { it.copy(isActive = false) },
        )
        assertNull(TvProfilesPolicy.activeName(noFlags, fallback = "   "))
    }

    @Test
    fun `badges follow the importance order active, default, hidden`() {
        val row = TvProfilesPolicy.rows(response).single { it.name == "main" }.let {
            it.copy(default = true, hidden = true)
        }
        assertEquals(
            listOf(TvProfilesPolicy.ACTIVE_BADGE, TvProfilesPolicy.DEFAULT_BADGE, TvProfilesPolicy.HIDDEN_BADGE),
            TvProfilesPolicy.badges(row),
        )
        assertTrue(TvProfilesPolicy.badges(TvProfilesPolicy.rows(response)[1]).isEmpty())
    }

    @Test
    fun `meta line keeps WebUI order model, provider, skills`() {
        assertEquals("gpt-5 · openai · 3 из 12 навыков", TvProfilesPolicy.metaLine(TvProfilesPolicy.rows(response)[0]))
    }

    @Test
    fun `meta line without configuration says so instead of staying empty`() {
        val row = TvProfilesPolicy.rows(ProfilesResponse(profiles = listOf(MarsProfile(name = "bare")))).single()
        assertFalse(row.hasConfiguration)
        assertEquals(TvProfilesPolicy.NO_CONFIGURATION, TvProfilesPolicy.metaLine(row))
    }

    @Test
    fun `skills counter declines the noun by the total`() {
        assertEquals("1 из 1 навыка", TvProfilesPolicy.skillsLabel(1, 1))
        assertEquals("1 из 3 навыков", TvProfilesPolicy.skillsLabel(1, 3))
        assertEquals("2 из 22 навыков", TvProfilesPolicy.skillsLabel(2, 22))
        assertEquals("1 из 21 навыка", TvProfilesPolicy.skillsLabel(1, 21))
        assertEquals("5 из 11 навыков", TvProfilesPolicy.skillsLabel(5, 11))
        assertEquals("0 из 0 навыков", TvProfilesPolicy.skillsLabel(0, 0))
    }

    @Test
    fun `negative counters are clamped instead of rendering nonsense`() {
        val row = TvProfilesPolicy.row(MarsProfile(name = "a", enabledSkills = -5, totalSkills = -1), isActive = false)
        assertEquals(0, row.enabledSkills)
        assertEquals(0, row.totalSkills)
    }

    @Test
    fun `hidden profile stays in the catalog but does not lead to chat`() {
        val row = TvProfilesPolicy.rows(
            ProfilesResponse(profiles = listOf(MarsProfile(name = "ghost", visible = false))),
        ).single()
        assertTrue(row.hidden)
        assertFalse(TvProfilesPolicy.canOpenChat(row))
        assertNull(TvProfilesPolicy.chatExtra(row))
    }

    @Test
    fun `visible profile opens the chat of exactly that profile`() {
        val row = TvProfilesPolicy.rows(response)[1]
        assertTrue(TvProfilesPolicy.canOpenChat(row))
        assertEquals("office", TvProfilesPolicy.chatExtra(row))
    }

    @Test
    fun `summary counts the cards actually drawn`() {
        assertEquals("Профилей: 2", TvProfilesPolicy.summaryLine(response))
        assertEquals("Профилей: 0", TvProfilesPolicy.summaryLine(ProfilesResponse()))
        assertEquals("Профилей: 0", TvProfilesPolicy.summaryLine(null))
    }

    @Test
    fun `empty catalog is data with its own line, not an error`() {
        assertEquals(TvProfilesPolicy.EMPTY_LIST, TvProfilesPolicy.emptyLine(ProfilesResponse()))
        assertEquals(TvProfilesPolicy.EMPTY_LIST, TvProfilesPolicy.emptyLine(null))
        assertNull(TvProfilesPolicy.emptyLine(response))
    }

    @Test
    fun `status line prepends the cache badge when the source reported one`() {
        assertEquals("Обновлено только что · Профилей: 2", TvProfilesPolicy.statusLine("Профилей: 2", "Обновлено только что"))
        assertEquals("Профилей: 2", TvProfilesPolicy.statusLine("Профилей: 2", null))
        assertEquals("Профилей: 2", TvProfilesPolicy.statusLine("Профилей: 2", "  "))
    }

    @Test
    fun `card description carries title, badges and meta for TalkBack`() {
        val description = TvProfilesPolicy.cardDescription(TvProfilesPolicy.rows(response)[0])
        assertTrue(description.startsWith("Марс"))
        assertTrue(description.contains(TvProfilesPolicy.ACTIVE_BADGE))
        assertTrue(description.contains(TvProfilesPolicy.DEFAULT_BADGE))
        assertTrue(description.contains("gpt-5"))
    }

    @Test
    fun `focus restoration clamps and never wraps`() {
        assertEquals(TvProfilesPolicy.NO_SELECTION, TvProfilesPolicy.restoreSelection(2, 0))
        assertEquals(1, TvProfilesPolicy.restoreSelection(7, 2))
        assertEquals(0, TvProfilesPolicy.restoreSelection(-1, 2))
        assertEquals(1, TvProfilesPolicy.restoreSelection(1, 2))
    }

    @Test
    fun `profile extra is trimmed and blank names are never put on the intent`() {
        assertEquals("office", TvProfilesRouting.profileExtra("  office "))
        assertNull(TvProfilesRouting.profileExtra("   "))
        assertNull(TvProfilesRouting.profileExtra(null))
    }
}
