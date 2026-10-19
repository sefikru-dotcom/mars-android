package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.mars.network.model.SessionItem

/**
 * Отпечаток входного состояния списка диалогов: пока он тот же, экран не снимает
 * и не строит карточки заново. Тесты фиксируют границу «что считается изменением» —
 * и в первую очередь то, что пересборка НЕ случается от пустых поводов (тот же
 * список, другой порядок раскрытия).
 */
class SessionListRenderPolicyTest {

    private fun row(id: String, title: String = "Диалог $id", parent: String? = null) =
        SessionItem(id = id, title = title, parentSessionId = parent)

    private fun state(
        query: String = "",
        expanded: Set<String> = emptySet(),
        sessions: List<SessionItem> = listOf(row("a"), row("b")),
        remote: List<SessionItem> = emptyList(),
    ) = SessionListRenderState(
        filterQuery = query,
        expandedParents = expanded,
        sessions = sessions,
        remoteMatches = remote,
    )

    @Test
    fun firstRenderAlwaysBuilds() {
        val policy = SessionListRenderPolicy()
        assertEquals(SessionListRenderDecision.Rebuild, policy.decide(state()))
    }

    @Test
    fun sameStateIsReused() {
        val policy = SessionListRenderPolicy()
        val s = state()
        assertEquals(SessionListRenderDecision.Rebuild, policy.decide(s))
        // Повторный вызов с тем же состоянием — самый частый случай: onResume,
        // догоняющий refresh после мутации, вторая половина debounce-окна.
        assertEquals(SessionListRenderDecision.Reuse, policy.decide(s))
        assertEquals(SessionListRenderDecision.Reuse, policy.decide(s))
    }

    @Test
    fun equalButSeparateStateIsReused() {
        // Сеть каждый раз отдаёт НОВЫЕ объекты строк с теми же значениями: рендер
        // не должен зависеть от identity, иначе переиспользование не работало бы.
        val policy = SessionListRenderPolicy()
        assertEquals(SessionListRenderDecision.Rebuild, policy.decide(state()))
        assertEquals(SessionListRenderDecision.Reuse, policy.decide(state()))
    }

    @Test
    fun filterQueryChangeRebuilds() {
        val policy = SessionListRenderPolicy()
        policy.decide(state(query = ""))
        assertEquals(SessionListRenderDecision.Rebuild, policy.decide(state(query = "бюд")))
    }

    @Test
    fun expandingParentRebuilds() {
        val policy = SessionListRenderPolicy()
        policy.decide(state(expanded = emptySet()))
        assertEquals(SessionListRenderDecision.Rebuild, policy.decide(state(expanded = setOf("a"))))
    }

    @Test
    fun expansionOrderIsNotAChange() {
        // Свернуть и снова развернуть — картинка та же, пересобирать нечего.
        val policy = SessionListRenderPolicy()
        policy.decide(state(expanded = linkedSetOf("a", "b")))
        assertEquals(
            SessionListRenderDecision.Reuse,
            policy.decide(state(expanded = linkedSetOf("b", "a")))
        )
    }

    @Test
    fun titleChangeRebuilds() {
        val policy = SessionListRenderPolicy()
        policy.decide(state(sessions = listOf(row("a", "Старое"), row("b"))))
        assertEquals(
            SessionListRenderDecision.Rebuild,
            policy.decide(state(sessions = listOf(row("a", "Новое"), row("b"))))
        )
    }

    @Test
    fun parentChangeRebuilds() {
        // Родитель определяет глубину и место строки в дереве.
        val policy = SessionListRenderPolicy()
        policy.decide(state(sessions = listOf(row("a"), row("b"))))
        assertEquals(
            SessionListRenderDecision.Rebuild,
            policy.decide(state(sessions = listOf(row("a"), row("b", parent = "a"))))
        )
    }

    @Test
    fun orderChangeRebuilds() {
        val policy = SessionListRenderPolicy()
        policy.decide(state(sessions = listOf(row("a"), row("b"))))
        assertEquals(
            SessionListRenderDecision.Rebuild,
            policy.decide(state(sessions = listOf(row("b"), row("a"))))
        )
    }

    @Test
    fun addedRowRebuilds() {
        val policy = SessionListRenderPolicy()
        policy.decide(state(sessions = listOf(row("a"))))
        assertEquals(
            SessionListRenderDecision.Rebuild,
            policy.decide(state(sessions = listOf(row("a"), row("b"))))
        )
    }

    @Test
    fun metaFieldsChangeRebuilds() {
        // Мета-строка карточки («5 мин назад · 12 сообщений») и закрепление печатаются
        // на экране, значит входят в отпечаток — иначе карточка осталась бы старой.
        val policy = SessionListRenderPolicy()
        policy.decide(state(sessions = listOf(SessionItem("a", "Диалог", messageCount = 3))))
        assertEquals(
            SessionListRenderDecision.Rebuild,
            policy.decide(state(sessions = listOf(SessionItem("a", "Диалог", messageCount = 4))))
        )
        policy.decide(state(sessions = listOf(SessionItem("a", "Диалог", pinned = false))))
        assertEquals(
            SessionListRenderDecision.Rebuild,
            policy.decide(state(sessions = listOf(SessionItem("a", "Диалог", pinned = true))))
        )
    }

    @Test
    fun remoteMatchesChangeRebuilds() {
        val policy = SessionListRenderPolicy()
        policy.decide(state(remote = emptyList()))
        assertEquals(
            SessionListRenderDecision.Rebuild,
            policy.decide(state(remote = listOf(row("r1"))))
        )
    }

    @Test
    fun emptyAndMissingTitleAreDifferent() {
        // null («сервер не прислал») и "" — разные состояния, хотя обе карточки
        // показывают «Новый диалог»: длина-префикс в отпечатке их не путает.
        val policy = SessionListRenderPolicy()
        policy.decide(state(sessions = listOf(SessionItem("a", title = null))))
        assertEquals(
            SessionListRenderDecision.Rebuild,
            policy.decide(state(sessions = listOf(SessionItem("a", title = ""))))
        )
    }

    @Test
    fun resetForcesNextRender() {
        // После пересоздания view на экране ничего не отрисовано — отпечаток
        // предыдущего дерева недействителен.
        val policy = SessionListRenderPolicy()
        val s = state()
        policy.decide(s)
        policy.reset()
        assertEquals(SessionListRenderDecision.Rebuild, policy.decide(s))
    }
}
