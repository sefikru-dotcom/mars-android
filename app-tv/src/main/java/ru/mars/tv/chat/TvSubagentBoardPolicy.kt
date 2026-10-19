package ru.mars.tv.chat

import ru.mars.network.model.SubagentState
import ru.mars.network.model.SubagentStatus

/**
 * Доска живых субагентов ТВ-чата — одна плашка, читаемая с дистанции (чистая
 * политика, JVM-тесты; паритет с мобильным `SubagentBoardPresenter`, но под пульт).
 *
 * Разрыв: ТВ-экран подписывался только на `ContextStatusUpdate`, поэтому кадры
 * `subagent.*`/`delegate.*` ([ru.mars.network.model.SubagentStatusUpdate]) уходили в
 * никуда — работа детей была невидима, хотя на мобильном она видна на карточке хода.
 *
 * Контракт отображения на 10 футов:
 * - строк не больше [MAX_LINES]: остальные сворачиваются в «и ещё N» (плашка не
 *   съедает транскрипт);
 * - активные дети идут первыми (Running, затем Waiting), терминальные — после, в
 *   порядке появления (как в мобильном презентере);
 * - текст строки: «имя — статус» плюс шаг, если сервер его прислал;
 * - подпись заголовка несёт счётчик: «Субагенты · 2 из 3 работают».
 *
 * Состояние считает core-редьюсер [ru.mars.network.repository.SubagentStatusPolicy];
 * здесь только презентация — никаких сетевых вызовов и таймеров.
 */
object TvSubagentBoardPolicy {

    /** Тон строки: цвет подбирает хост по палитре (глифов в тексте нет). */
    enum class Tone { RUNNING, DONE, ERROR, WARNING }

    /** Максимум строк детей в плашке; остальные схлопываются в счётчик. */
    const val MAX_LINES = 3

    /** Заголовок плашки без детей. */
    const val HEADER = "Субагенты"

    data class Board(
        /** Заголовок: «Субагенты · 2 из 3 работают» либо «Субагенты · 3». */
        val header: String,
        /** Строки детей (не больше [MAX_LINES]) — каждая готова к выводу как есть. */
        val lines: List<String>,
        /** Строка-свёртка «и ещё N», когда детей больше [MAX_LINES]; иначе null. */
        val overflow: String?,
        /** Сколько детей ещё работает (Running/Waiting). */
        val activeCount: Int,
        /** Тон заголовка: идёт работа -> RUNNING, все терминальны -> DONE/ERROR. */
        val tone: Tone,
    ) {
        /** Пустая карта — невидимая плашка. */
        val visible: Boolean get() = lines.isNotEmpty()

        /** Готовый текст для одного TextView (заголовок и строки через перевод строки). */
        val text: String
            get() = (listOf(header) + lines + listOfNotNull(overflow)).joinToString("\n")
    }

    /** Короткая русская подпись состояния (паритет с мобильным презентером). */
    fun statusLabel(state: SubagentState): String = when (state) {
        SubagentState.Running -> "работает"
        SubagentState.Completed -> "готово"
        SubagentState.Failed -> "провал"
        SubagentState.Waiting -> "ожидает"
        SubagentState.Stopped -> "остановлен"
    }

    fun tone(state: SubagentState): Tone = when (state) {
        SubagentState.Running -> Tone.RUNNING
        SubagentState.Completed -> Tone.DONE
        SubagentState.Failed -> Tone.ERROR
        SubagentState.Waiting -> Tone.WARNING
        SubagentState.Stopped -> Tone.WARNING
    }

    /** Собирает плашку из состояния редьюсера. Пустая карта — невидимая плашка. */
    fun present(board: Map<String, SubagentStatus>): Board {
        if (board.isEmpty()) return Board(HEADER, emptyList(), null, 0, Tone.DONE)
        val ordered = board.values.sortedWith(
            compareByDescending<SubagentStatus> { it.state.isActive }
                .thenBy { if (it.state == SubagentState.Running) 0 else 1 }
        )
        val activeCount = ordered.count { it.state.isActive }
        val header = if (activeCount > 0) "$HEADER · $activeCount из ${ordered.size} работают" else "$HEADER · ${ordered.size}"
        val shown = ordered.take(MAX_LINES).map(::line)
        val hidden = ordered.size - shown.size
        val tone = when {
            activeCount > 0 -> Tone.RUNNING
            ordered.any { it.state == SubagentState.Failed } -> Tone.ERROR
            else -> Tone.DONE
        }
        return Board(
            header = header,
            lines = shown,
            overflow = if (hidden > 0) "и ещё $hidden" else null,
            activeCount = activeCount,
            tone = tone,
        )
    }

    /** «имя — статус · шаг: …»; имя — displayName, иначе childId (догадок не выдумываем). */
    private fun line(status: SubagentStatus): String {
        val name = status.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: status.childId
        val phase = status.phase?.trim()?.takeIf { it.isNotEmpty() }
        val head = "$name — ${statusLabel(status.state)}"
        return if (phase != null) "$head · шаг: $phase" else head
    }
}
