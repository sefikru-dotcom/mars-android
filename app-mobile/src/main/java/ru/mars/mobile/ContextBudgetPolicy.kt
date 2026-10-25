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
    /** Процент заполнения окна — дуга кольца ([ContextRingView]); null при сжатии. */
    val percent: Int? = null,
    /**
     * Подпись серверного порога автосжатия («Автосжатие при 160.0k · 80%») — чтобы порог
     * был понятен без открытия диалога; null, когда сервер порог не сообщил или окно
     * неизвестно. Пустая строка/подпись без порога НЕ синтезируется.
     */
    val autoCompression: String? = null,
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

    /** Порог автосжатия неизвестен (сервер не прислал `threshold_tokens`). */
    const val THRESHOLD_UNKNOWN = "порог не сообщён"

    fun level(percent: Int): ContextBudgetLevel = when {
        percent > CRITICAL_PERCENT -> ContextBudgetLevel.CRITICAL
        percent > WARNING_PERCENT -> ContextBudgetLevel.WARNING
        else -> ContextBudgetLevel.NORMAL
    }

    /**
     * Процент окна, на котором сработает СЕРВЕРНОЕ автосжатие: `round(threshold/window*100)`
     * — ровно как `_syncCtxIndicator` в WebUI (`Math.round(threshold/ctxWindow*100)`).
     * null, когда окно или порог неизвестны (порог `<= 0`). Не клампится: порог больше
     * окна даёт >100% — это честный сигнал «сожмёт уже никогда», а не тихая подмена.
     */
    fun thresholdPercent(usage: ChatEvent.ContextUsage?): Int? {
        if (usage == null || usage.maxTokens <= 0 || usage.thresholdTokens <= 0) return null
        return Math.round(usage.thresholdTokens.toLong() * 100.0 / usage.maxTokens).toInt()
    }

    /**
     * Подпись порога автосжатия — эталон WebUI (`context_auto_compress_at`):
     * «Автосжатие при 160.0k · 80%». null, когда сервер порог не сообщил: подпись без
     * числа («Автосжатие при …») вводила бы в заблуждение.
     */
    fun autoCompressionLabel(usage: ChatEvent.ContextUsage?): String? {
        if (usage == null) return null
        val percent = thresholdPercent(usage) ?: return null
        return "Автосжатие при ${formatTokenCount(usage.thresholdTokens)} · $percent%"
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
        // Подпись кольца: процент заполнения + порог серверного автосжатия, чтобы владелец
        // видел «когда сожмёт» не открывая диалог. Неизвестный порог подпись не удлиняет.
        val autoCompression = autoCompressionLabel(usage)
        val description = buildString {
            append("Заполнение контекста: ${usage.percent}%")
            if (autoCompression != null) append(". $autoCompression")
        }
        return ContextPillView(
            visible = true,
            text = "${usage.percent}% · ${formatTokenCount(usage.usedTokens)}/${formatTokenCount(usage.maxTokens)}",
            level = level(usage.percent),
            contentDescription = description,
            percent = usage.percent,
            autoCompression = autoCompression,
        )
    }

    /**
     * Текст детального диалога контекста: заполнено / использовано / размер окна и порог
     * СЕРВЕРНОГО автосжатия (в токенах и в проценте окна). Локальное предпочтение
     * `AutoCompressionConfig.thresholdPercent` здесь не показывается — это отдельный,
     * клиентский режим; диалог подписывает его своей строкой «Локальный авто-режим при N%».
     */
    fun detailsText(usage: ChatEvent.ContextUsage?): String {
        if (usage == null || usage.maxTokens <= 0) return UNKNOWN_DETAILS
        val percent = thresholdPercent(usage)
        val thresholdLine = if (percent != null) {
            "Сервер сожмёт при: ${formatTokenCount(usage.thresholdTokens)} · $percent%"
        } else {
            "Сервер сожмёт при: $THRESHOLD_UNKNOWN"
        }
        return "Заполнено: ${usage.percent}%\n" +
            "Использовано токенов: ${formatTokenCount(usage.usedTokens)}\n" +
            "Размер окна: ${formatTokenCount(usage.maxTokens)}\n" +
            thresholdLine
    }
}
