package ru.mars.tv.chat

/**
 * Чистая политика пикера параметров хода на ТВ (модель и уровень размышлений): строки
 * списка, начальная точка фокуса и D-pad-шаг. Без Android и I/O — тестируется на JVM
 * (паритет `TvQuickChipsPolicy`/`TvSessionBrowserPolicy`: экран только рендерит то,
 * что вернула политика).
 */
object TvTurnParamsPolicy {

    /** В пикере нечего фокусировать (пустой список). */
    const val NO_SELECTION = -1

    /**
     * Одна фокусируемая строка пикера: [label] — что показывает экран, [current] —
     * отметка «текущий выбор» (акцентный цвет и мета-строка вместо глифов: дизайн-гейт
     * ТВ запрещает символы-галочки).
     */
    data class PickerRow(val label: String, val current: Boolean)

    /** Строки пикера моделей: серверный порядок каталога, текущая — по id эффективного выбора. */
    fun modelRows(entries: List<TvModelPickerEntry>, currentModelId: String?): List<PickerRow> =
        entries.map { PickerRow(it.label, !currentModelId.isNullOrBlank() && it.model.id == currentModelId) }

    /**
     * Строки пикера уровней размышления: «Авто» и серверная лестница из
     * [TvReasoningSelection.options]; текущая — по эффективному уровню (null == null
     * отмечает «Авто»).
     */
    fun reasoningRows(options: List<TvReasoningOption>, effectiveEffort: String?): List<PickerRow> =
        options.map { PickerRow(it.label, it.effort == effectiveEffort) }

    /**
     * Строка, на которую встанет фокус при открытии пикера: текущий выбор, если он в
     * списке, иначе первая строка; [NO_SELECTION] для пустого списка (фокус остаётся на
     * кнопке «Закрыть»).
     */
    fun initialSelection(rows: List<PickerRow>): Int {
        if (rows.isEmpty()) return NO_SELECTION
        val current = rows.indexOfFirst { it.current }
        return if (current >= 0) current else 0
    }

    /**
     * D-pad UP/DOWN-шаг по списку: границы держат (без переноса — сорвавшийся фокус на ТВ
     * читается как сломанный пульт), выход за диапазон восстанавливается в сторону
     * движения: вниз -> первая строка, вверх -> последняя.
     */
    fun move(selection: Int, size: Int, delta: Int): Int {
        if (size <= 0) return NO_SELECTION
        if (selection !in 0 until size) return if (delta > 0) 0 else size - 1
        return (selection + delta).coerceIn(0, size - 1)
    }
}
