package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MarsProfile
import ru.mars.network.model.ProfilesResponse

/**
 * Решения экрана «Профили»: разбор состояния (`/api/profiles`), состав строк,
 * активный профиль и пометки. Текстовок здесь нет — они живут в ресурсах и
 * проверяются разметкой экрана, а не политикой.
 */
class ProfilesPolicyTest {

    private val full = ProfilesResponse(
        profiles = listOf(
            MarsProfile(
                name = "default",
                displayName = "Stef",
                isDefault = true,
                gatewayRunning = true,
                model = "openai/gpt-5",
                provider = "openai",
                enabledSkills = 12,
                totalSkills = 34,
            ),
            MarsProfile(name = "office", isActive = true, visible = false),
        ),
        active = "office",
        singleProfileMode = false,
    )

    @Test
    fun `full payload builds rows in server order with badges`() {
        val state = ProfilesPolicy.resolve(full, loading = false)

        val body = state as ProfilesScreenState.Body
        assertEquals("office", body.activeName)
        assertFalse(body.singleProfileMode)
        assertEquals(listOf("default", "office"), body.rows.map { it.name })
        assertEquals(listOf("Stef", "office"), body.rows.map { it.title })

        val first = body.rows[0]
        assertTrue("профиль по умолчанию помечен", first.showDefaultBadge)
        assertFalse("активен office, не default", first.showActiveBadge)
        assertTrue(first.gatewayRunning)
        assertTrue(first.hasConfiguration)
        assertEquals("gpt-5", first.model)

        val second = body.rows[1]
        assertTrue(second.showActiveBadge)
        assertFalse(second.showDefaultBadge)
        assertTrue("visible=false — скрыт из чата", second.showHiddenBadge)
        assertFalse("без модели/провайдера/скиллов меты нет", second.hasConfiguration)
    }

    @Test
    fun `truncated payload keeps defaults instead of failing`() {
        val state = ProfilesPolicy.resolve(ProfilesResponse(profiles = listOf(MarsProfile(name = "work"))), loading = false)

        val row = (state as ProfilesScreenState.Body).rows.single()
        assertEquals("work", row.title)
        assertNull("активного сервер не назвал", state.activeName)
        assertFalse(row.showActiveBadge)
        assertFalse(row.hasConfiguration)
        assertFalse(row.gatewayRunning)
        assertTrue("нет пометки «скрыт»: ключа visible в payload не было", !row.showHiddenBadge)
    }

    @Test
    fun `empty profile list is its own state`() {
        assertEquals(
            ProfilesScreenState.Empty,
            ProfilesPolicy.resolve(ProfilesResponse(profiles = emptyList(), active = "default"), loading = false),
        )
    }

    @Test
    fun `single profile mode is carried through and keeps the active badge`() {
        val state = ProfilesPolicy.resolve(
            ProfilesResponse(
                profiles = listOf(MarsProfile(name = "user1", isActive = true, visible = false)),
                active = "user1",
                singleProfileMode = true,
            ),
            loading = false,
        )

        val body = state as ProfilesScreenState.Body
        assertTrue(body.singleProfileMode)
        assertTrue(body.rows.single().showActiveBadge)
    }

    @Test
    fun `active profile falls back to is_active row when payload has no active`() {
        val state = ProfilesPolicy.resolve(
            ProfilesResponse(profiles = listOf(MarsProfile(name = "a"), MarsProfile(name = "b", isActive = true))),
            loading = false,
        )

        assertEquals("b", (state as ProfilesScreenState.Body).activeName)
        assertTrue(state.rows.single { it.name == "b" }.showActiveBadge)
    }

    @Test
    fun `active endpoint fallback is accepted only for a listed profile`() {
        val response = ProfilesResponse(profiles = listOf(MarsProfile(name = "a"), MarsProfile(name = "b")))

        assertEquals("b", ProfilesPolicy.activeName(response, fallback = "b"))
        assertNull("чужое имя не помечает строку активной", ProfilesPolicy.activeName(response, fallback = "gone"))
        assertNull(ProfilesPolicy.activeName(response))
        assertNull(ProfilesPolicy.activeName(null, fallback = "b"))
        // Поле active ответа сильнее фолбэка.
        assertEquals("a", ProfilesPolicy.activeName(response.copy(active = "a"), fallback = "b"))
    }

    @Test
    fun `resolve maps auth, loading, failure and keeps the shown list`() {
        assertEquals(ProfilesScreenState.NoAuth, ProfilesPolicy.resolve(full, loading = false, authenticated = false))
        assertEquals(ProfilesScreenState.Loading, ProfilesPolicy.resolve(null, loading = true))
        assertEquals(ProfilesScreenState.Failure, ProfilesPolicy.resolve(null, loading = false))

        // Обновление поверх готового списка не мигает и не теряет строки.
        val shown = ProfilesPolicy.resolve(full, loading = false)
        val refreshing = ProfilesPolicy.resolve(null, loading = true, previous = shown) as ProfilesScreenState.Body
        assertTrue(refreshing.refreshing)
        assertFalse(refreshing.stale)
        assertEquals(2, refreshing.rows.size)

        // Неудачное обновление оставляет прошлый список и помечает его устаревшим.
        val stale = ProfilesPolicy.resolve(null, loading = false, previous = refreshing) as ProfilesScreenState.Body
        assertFalse(stale.refreshing)
        assertTrue(stale.stale)
        assertEquals(2, stale.rows.size)
    }

    @Test
    fun `meta line follows the WebUI order and hides the skill counter when empty`() {
        val withSkills = ProfilesRow(name = "default", title = "Stef", model = "gpt-5", provider = "openai", enabledSkills = 12, totalSkills = 34)
        assertEquals(
            listOf("gpt-5", "openai", "12 / 34 навыков"),
            ProfilesPolicy.metaParts(withSkills) { enabled, total -> "$enabled / $total навыков" },
        )

        val bare = ProfilesRow(name = "office", title = "office")
        assertTrue(ProfilesPolicy.metaParts(bare) { _, _ -> "не должен вызываться" }.isEmpty())
        assertFalse(bare.hasConfiguration)
    }
}
