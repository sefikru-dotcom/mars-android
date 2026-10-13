package ru.mars.mobile

import ru.mars.network.model.ChatEvent

/** Идентификатор компактного контрола строки параметров чата. */
enum class ParamRowControlId { MODEL, THINKING, CONTEXT }

/**
 * Один компактный контрол строки параметров: модель, уровень мышления или
 * индикатор контекста. Строка намеренно минималистична — только эти три
 * контрола: без YOLO-пилюли и без фильтра субагентов.
 */
data class ParamRowControl(
    val id: ParamRowControlId,
    val visible: Boolean,
    val label: String,
    /** Контрол откликается на нажатие (модель/мышление открывают выбор). */
    val enabled: Boolean,
    val tone: ComposerChipTone = ComposerChipTone.NEUTRAL,
    val contentDescription: String = label,
)

/** Состояние строки параметров: контролы в фиксированном порядке. */
data class ParamRowState(
    val model: ParamRowControl,
    val thinking: ParamRowControl,
    val context: ParamRowControl,
) {
    /** Все контролы в порядке отрисовки: модель, мышление, контекст. */
    val controls: List<ParamRowControl> get() = listOf(model, thinking, context)
}

/**
 * Входной снимок строки параметров. Activity собирает его из доменных
 * селекторов: [sessionModelLabel] — фактическая модель открытой сессии
 * (серверная правда, `ModelSelection.wireChoice`/`buttonLabel`),
 * [localModelLabel] — локальный выбор пользователя в пикере; они обновляются
 * независимо, как `sessionModel` и `current` в [ModelSelection].
 */
data class ParamRowSnapshot(
    val sessionModelLabel: String? = null,
    val localModelLabel: String? = null,
    val thinkingLabel: String? = null,
    /** Заполнение окна в процентах из metering-кадров; null — замер ещё не приходил. */
    val contextPercent: Int? = null,
    /** Идёт сжатие контекста — пилюля показывает его, а не замер. */
    val compressionRunning: Boolean = false,
)

/**
 * Чистая модель компактной строки параметров чата (UI -> state/domain):
 * модель и уровень мышления доступны прямо в чате, индикатор контекста виден
 * всегда — неизвестный замер показывает нейтральный плейсхолдер, а не
 * прячется по порогу. Без Android-импортов — тестируется на JVM;
 * [ChatActivity] лишь маппит состояние на views и форвардит нажатия.
 *
 * Цветовые пороги контекста делегирует [ContextBudgetPolicy] (тон пилюли
 * совпадает с [ComposerChipTone] функционального бара).
 */
class ChatParamRowPresenter {

    /** Фактическая модель открытой сессии (серверная правда); обновляется отдельно от выбора. */
    var sessionModelLabel: String? = null
        private set

    /** Локальный выбор пользователя в пикере моделей. */
    var localModelLabel: String? = null
        private set

    /** Подпись уровня мышления (`ReasoningEffortSelection.buttonLabel`). */
    var thinkingLabel: String? = null
        private set

    /** Последний замер заполнения контекста; null до первого metering-кадра. */
    var contextPercent: Int? = null
        private set

    /** Идёт сжатие контекста. */
    var compressionRunning: Boolean = false
        private set

    /**
     * Серверная модель сессии пришла/сменилась (bind сессии, chat/start ответ):
     * локальный выбор пользователя не затирается — он живёт в [localModelLabel].
     */
    fun setSessionModel(label: String?) {
        sessionModelLabel = safeLabel(label)
    }

    /** Пользователь выбрал модель в пикере; фактическая модель сессии не меняется. */
    fun setLocalModelChoice(label: String?) {
        localModelLabel = safeLabel(label)
    }

    /**
     * Эффективная подпись модели: фактическая модель сессии важнее локального
     * выбора — паритет с [ModelSelection.wireChoice] (`sessionModel ?: current`).
     */
    fun effectiveModelLabel(): String? = sessionModelLabel ?: localModelLabel

    fun setThinkingLabel(label: String?) {
        thinkingLabel = safeLabel(label)
    }

    /** Metering-кадр потока: процент заполнения окна контекста. */
    fun setContextUsage(usage: ChatEvent.ContextUsage?) {
        contextPercent = usage?.takeIf { it.maxTokens > 0 }?.percent
    }

    fun setCompressionRunning(running: Boolean) {
        compressionRunning = running
    }

    /** Текущий снимок строки из накопленного состояния. */
    fun snapshot(): ParamRowSnapshot = ParamRowSnapshot(
        sessionModelLabel = sessionModelLabel,
        localModelLabel = localModelLabel,
        thinkingLabel = thinkingLabel,
        contextPercent = contextPercent,
        compressionRunning = compressionRunning,
    )

    /** Полная резолюция состояния строки для рендера. */
    fun resolve(): ParamRowState = resolveWith(snapshot())

    companion object {
        /** Нейтральная подпись модели, пока ни сессия, ни пикер её не дали. */
        const val MODEL_FALLBACK_LABEL = "Модель"

        /** Подпись неактивного уровня мышления — паритет с [ReasoningEffortSelection.label]. */
        const val THINKING_FALLBACK_LABEL = "Авто"

        /** Плейсхолдер индикатора контекста до первого metering-кадра. */
        const val CONTEXT_UNKNOWN_LABEL = "—"

        /** Описание плейсхолдера контекста — паритет с [ContextBudgetPolicy.UNKNOWN_DETAILS]. */
        const val CONTEXT_UNKNOWN_DESCRIPTION = "Заполнение контекста ещё не измерено"

        /** Предел длины подписи контрола: строка компактная, длинные имена подрезаются хвостом. */
        const val LABEL_MAX = 28

        /**
         * Чистая резолюция состояния из снимка — для переиспользования без
         * экземпляра презентера и для тестов.
         */
        fun resolveWith(snapshot: ParamRowSnapshot): ParamRowState = ParamRowState(
            model = modelControl(snapshot.sessionModelLabel ?: snapshot.localModelLabel),
            thinking = thinkingControl(snapshot.thinkingLabel),
            context = contextControl(snapshot.contextPercent, snapshot.compressionRunning),
        )

        /**
         * Контрол модели: виден всегда (модель доступна прямо в чате), без
         * подписи — нейтральный плейсхолдер [MODEL_FALLBACK_LABEL].
         */
        fun modelControl(label: String?): ParamRowControl {
            val name = safeLabel(label)
            return ParamRowControl(
                id = ParamRowControlId.MODEL,
                visible = true,
                label = name ?: MODEL_FALLBACK_LABEL,
                enabled = true,
                contentDescription = if (name != null) "Модель: $name" else "Выбор модели",
            )
        }

        /**
         * Контрол уровня мышления: виден всегда; null/пусто/«Авто» показывают
         * [THINKING_FALLBACK_LABEL] — уровень доступен в чате даже неактивным.
         */
        fun thinkingControl(label: String?): ParamRowControl {
            val effort = safeLabel(label) ?: THINKING_FALLBACK_LABEL
            return ParamRowControl(
                id = ParamRowControlId.THINKING,
                visible = true,
                label = effort,
                enabled = true,
                contentDescription = "Уровень мышления: $effort",
            )
        }

        /**
         * Индикатор контекста: виден всегда — неизвестный замер показывает
         * плейсхолдер, а не прячется; процент приводится к 0..100, тон по
         * порогам [ContextBudgetPolicy]. Компрессия важнее замера.
         */
        fun contextControl(percent: Int?, compressionRunning: Boolean): ParamRowControl {
            if (compressionRunning) {
                return ParamRowControl(
                    id = ParamRowControlId.CONTEXT,
                    visible = true,
                    label = ContextBudgetPolicy.COMPRESSION_LABEL,
                    enabled = false,
                    tone = ComposerChipTone.WARNING,
                    contentDescription = "Идёт сжатие контекста",
                )
            }
            if (percent == null) {
                return ParamRowControl(
                    id = ParamRowControlId.CONTEXT,
                    visible = true,
                    label = CONTEXT_UNKNOWN_LABEL,
                    enabled = false,
                    contentDescription = CONTEXT_UNKNOWN_DESCRIPTION,
                )
            }
            val clamped = percent.coerceIn(0, 100)
            val tone = when (ContextBudgetPolicy.level(clamped)) {
                ContextBudgetLevel.NORMAL -> ComposerChipTone.NEUTRAL
                ContextBudgetLevel.WARNING -> ComposerChipTone.WARNING
                ContextBudgetLevel.CRITICAL -> ComposerChipTone.CRITICAL
            }
            return ParamRowControl(
                id = ParamRowControlId.CONTEXT,
                visible = true,
                label = "$clamped%",
                enabled = false,
                tone = tone,
                contentDescription = "Заполнение контекста: $clamped%",
            )
        }

        /** Безопасная подпись: trim, схлопнутые пробелы, пустое → null, длинное → «…хвост». */
        fun safeLabel(label: String?): String? {
            val collapsed = label?.trim()?.replace(WHITESPACE_RUN, " ")?.takeIf { it.isNotEmpty() }
                ?: return null
            return if (collapsed.length > LABEL_MAX) {
                "…" + collapsed.takeLast(LABEL_MAX - 1)
            } else {
                collapsed
            }
        }

        private val WHITESPACE_RUN = Regex("\\s+")
    }
}
