package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.SessionItem
import java.time.ZoneId

/**
 * Перенос диалога в другого родителя: какие строки можно переносить, какие
 * диалоги предлагаются целью и что видит пользователь в тостах.
 *
 * Логика чистая ([SessionMovePolicy]), поэтому тесты JVM-ные: ни Android, ни сети.
 */
class SessionMovePolicyTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    private fun root(id: String, title: String? = "Диалог $id", updatedAt: String? = null) =
        SessionItem(id = id, title = title, updatedAt = updatedAt)

    private fun child(id: String, parentId: String, subagent: Boolean = false, title: String? = "Дочерний $id") =
        SessionItem(
            id = id,
            title = title,
            parentSessionId = parentId,
            readOnly = subagent,
            sourceTag = if (subagent) "subagent" else null,
        )

    @Test
    fun rootRowCannotBeMoved() {
        // Корневой диалог — верхний уровень, переносить его некуда.
        assertFalse(SessionMovePolicy.canMove(root("s1")))
    }

    @Test
    fun subagentAndForkRowsCanBeMoved() {
        assertTrue(SessionMovePolicy.canMove(child("sub", "p", subagent = true)))
        assertTrue(SessionMovePolicy.canMove(child("fork", "p")))
    }

    @Test
    fun blankIdCannotBeMoved() {
        assertFalse(SessionMovePolicy.canMove(child("", "p")))
    }

    @Test
    fun targetsAreRootsWithoutSelfParentAndSubagents() {
        val moving = child("kid", "p1")
        val sessions = listOf(
            root("p1", "Родитель"),
            root("p2", "Другой корень"),
            moving,
            child("sib", "p1"),
            child("sub", "p1", subagent = true),
            root("arch", "Архивный").copy(archived = true),
        )

        // p1 — текущий родитель (no-op), sib/sub — не корни, arch — архив.
        assertEquals(listOf("p2"), SessionMovePolicy.targets(sessions, moving).map { it.id })
    }

    @Test
    fun ownDescendantsAreExcludedFromTargets() {
        // Правило «нельзя перенести в себя или в своего потомка» проверяется на
        // уровне [SessionMovePolicy.targets]: и сам диалог, и его дети/внуки в цели
        // не попадают (потомки дополнительно отсекаются фильтром «только корни»).
        val moving = child("kid", "p1")
        val sessions = listOf(root("p1"), moving, child("grand", "kid"), root("p2"))

        val ids = SessionMovePolicy.targets(sessions, moving).map { it.id }
        assertFalse("перенос в себя запрещён", ids.contains("kid"))
        assertFalse("перенос в потомка запрещён", ids.contains("grand"))
        assertEquals(listOf("p2"), ids)
    }

    @Test
    fun deepDescendantIsExcludedTransitively() {
        val moving = child("kid", "p1")
        val sessions = listOf(
            moving,
            child("a", "kid"),
            child("b", "a"),
            root("p2"),
        )

        assertEquals(listOf("p2"), SessionMovePolicy.targets(sessions, moving).map { it.id })
    }

    @Test
    fun rootRowHasNoTargetsAtAll() {
        val sessions = listOf(root("p1"), root("p2"))
        assertTrue(SessionMovePolicy.targets(sessions, sessions[0]).isEmpty())
    }

    @Test
    fun targetLabelShowsTitleAndDateNeverRawId() {
        val target = root("0193f0c2-dead-beef", "Квартал", updatedAt = "2026-10-14 09:15:00")

        val label = SessionMovePolicy.targetLabel(target, utc)
        assertEquals("Квартал · 14.10.2026", label)
        assertFalse("сырой id в подписи не показываем", label.contains("0193f0c2"))
    }

    @Test
    fun targetLabelFallsBackToCreatedAtThenToTitleOnly() {
        assertEquals(
            "Квартал · 13.10.2026",
            SessionMovePolicy.targetLabel(root("p2", "Квартал", updatedAt = null).copy(createdAt = "2026-10-13 23:00:00"), utc),
        )
        // Метки нет — подпись без даты, а не «01.01.1970».
        assertEquals("Без метки", SessionMovePolicy.targetLabel(root("p3", "Без метки"), utc))
        assertEquals(SessionMovePolicy.UNTITLED, SessionMovePolicy.targetLabel(root("p4", "  "), utc))
    }

    @Test
    fun messagesNameTheTargetAndKeepServerText() {
        assertEquals("Диалог перенесён: Квартал", SessionMovePolicy.successMessage(root("p2", "Квартал")))
        assertEquals(
            "Не удалось перенести: Session not found",
            SessionMovePolicy.failureMessage("Session not found"),
        )
        assertEquals("Не удалось перенести диалог", SessionMovePolicy.failureMessage(null))
        assertEquals("Не удалось перенести диалог", SessionMovePolicy.failureMessage("   "))
    }

    @Test
    fun menuOffersMoveOnlyForChildRows() {
        // У субагента read_only отсекает pin/archive/export/share — остаётся один перенос.
        assertEquals(
            listOf(SessionActionKind.MOVE),
            SessionManagementPolicy.menu(child("sub", "p1", subagent = true)),
        )
        // Обычная корневая строка перенос не предлагает: у неё полный набор
        // управляемых действий, включая публичную ссылку (share закрыт в этой волне).
        assertEquals(
            listOf(
                SessionActionKind.PIN,
                SessionActionKind.ARCHIVE,
                SessionActionKind.EXPORT,
                SessionActionKind.SHARE,
            ),
            SessionManagementPolicy.menu(root("s1")),
        )
    }

    @Test
    fun targetsAreDeduplicatedById() {
        val moving = child("kid", "p1")
        val sessions = listOf(moving, root("p2"), root("p2"))
        assertEquals(listOf("p2"), SessionMovePolicy.targets(sessions, moving).map { it.id })
    }
}
