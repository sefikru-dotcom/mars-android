package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.SessionItem

class SessionTreePolicyTest {

    private fun session(
        id: String,
        title: String? = null,
        preview: String? = null,
        parent: String? = null,
        subagent: Boolean = false,
        readOnly: Boolean = false,
        createdAt: String? = null,
        updatedAt: String? = null,
        pinned: Boolean = false,
        archived: Boolean = false,
    ) = SessionItem(
        id = id,
        title = title,
        preview = preview,
        createdAt = createdAt,
        updatedAt = updatedAt,
        sourceTag = if (subagent) "subagent" else null,
        readOnly = readOnly,
        parentSessionId = parent,
        pinned = pinned,
        archived = archived,
    )

    private fun ids(nodes: List<SessionTreeNode>): List<String> = nodes.map { it.session.id }

    private fun flattenAll(roots: List<SessionTreeNode>): List<SessionTreeNode> {
        val out = mutableListOf<SessionTreeNode>()
        fun walk(node: SessionTreeNode) {
            out += node
            node.children.forEach(::walk)
        }
        roots.forEach(::walk)
        return out
    }

    // ---- root grouping ----

    @Test
    fun subagentChildrenNestUnderParentAndAreNotTopLevel() {
        val roots = SessionTreePolicy.build(
            listOf(
                session("w1", "Основная", updatedAt = "2026-10-01"),
                session("s1", "Субагент", parent = "w1", subagent = true, readOnly = true, updatedAt = "2026-10-02"),
                session("s2", "Субагент 2", parent = "w1", subagent = true, readOnly = true, updatedAt = "2026-10-03"),
            )
        )
        // Единственный верхнеуровневый чат — родитель; субагенты вложены в него.
        assertEquals(listOf("w1"), ids(roots))
        assertEquals(listOf("s2", "s1"), ids(roots[0].children)) // свежие сначала
        assertEquals(1, roots[0].children[0].depth)
    }

    @Test
    fun rootsAreSortedByRecencyWithCreatedAtFallback() {
        val roots = SessionTreePolicy.build(
            listOf(
                session("old", "Старая", updatedAt = "2026-01-01"),
                session("mid", "Средняя", createdAt = "2026-05-01"),
                session("new", "Свежая", updatedAt = "2026-09-01"),
            )
        )
        assertEquals(listOf("new", "mid", "old"), ids(roots))
    }

    @Test
    fun plainChildSessionAlsoNestsNotOnlySubagents() {
        // Вложение определяется parent_session_id, а не только source_tag.
        val roots = SessionTreePolicy.build(
            listOf(
                session("p", "Родитель", updatedAt = "2026-01-02"),
                session("c", "Ветка", parent = "p", updatedAt = "2026-01-01"),
            )
        )
        assertEquals(listOf("p"), ids(roots))
        assertEquals(listOf("c"), ids(roots[0].children))
    }

    // ---- expand / collapse ----

    @Test
    fun collapsedParentHidesChildrenFromVisibleRows() {
        val roots = SessionTreePolicy.build(
            listOf(
                session("w1", "Основная"),
                session("s1", "Субагент", parent = "w1", subagent = true),
            )
        )
        assertFalse(roots[0].expanded)
        assertEquals(listOf("w1"), ids(SessionTreePolicy.visibleRows(roots)))
    }

    @Test
    fun expandedParentRevealsChildren() {
        val roots = SessionTreePolicy.build(
            listOf(
                session("w1", "Основная"),
                session("s1", "Субагент", parent = "w1", subagent = true),
            ),
            expandedParents = setOf("w1"),
        )
        assertTrue(roots[0].expanded)
        assertEquals(listOf("w1", "s1"), ids(SessionTreePolicy.visibleRows(roots)))
    }

    @Test
    fun collapseAfterExpandHidesBranchAgain() {
        val sessions = listOf(
            session("w1", "Основная"),
            session("s1", "Субагент", parent = "w1", subagent = true),
        )
        val expanded = SessionTreePolicy.build(sessions, expandedParents = setOf("w1"))
        val collapsed = SessionTreePolicy.build(sessions, expandedParents = emptySet())
        assertEquals(2, SessionTreePolicy.visibleRows(expanded).size)
        assertEquals(listOf("w1"), ids(SessionTreePolicy.visibleRows(collapsed)))
    }

    @Test
    fun grandchildRequiresWholeChainExpanded() {
        val sessions = listOf(
            session("w1", "Основная"),
            session("s1", "Субагент", parent = "w1", subagent = true),
            session("g1", "Внук", parent = "s1", subagent = true),
        )
        // Раскрыт только w1: s1 видна, её ветка — нет.
        assertEquals(
            listOf("w1", "s1"),
            ids(SessionTreePolicy.visibleRows(SessionTreePolicy.build(sessions, expandedParents = setOf("w1"))))
        )
        // Раскрыта вся цепочка.
        assertEquals(
            listOf("w1", "s1", "g1"),
            ids(SessionTreePolicy.visibleRows(SessionTreePolicy.build(sessions, expandedParents = setOf("w1", "s1"))))
        )
    }

    @Test
    fun hasChildrenMarksExpandableRows() {
        val roots = SessionTreePolicy.build(
            listOf(
                session("w1", "Основная"),
                session("s1", "Субагент", parent = "w1", subagent = true),
                session("solo", "Без детей"),
            )
        )
        assertTrue(roots.first { it.session.id == "w1" }.hasChildren)
        assertFalse(roots.first { it.session.id == "solo" }.hasChildren)
    }

    // ---- orphan handling ----

    @Test
    fun orphanSubagentIsSuppressed() {
        // Родитель вне списка: субагент не promoted в верхний уровень (WebUI-паритет).
        val roots = SessionTreePolicy.build(
            listOf(
                session("w1", "Основная"),
                session("s1", "Сирота", parent = "gone", subagent = true, readOnly = true),
            )
        )
        assertEquals(listOf("w1"), ids(roots))
        assertFalse(flattenAll(roots).any { it.session.id == "s1" })
    }

    @Test
    fun plainOrphanStaysVisibleInRootTail() {
        val roots = SessionTreePolicy.build(
            listOf(
                session("s1", "Сирота", parent = "gone", updatedAt = "2026-12-01"),
                session("w1", "Основная", updatedAt = "2026-01-01"),
            )
        )
        // Сирота без subagent-метки не теряется, но не конкурирует с укоренёнными.
        assertEquals(listOf("w1", "s1"), ids(roots))
        assertEquals(0, roots[1].depth)
    }

    @Test
    fun orphanSuppressionDoesNotSwallowCurrentSessionBranch() {
        // Текущая сессия — внук сироты-субагента: ветка обязана остаться видимой.
        val roots = SessionTreePolicy.build(
            listOf(
                session("s1", "Сирота", parent = "gone", subagent = true, readOnly = true),
                session("g1", "Открытая", parent = "s1", subagent = true, readOnly = true),
            ),
            currentSessionId = "g1",
        )
        assertEquals(listOf("s1", "g1"), ids(SessionTreePolicy.visibleRows(roots)))
    }

    @Test
    fun cycleMembersAppearExactlyOnce() {
        val roots = SessionTreePolicy.build(
            listOf(
                session("a", "A", parent = "b"),
                session("b", "B", parent = "a"),
                session("c", "C", parent = "a"),
            ),
            expandedParents = setOf("a", "b"),
        )
        val all = flattenAll(roots)
        assertEquals(listOf("a", "b", "c"), ids(all).sorted())
        assertEquals(3, all.size) // ровно по одному разу
    }

    // ---- search ----

    @Test
    fun searchFiltersFlatListBeforeTreeBuildAndPullsAncestors() {
        val sessions = listOf(
            session("w1", "Деплой сервера"),
            session("s1", "Прогон тестов", preview = "деплой прошёл", parent = "w1", subagent = true),
            session("s2", "Музыка", parent = "w1", subagent = true),
            session("w2", "Заметки"),
        )
        val filtered = SessionTreePolicy.filterForSearch(sessions, "деплой")
        // Совпадения + предки как контекст; порядок списка сохранён.
        assertEquals(listOf("w1", "s1"), ids(filtered.map { SessionTreeNode(it, 0) }))

        val roots = SessionTreePolicy.build(filtered, searchActive = true)
        assertEquals(listOf("w1"), ids(roots))
        assertEquals(listOf("s1"), ids(roots[0].children))
        // Не совпавшие строки не попали в дерево.
        assertFalse(flattenAll(roots).any { it.session.id == "s2" || it.session.id == "w2" })
    }

    @Test
    fun searchResultIsExpandedSoMatchesAreVisible() {
        val sessions = listOf(
            session("w1", "Деплой"),
            session("s1", "Деплой-лог", parent = "w1", subagent = true),
        )
        // Без явного expandedParents совпадение всё равно видно (searchActive).
        val roots = SessionTreePolicy.tree(sessions, query = "лог")
        assertEquals(listOf("w1", "s1"), ids(SessionTreePolicy.visibleRows(roots)))
    }

    @Test
    fun blankQueryKeepsTreeIntact() {
        val sessions = listOf(
            session("w1", "Деплой"),
            session("s1", "Субагент", parent = "w1", subagent = true),
        )
        assertEquals(SessionTreePolicy.build(sessions), SessionTreePolicy.tree(sessions, query = "   "))
    }

    @Test
    fun searchIsCaseInsensitiveAndMatchesPreview() {
        val filtered = SessionTreePolicy.filterForSearch(
            listOf(session("w1", null, preview = "DEPLOY log")),
            "deploy",
        )
        assertEquals(listOf("w1"), ids(filtered.map { SessionTreeNode(it, 0) }))
    }

    // ---- current session ----

    @Test
    fun currentChildSessionIsMarkedAndAutoExpandsAncestors() {
        val roots = SessionTreePolicy.build(
            listOf(
                session("w1", "Основная"),
                session("s1", "Субагент", parent = "w1", subagent = true, readOnly = true),
            ),
            currentSessionId = "s1",
        )
        // Открытая субагент-сессия видна без ручного раскрытия родителя.
        val visible = SessionTreePolicy.visibleRows(roots)
        assertEquals(listOf("w1", "s1"), ids(visible))
        assertFalse(visible[0].isCurrent)
        assertTrue(visible[1].isCurrent)
    }

    @Test
    fun currentRootSessionIsMarkedWithoutExpansionSideEffects() {
        val roots = SessionTreePolicy.build(
            listOf(
                session("w1", "Основная"),
                session("s1", "Субагент", parent = "w1", subagent = true),
            ),
            currentSessionId = "w1",
        )
        assertTrue(roots[0].isCurrent)
        assertFalse(roots[0].expanded) // текущий корень не обязан раскрывать детей
        assertEquals(listOf("w1"), ids(SessionTreePolicy.visibleRows(roots)))
    }

    @Test
    fun unknownCurrentSessionIdChangesNothing() {
        val sessions = listOf(
            session("w1", "Основная"),
            session("s1", "Субагент", parent = "w1", subagent = true),
        )
        assertEquals(SessionTreePolicy.build(sessions), SessionTreePolicy.build(sessions, currentSessionId = "nope"))
    }

    // ---- pin ordering (A3) ----

    @Test
    fun pinnedRootRisesAboveFresherUnpinnedRoot() {
        // Без этого закрепление было бы невидимым: закреплённый диалог уезжал бы
        // вниз под более свежие незакреплённые.
        val roots = SessionTreePolicy.build(
            listOf(
                session("fresh", "Свежая", updatedAt = "2026-10-09"),
                session("pinned", "Закреплённая", updatedAt = "2026-01-01", pinned = true),
            )
        )
        assertEquals(listOf("pinned", "fresh"), ids(roots))
    }

    @Test
    fun pinnedGroupItselfStaysRecencyOrdered() {
        val roots = SessionTreePolicy.build(
            listOf(
                session("p-old", "Старая", updatedAt = "2026-01-01", pinned = true),
                session("p-new", "Новая", updatedAt = "2026-09-01", pinned = true),
                session("plain", "Обычная", updatedAt = "2026-10-01"),
            )
        )
        assertEquals(listOf("p-new", "p-old", "plain"), ids(roots))
    }

    @Test
    fun unpinnedOrderIsUnchangedWhenNothingIsPinned() {
        // Весь существующий список: ни одного флага pinned — порядок как раньше.
        val sessions = listOf(
            session("old", "Старая", updatedAt = "2026-01-01"),
            session("mid", "Средняя", createdAt = "2026-05-01"),
            session("new", "Свежая", updatedAt = "2026-09-01"),
        )
        assertEquals(listOf("new", "mid", "old"), ids(SessionTreePolicy.build(sessions)))
    }
}
