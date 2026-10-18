package ru.mars.mobile

/**
 * Единица отрисовки markdown-секции пузыря: ровно ОДИН view в контейнере.
 *
 * Зачем дробление: на кадре стрима текст пузыря дорастает на несколько символов,
 * а прежний путь сносил ВСЕ view секции и инфлейтил их заново — O(строк) TextView
 * и SpannableStringBuilder на каждый кадр. С единицами можно переиспользовать
 * неизменившийся префикс и пересобрать только хвост.
 */
sealed interface MarkdownUnit {
    /** Индекс блока в [ContentBlock]-списке: нужен code-блокам (фолд) и остаётся прежним. */
    val blockIndex: Int

    /** Одна строка [ContentBlock.Text] — один TextView. */
    data class Line(override val blockIndex: Int, val line: RichLine) : MarkdownUnit

    /** Целый code-блок — одна карточка с шапкой, скроллом и фолдом. */
    data class Code(override val blockIndex: Int, val block: ContentBlock.Code) : MarkdownUnit

    /** Целая таблица — одна карточка с горизонтальным скроллом. */
    data class Table(override val blockIndex: Int, val block: ContentBlock.Table) : MarkdownUnit
}

/**
 * План инкрементальной отрисовки markdown-секции — чистая JVM-политика (без Android).
 *
 * Инвариант, на котором держится переиспользование: вид каждой единицы зависит
 * ТОЛЬКО от её собственных данных, поэтому равные единицы дают визуально равные view
 * и их можно не пересобирать. Сравнение — структурное равенство data-классов
 * ([RichLine]/[ContentBlock] — data-классы).
 */
object StreamingMarkdownPlan {

    /**
     * Сколько хвостовых единиц пересобирать всегда, даже если они равны.
     *
     * Последняя строка стрима дорастает следующим чанком, а незакрытый ``` может
     * превратить уже нарисованный хвост в code-блок (парсер размечает его регексом
     * по остатку текста). Гарантия «последняя единица всегда свежая» стоит один
     * TextView на кадр и снимает целый класс залипших хвостов.
     */
    const val TAIL_GUARD = 1

    /** Разворачивает блоки парсера в плоский список единиц отрисовки (порядок = порядок view). */
    fun units(blocks: List<ContentBlock>): List<MarkdownUnit> {
        if (blocks.isEmpty()) return emptyList()
        val result = ArrayList<MarkdownUnit>(blocks.size * 2)
        blocks.forEachIndexed { blockIndex, block ->
            when (block) {
                is ContentBlock.Text -> block.lines.forEach { line ->
                    result.add(MarkdownUnit.Line(blockIndex, line))
                }
                is ContentBlock.Code -> result.add(MarkdownUnit.Code(blockIndex, block))
                is ContentBlock.Table -> result.add(MarkdownUnit.Table(blockIndex, block))
            }
        }
        return result
    }

    /**
     * Длина переиспользуемого префикса: сколько ведущих единиц уже нарисованы
     * и НЕ изменились. Всё, что после возвращённого индекса, хост сносит и рисует заново.
     */
    fun reusablePrefix(previous: List<MarkdownUnit>, current: List<MarkdownUnit>): Int {
        if (previous.isEmpty() || current.isEmpty()) return 0
        val limit = minOf(previous.size, current.size)
        var common = 0
        while (common < limit && previous[common] == current[common]) common++
        return (common - TAIL_GUARD).coerceAtLeast(0)
    }
}
