package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.KanbanAssigneeStats
import ru.mars.network.model.KanbanBoard
import ru.mars.network.model.KanbanColumn
import ru.mars.network.model.KanbanComment
import ru.mars.network.model.KanbanStats
import ru.mars.network.model.KanbanTask
import ru.mars.network.model.KanbanTaskDetail
import ru.mars.network.model.KanbanTaskEvent
import ru.mars.network.model.KanbanTaskLinks

/**
 * Чистые JVM-тесты [KanbanPolicy]: тексты, тона статусов, возрастные метки,
 * колонки/карточки, статистика и модель диалога задачи.
 */
class KanbanPolicyTest {

    private fun task(
        id: String = "k-1",
        title: String = "Задача",
        status: String = "ready",
        assignee: String? = null,
        priority: Int = 0,
        ageSeconds: Long? = null,
        blockKind: String? = null,
        commentCount: Int = 0,
        childCount: Int = 0,
        goalMode: Boolean = false,
    ) = KanbanTask(
        id = id,
        title = title,
        status = status,
        assignee = assignee,
        priority = priority,
        ageSeconds = ageSeconds,
        blockKind = blockKind,
        commentCount = commentCount,
        childCount = childCount,
        goalMode = goalMode,
    )

    @Test
    fun `known statuses map to canonical tones`() {
        assertEquals(KanbanStatusTone.READY, KanbanPolicy.toneFor("ready"))
        assertEquals(KanbanStatusTone.READY, KanbanPolicy.toneFor("queued"))
        assertEquals(KanbanStatusTone.RUNNING, KanbanPolicy.toneFor("running"))
        assertEquals(KanbanStatusTone.RUNNING, KanbanPolicy.toneFor("in_progress"))
        assertEquals(KanbanStatusTone.BLOCKED, KanbanPolicy.toneFor("blocked"))
        assertEquals(KanbanStatusTone.DONE, KanbanPolicy.toneFor("done"))
        assertEquals(KanbanStatusTone.DONE, KanbanPolicy.toneFor("completed"))
    }

    @Test
    fun `unknown and blank statuses are neutral and case-insensitive known ones still map`() {
        assertEquals(KanbanStatusTone.NEUTRAL, KanbanPolicy.toneFor("weird"))
        assertEquals(KanbanStatusTone.NEUTRAL, KanbanPolicy.toneFor(""))
        assertEquals(KanbanStatusTone.RUNNING, KanbanPolicy.toneFor(" Running "))
    }

    @Test
    fun `age label formats minutes hours and days and tolerates null and negatives`() {
        assertNull(KanbanPolicy.ageLabel(null))
        assertEquals("только что", KanbanPolicy.ageLabel(0))
        assertEquals("только что", KanbanPolicy.ageLabel(59))
        assertEquals("только что", KanbanPolicy.ageLabel(-30))
        assertEquals("5 мин", KanbanPolicy.ageLabel(300))
        assertEquals("59 мин", KanbanPolicy.ageLabel(3599))
        assertEquals("2 ч", KanbanPolicy.ageLabel(7200))
        assertEquals("23 ч", KanbanPolicy.ageLabel(86399))
        assertEquals("2 дн", KanbanPolicy.ageLabel(172800))
    }

    @Test
    fun `assignee label falls back to explicit unassigned text`() {
        assertEquals("mars", KanbanPolicy.assigneeLabel("mars"))
        assertEquals(KanbanPolicy.UNASSIGNED_LABEL, KanbanPolicy.assigneeLabel(null))
        assertEquals(KanbanPolicy.UNASSIGNED_LABEL, KanbanPolicy.assigneeLabel("  "))
    }

    @Test
    fun `priority label is shown only for nonzero priority`() {
        assertNull(KanbanPolicy.priorityLabel(0))
        assertEquals("P1", KanbanPolicy.priorityLabel(1))
        assertEquals("P-2", KanbanPolicy.priorityLabel(-2))
    }

    @Test
    fun `card keeps server fields and marks blocked via block_kind even with foreign status`() {
        val card = KanbanPolicy.cardFor(
            task(
                id = "k-9",
                title = "Правка",
                status = "ready",
                assignee = "mars",
                priority = 2,
                ageSeconds = 600,
                blockKind = "needs_input",
                commentCount = 3,
                childCount = 1,
                goalMode = true,
            )
        )
        assertEquals("k-9", card.id)
        assertEquals("Правка", card.title)
        assertEquals("ready", card.status)
        assertEquals(KanbanStatusTone.BLOCKED, card.tone)
        assertTrue(card.isBlocked)
        assertTrue(card.isGoalMode)
        assertEquals("mars", card.assigneeLabel)
        assertEquals("P2", card.priorityLabel)
        assertEquals("10 мин", card.ageLabel)
        assertEquals(3, card.commentCount)
        assertEquals(1, card.childCount)
    }

    @Test
    fun `blank title gets explicit placeholder and unassigned stays visible`() {
        val card = KanbanPolicy.cardFor(task(title = "  ", assignee = null))
        assertEquals("(без названия)", card.title)
        assertEquals(KanbanPolicy.UNASSIGNED_LABEL, card.assigneeLabel)
    }

    @Test
    fun `columns keep server order and empty columns stay visible with counter`() {
        val board = KanbanBoard(
            columns = listOf(
                KanbanColumn(name = "Working", tasks = listOf(task(id = "k-2", status = "running"))),
                KanbanColumn(name = "", tasks = emptyList()),
                KanbanColumn(name = "Ready", tasks = listOf(task(id = "k-1"), task(id = "k-3"))),
            )
        )
        val columns = KanbanPolicy.columnsFor(board)
        assertEquals(listOf("Working", "(колонка)", "Ready"), columns.map { it.name })
        assertEquals(listOf(1, 0, 2), columns.map { it.count })
        assertEquals(listOf("k-1", "k-3"), columns[2].cards.map { it.id })
    }

    @Test
    fun `config columns define the canonical lane order and unknown lanes go last`() {
        val board = KanbanBoard(
            columns = listOf(
                KanbanColumn(name = "new", tasks = listOf(task(id = "k-1"))),
                KanbanColumn(name = "running", tasks = listOf(task(id = "k-2"))),
                KanbanColumn(name = "done", tasks = emptyList()),
                KanbanColumn(name = "archived", tasks = listOf(task(id = "k-9"))),
            )
        )
        val columns = KanbanPolicy.columnsFor(board, listOf("done", "new", "waiting"))
        // Порядок по конфигу; отсутствующая в доске `waiting` не выдумывается;
        // неизвестные конфигу `running`/`archived` — хвостом в серверном порядке.
        assertEquals(listOf("done", "new", "running", "archived"), columns.map { it.name })
        assertEquals(listOf(0, 1, 1, 1), columns.map { it.count })
    }

    @Test
    fun `config lane matching is case and whitespace tolerant and dedupes names`() {
        val board = KanbanBoard(
            columns = listOf(
                KanbanColumn(name = "Ready", tasks = listOf(task(id = "k-1"))),
                KanbanColumn(name = "done", tasks = emptyList()),
            )
        )
        val columns = KanbanPolicy.columnsFor(board, listOf(" DONE ", "done", "ready", "  "))
        assertEquals(listOf("done", "Ready"), columns.map { it.name })
    }

    @Test
    fun `a board without columns gets an empty skeleton from the config lanes`() {
        val columns = KanbanPolicy.columnsFor(KanbanBoard(columns = emptyList()), listOf("new", "done"))
        assertEquals(listOf("new", "done"), columns.map { it.name })
        assertTrue(columns.all { it.count == 0 && it.cards.isEmpty() })
        // Без конфига пустая доска так и остаётся пустой — фазу EMPTY решает презентер.
        assertTrue(KanbanPolicy.columnsFor(KanbanBoard(columns = emptyList())).isEmpty())
    }

    @Test
    fun `stats chips put canonical statuses first then unknown alphabetically`() {
        val stats = KanbanStats(
            byStatus = linkedMapOf("weird" to 1, "done" to 10, "ready" to 3, "running" to 2, "blocked" to 1),
            byAssignee = mapOf("mars" to KanbanAssigneeStats(total = 4)),
            oldestReadyAgeSeconds = 3600,
        )
        val ui = KanbanPolicy.statsFor(stats)
        assertEquals(listOf("ready", "running", "blocked", "done", "weird"), ui.chips.map { it.status })
        assertEquals(listOf(3, 2, 1, 10, 1), ui.chips.map { it.value })
        assertEquals(KanbanStatusTone.READY, ui.chips[0].tone)
        assertEquals("Всего 17 · старейшая ready: 1 ч", ui.summary)
    }

    @Test
    fun `stats without oldest ready still summarizes total and empty stats yield no summary`() {
        val noOldest = KanbanPolicy.statsFor(KanbanStats(byStatus = mapOf("done" to 5)))
        assertEquals("Всего задач: 5", noOldest.summary)

        val empty = KanbanPolicy.statsFor(KanbanStats())
        assertTrue(empty.chips.isEmpty())
        assertNull(empty.summary)
    }

    @Test
    fun `detail rows include present fields only and unassigned is explicit`() {
        val full = KanbanPolicy.detailRows(
            task(assignee = "mars", priority = 1, blockKind = null).copy(
                tenant = "default",
                branchName = "wave/1",
                consecutiveFailures = 2,
                goalMode = true,
                result = "готово",
            )
        )
        val labels = full.map { it.label }
        assertTrue(labels.containsAll(listOf("Исполнитель", "Приоритет", "Тенант", "Ветка", "Подряд провалов", "Режим", "Результат")))
        assertEquals("mars", full.first { it.label == "Исполнитель" }.value)
        assertEquals("goal", full.first { it.label == "Режим" }.value)

        val sparse = KanbanPolicy.detailRows(task(title = "x", status = "", assignee = " "))
        assertEquals(1, sparse.size)
        assertEquals(KanbanPolicy.UNASSIGNED_LABEL, sparse.single().value)
    }

    @Test
    fun `detail rows show block kind with recurrence count`() {
        val rows = KanbanPolicy.detailRows(
            task(status = "blocked", blockKind = "needs_input").copy(blockRecurrences = 3)
        )
        assertEquals("needs_input (x3)", rows.first { it.label == "Блокировка" }.value)
    }

    @Test
    fun `detail ui maps comments with age from injected clock and caps event tail`() {
        val detail = KanbanTaskDetail(
            task = task(id = "k-1", title = "Задача", status = "running", assignee = "mars"),
            comments = listOf(
                KanbanComment(id = 1, taskId = "k-1", author = "mars", body = "комментарий", createdAt = 900L),
                KanbanComment(id = 2, taskId = "k-1", author = "  ", body = "анонимный"),
            ),
            events = (1..12).map { KanbanTaskEvent(id = it.toLong(), taskId = "k-1", kind = "kind$it") },
            links = KanbanTaskLinks(parents = listOf("p-1"), children = listOf("c-1", "c-2")),
        )
        val ui = KanbanPolicy.detailUi(detail, nowEpochSeconds = 1800L)
        assertEquals("k-1", ui.id)
        assertEquals("running", ui.status)
        assertEquals(KanbanStatusTone.RUNNING, ui.tone)
        assertEquals(2, ui.comments.size)
        assertEquals("15 мин", ui.comments[0].whenLabel)
        assertEquals("?", ui.comments[1].author)
        assertNull(ui.comments[1].whenLabel)
        // Хвост событий: последние MAX_DETAIL_EVENTS, порядок сохранён.
        assertEquals(KanbanPolicy.MAX_DETAIL_EVENTS, ui.eventLabels.size)
        assertEquals("kind12", ui.eventLabels.last())
        assertEquals(listOf("p-1"), ui.parentIds)
        assertEquals(listOf("c-1", "c-2"), ui.childIds)
    }

    @Test
    fun `detail ui without clock hides comment age and blank body is dropped`() {
        val detail = KanbanTaskDetail(
            task = task(title = "T").copy(body = "  "),
            comments = listOf(KanbanComment(author = "a", body = "b", createdAt = 10L)),
        )
        val ui = KanbanPolicy.detailUi(detail)
        assertNull(ui.body)
        assertNull(ui.comments.single().whenLabel)
    }

    @Test
    fun `badges are compact counter labels`() {
        assertEquals("3 комм.", KanbanPolicy.commentsLabel(3))
        assertEquals("2 подзадач", KanbanPolicy.childrenLabel(2))
    }
}
