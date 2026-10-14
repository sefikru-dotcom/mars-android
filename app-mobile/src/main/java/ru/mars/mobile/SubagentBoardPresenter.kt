package ru.mars.mobile

import ru.mars.network.model.SubagentState
import ru.mars.network.model.SubagentStatus

/**
 * Доска живых статусов субагентов на карточке родительского хода (волна C,
 * фича B) — чистая JVM-презентация, без Android.
 *
 * Источник данных — редьюсер [ru.mars.network.repository.SubagentStatusPolicy]
 * (кадры `subagent_*`/`delegate.*` стрима). Контракт отображения:
 * - состояния детей читаются БЕЗ открытия дочернего экрана: имя, статус,
 *   текущий шаг и модель видны прямо на родительской карточке;
 * - активные дети (Running/Waiting) идут первыми, терминальные — в порядке
 *   появления; у Failed выводится причина провала, если сервер её прислал;
 * - подпись статуса — короткое русское слово; цвет/бейдж выбирает хост по
 *   [BoardBadge] (1:1 с именами MarsGradients.Status).
 */
object SubagentBoardPresenter {

    /** Ключ визуального бейджа строки (совпадает с именами MarsGradients.Status). */
    enum class BoardBadge { RUNNING, DONE, ERROR, WARNING, INFO }

    data class Row(
        val childId: String,
        val title: String,
        val state: SubagentState,
        val statusLabel: String,
        val badge: BoardBadge,
        /** Активный ребёнок: хост рисует индикатор активности (пульс/спиннер). */
        val active: Boolean,
        /** «шаг: … · модель» — детали хода ребёнка; null, если нечего показать. */
        val detail: String?,
        /** Причина провала (только [SubagentState.Failed]); null, если сервер её не прислал. */
        val failureReason: String?,
    )

    data class Board(
        val headerLabel: String,
        val rows: List<Row>,
        val activeCount: Int,
    ) {
        val visible: Boolean get() = rows.isNotEmpty()
    }

    private const val HEADER = "Субагенты"

    fun statusLabel(state: SubagentState): String = when (state) {
        SubagentState.Running -> "работает"
        SubagentState.Completed -> "готово"
        SubagentState.Failed -> "провал"
        SubagentState.Waiting -> "ожидает"
        SubagentState.Stopped -> "остановлен"
    }

    fun badge(state: SubagentState): BoardBadge = when (state) {
        SubagentState.Running -> BoardBadge.RUNNING
        SubagentState.Completed -> BoardBadge.DONE
        SubagentState.Failed -> BoardBadge.ERROR
        SubagentState.Waiting -> BoardBadge.WARNING
        SubagentState.Stopped -> BoardBadge.INFO
    }

    /**
     * Собирает доску из состояния редьюсера. Порядок: активные дети первыми
     * (Running, затем Waiting — по вставке), терминальные после (по вставке).
     * Пустая карта — невидимая доска (карточка без секции).
     */
    fun present(board: Map<String, SubagentStatus>): Board {
        if (board.isEmpty()) return Board(HEADER, emptyList(), 0)
        val ordered = board.values.sortedWith(
            compareByDescending<SubagentStatus> { it.state.isActive }
                .thenBy { if (it.state == SubagentState.Running) 0 else 1 }
        )
        val rows = ordered.map { status ->
            Row(
                childId = status.childId,
                title = status.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: status.childId,
                state = status.state,
                statusLabel = statusLabel(status.state),
                badge = badge(status.state),
                active = status.state.isActive,
                detail = detail(status),
                failureReason = status.failureReason
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.takeIf { status.state == SubagentState.Failed },
            )
        }
        val activeCount = rows.count { it.active }
        val header = if (activeCount > 0) "$HEADER · $activeCount/${rows.size} в работе" else "$HEADER · ${rows.size}"
        return Board(header, rows, activeCount)
    }

    /** «шаг: <phase> · <model>» из непустых частей; null, если обеих нет. */
    private fun detail(status: SubagentStatus): String? {
        val parts = mutableListOf<String>()
        status.phase?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += "шаг: $it" }
        status.model?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
        return parts.joinToString(" · ").ifEmpty { null }
    }
}
