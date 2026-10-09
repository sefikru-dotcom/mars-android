package ru.mars.mobile

import ru.mars.network.model.ChatEvent

/** Уровень заполнения контекстного окна — определяет цвет пилюли (как кольцо WebUI). */
enum class ContextBudgetLevel { NORMAL, WARNING, CRITICAL }

/**
 * Презентация пилюли контекста в композере: видимость, текст, уровень и описание.
 * [compression] — пилюля показывает идущее сжатие контекста, а не замер заполнения.
 */
data class ContextPillView(
    val visible: Boolean,
    val text: String?,
    val level: ContextBudgetLevel?,
    val contentDescription: String?,
    val compression: Boolean = false,
)

/**
 * Чистая политика контекстного бюджета (паритет с ctx-ring WebUI): процент +
 * использовано/всего токенов из `metering`-кадров потока, цветовые пороги
 * (зелёный ≤50, оранжевый ≤75, красный выше) и текст детального диалога.
 * Неизвестное окно не превращается в фейковый процент — пилюля скрыта.
 * Без Android-импортов (формат токенов — [formatTokenCount]) — тестируется на JVM.
 */
object ContextBudgetPolicy {
    /** Порог предупреждения: больше 50% — оранжевый. */
    const val WARNING_PERCENT = 50

    /** Порог опасности: больше 75% — красный. */
    const val CRITICAL_PERCENT = 75

    /** Подпись пилюли во время ручной/авто компрессии контекста. */
    const val COMPRESSION_LABEL = "Сжатие…"

    /** Детали, когда сервер ещё не присылал metering-кадр. */
    const val UNKNOWN_DETAILS = "Заполнение ещё не измерено — данные появятся после первого хода."

    fun level(percent: Int): ContextBudgetLevel = when {
        percent > CRITICAL_PERCENT -> ContextBudgetLevel.CRITICAL
        percent > WARNING_PERCENT -> ContextBudgetLevel.WARNING
        else -> ContextBudgetLevel.NORMAL
    }

    /** Состояние пилюли композера: компрессия важнее замера, неизвестный замер скрыт. */
    fun pill(usage: ChatEvent.ContextUsage?, compressionRunning: Boolean): ContextPillView {
        if (compressionRunning) {
            return ContextPillView(
                visible = true,
                text = COMPRESSION_LABEL,
                level = ContextBudgetLevel.WARNING,
                contentDescription = "Идёт сжатие контекста",
                compression = true,
            )
        }
        if (usage == null || usage.maxTokens <= 0) {
            return ContextPillView(visible = false, text = null, level = null, contentDescription = null)
        }
        return ContextPillView(
            visible = true,
            text = "${usage.percent}% · ${formatTokenCount(usage.usedTokens)}/${formatTokenCount(usage.maxTokens)}",
            level = level(usage.percent),
            contentDescription = "Заполнение контекста: ${usage.percent}%",
        )
    }

    /** Текст детального диалога контекста (заполнено / использовано / размер окна). */
    fun detailsText(usage: ChatEvent.ContextUsage?): String =
        if (usage != null && usage.maxTokens > 0) {
            "Заполнено: ${usage.percent}%\n" +
                "Использовано токенов: ${formatTokenCount(usage.usedTokens)}\n" +
                "Размер окна: ${formatTokenCount(usage.maxTokens)}"
        } else {
            UNKNOWN_DETAILS
        }
}
