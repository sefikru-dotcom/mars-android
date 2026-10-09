package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Тесты политики сохранения открытого диалога [SessionRecreationPolicy]: что уходит в
 * onSaveInstanceState, какой план восстановления даёт onCreate, и почему восстановление
 * одноразовое в пределах экземпляра Activity.
 */
class SessionRecreationPolicyTest {

    private val policy = SessionRecreationPolicy()

    @Test
    fun `open session id is persisted for recreation`() {
        assertEquals("session-42", policy.stateToPersist("session-42"))
    }

    @Test
    fun `persisted id is trimmed`() {
        assertEquals("session-42", policy.stateToPersist("  session-42 \n"))
    }

    @Test
    fun `nothing is persisted without an open session`() {
        // Новый чат без session_id: в bundle нечего сохранять.
        assertNull(policy.stateToPersist(null))
        assertNull(policy.stateToPersist(""))
        assertNull(policy.stateToPersist("   "))
    }

    @Test
    fun `saved session id plans a reopen`() {
        assertEquals(RecreationPlan.Reopen("session-42"), policy.planFor("session-42"))
    }

    @Test
    fun `saved id with whitespace plans a reopen of the trimmed id`() {
        assertEquals(RecreationPlan.Reopen("session-42"), policy.planFor(" session-42 "))
    }

    @Test
    fun `cold start without saved state is a fresh start`() {
        // savedInstanceState == null (обычный запуск) — остаёмся в новом чате.
        assertEquals(RecreationPlan.FreshStart, policy.planFor(null))
    }

    @Test
    fun `blank saved id is a fresh start`() {
        assertEquals(RecreationPlan.FreshStart, policy.planFor("   "))
    }

    @Test
    fun `restore is one-shot - second plan is fresh start`() {
        assertEquals(RecreationPlan.Reopen("session-42"), policy.planFor("session-42"))
        // Тот же экземпляр Activity не восстанавливает диалог дважды: дальше только навигация.
        assertEquals(RecreationPlan.FreshStart, policy.planFor("session-42"))
    }

    @Test
    fun `fresh start also consumes the one-shot restore`() {
        assertEquals(RecreationPlan.FreshStart, policy.planFor(null))
        assertEquals(RecreationPlan.FreshStart, policy.planFor("session-42"))
    }

    @Test
    fun `recreated activity gets its own policy and restores again`() {
        assertEquals(RecreationPlan.Reopen("s1"), policy.planFor("s1"))
        // Пересоздание экрана создаёт новый экземпляр политики — сохранённый id снова срабатывает.
        val recreated = SessionRecreationPolicy()
        assertEquals(RecreationPlan.Reopen("s1"), recreated.planFor("s1"))
    }
}
