package ru.mars.mobile

import ru.mars.network.repository.SessionToolsetsOutcome

/** Состояние диалога выбора инструментов: что уже выбрано и чем закончилось сохранение. */
enum class ToolsetSaveState { IDLE, SAVING, SAVED, ERROR }

/**
 * Презентер чипа набора инструментов сессии (A9): превращает серверное состояние
 * `enabled_toolsets` и результат записи в модель для отрисовки. Без Android-импортов.
 */
class SessionToolsetsPresenter {
    /** Последнее известное серверное значение; null — набор по умолчанию. */
    var enabledToolsets: List<String>? = null
        private set

    var saveState: ToolsetSaveState = ToolsetSaveState.IDLE
        private set

    var errorMessage: String? = null
        private set

    /** Начальное состояние выбора в диалоге — то, что стоит на сервере. */
    val selection: Set<String>
        get() = SessionToolsetsPolicy.normalize(enabledToolsets.orEmpty()).toSet()

    /** Подпись чипа в композере. */
    fun chipLabel(): String = SessionToolsetsPolicy.chipLabel(enabledToolsets)

    /** Описание чипа для доступности. */
    fun chipDescription(): String = SessionToolsetsPolicy.chipDescription(enabledToolsets)

    /** Строка статуса под чипом; null — показывать нечего (тишина вместо «Сохранено» на старте). */
    fun statusText(): String? = when (saveState) {
        ToolsetSaveState.IDLE -> null
        ToolsetSaveState.SAVING -> "Сохраняю набор инструментов…"
        ToolsetSaveState.SAVED -> SessionToolsetsPolicy.savedMessage(enabledToolsets)
        ToolsetSaveState.ERROR -> errorMessage ?: "Не удалось сохранить набор инструментов"
    }

    /** Начало сохранения: состояние SAVING, прежняя ошибка сбрасывается. */
    fun onSaving() {
        saveState = ToolsetSaveState.SAVING
        errorMessage = null
    }

    /** Успех: серверное значение становится источником истины для чипа. */
    fun onSaved(toolsets: List<String>?) {
        enabledToolsets = toolsets
        saveState = ToolsetSaveState.SAVED
        errorMessage = null
    }

    /** Провал: серверное значение НЕ меняется — чип не должен показывать то, чего нет. */
    fun onFailed(message: String?) {
        saveState = ToolsetSaveState.ERROR
        errorMessage = message?.trim().takeUnless { it.isNullOrEmpty() } ?: "Не удалось сохранить набор инструментов"
    }

    /** Подхват серверного значения при открытии сессии (без вспышки статуса). */
    fun bind(toolsets: List<String>?) {
        enabledToolsets = toolsets
        saveState = ToolsetSaveState.IDLE
        errorMessage = null
    }

    /** Применяет результат репозитория к состоянию презентера. */
    fun apply(outcome: SessionToolsetsOutcome) {
        when (outcome) {
            is SessionToolsetsOutcome.Success -> onSaved(outcome.enabledToolsets)
            is SessionToolsetsOutcome.Failure -> onFailed(outcome.message)
        }
    }

    companion object {
        /**
         * Пункты для диалога множественного выбора: подпись + описание.
         * Порядок фиксирован справочником, чтобы список не «прыгал» между открытиями.
         */
        fun dialogEntries(): List<ToolsetOption> = SessionToolsetsPolicy.ALL
    }
}
