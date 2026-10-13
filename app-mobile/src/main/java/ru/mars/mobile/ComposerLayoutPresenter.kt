package ru.mars.mobile

/** Режим плашки над полем ввода: полная цитата сообщения или ответ на него. */
enum class ComposerQuoteMode(val title: String) {
    QUOTE("Цитата"),
    REPLY("Ответ"),
}

/**
 * Активная цитата/ответ, сохранённая презентером: исходные данные, из которых
 * строятся плашка ([ComposerQuotePlate]) и исходящий текст ([ComposerLayoutPresenter.outgoingText]).
 */
data class ActiveComposerQuote(
    val mode: ComposerQuoteMode,
    val author: String,
    val messageText: String,
)

/** Плашка активной цитаты/ответа — верхний уровень композера под полем ввода. */
data class ComposerQuotePlate(
    val visible: Boolean,
    val mode: ComposerQuoteMode?,
    /** Заголовок плашки: «Цитата · Марс» (без автора — просто «Цитата»). */
    val title: String,
    /** Компактный однострочный отрывок сообщения. */
    val excerpt: String,
    /** Описание крестика отмены (доступность). */
    val cancelDescription: String,
) {
    companion object {
        /** Состояние скрытой плашки (цитаты нет). */
        val HIDDEN = ComposerQuotePlate(
            visible = false,
            mode = null,
            title = "",
            excerpt = "",
            cancelDescription = "",
        )
    }
}

/** Визуальный тон чипа: обычный, рубиновый (автономность), предупреждение/опасность (контекст). */
enum class ComposerChipTone { NEUTRAL, RUBY, WARNING, CRITICAL }

/** Стабильный идентификатор чипа сессии (маппинг иконок живёт в Activity). */
enum class ComposerChipId { MODEL, REASONING, WORKSPACE, AUTONOMY, CONTEXT }

/** Чип функционального бара — нижний уровень композера, слева в горизонтальном скролле. */
data class ComposerChip(
    val id: ComposerChipId,
    val visible: Boolean,
    val label: String,
    /** Шеврон справа — чип открывает выбор (модель/рассуждения/воркспейс). */
    val showChevron: Boolean = false,
    val tone: ComposerChipTone = ComposerChipTone.NEUTRAL,
    val contentDescription: String = label,
) {
    companion object {
        /** Невидимый чип-заглушка: [ComposerLayoutPresenter.chipsRow] такие отфильтровывает. */
        fun hidden(id: ComposerChipId) = ComposerChip(id = id, visible = false, label = "")
    }
}

/** Режим главной кнопки композера: отправка сообщения или остановка потока. */
enum class ComposerSendMode(val label: String) {
    SEND("Отправить"),
    STOP("Остановить"),
}

/** Состояние кнопки отправки/остановки — справа в функциональном баре. */
data class ComposerSendButton(
    val mode: ComposerSendMode,
    val enabled: Boolean,
    /** True — иконка стопа (идёт поток), false — иконка отправки. */
    val showStopIcon: Boolean,
    val contentDescription: String = mode.label,
)

/** Компактная кнопка действия (скрепка/микрофон): активность и описание для доступности. */
data class ComposerActionButton(
    val enabled: Boolean,
    val active: Boolean,
    val contentDescription: String,
)

/**
 * Входной снимок для резолюции композера. Activity собирает его из [ChatUiState]
 * и доменных селекторов ([ModelSelection.buttonLabel], [WorkspaceSelection.current],
 * [ReasoningEffortSelection.buttonLabel], заполнение контекста из metering-кадров).
 */
data class ComposerSnapshot(
    val draft: String = "",
    val status: ChatStatus = ChatStatus.IDLE,
    val hasAttachments: Boolean = false,
    val modelLabel: String? = null,
    val reasoningLabel: String? = null,
    val workspacePath: String? = null,
    val autonomyEnabled: Boolean = false,
    val contextPercent: Int? = null,
    val recordingVoice: Boolean = false,
)

/** Полное состояние двухуровневого композера (WebUI parity) для маппинга на views. */
data class ComposerViewState(
    val hint: String,
    val maxInputLines: Int,
    val quotePlate: ComposerQuotePlate,
    /** Видимые чипы в порядке: модель, reasoning, воркспейс, автономность, контекст. */
    val chips: List<ComposerChip>,
    val send: ComposerSendButton,
    val attach: ComposerActionButton,
    val mic: ComposerActionButton,
)

/**
 * Чистая модель состояния двухуровневого композера (WebUI parity):
 * верхний уровень — расширяемое поле ввода и плашка цитаты/ответа, нижний —
 * функциональный бар со скроллом чипов сессии слева и группой действий справа.
 * Без Android-импортов — тестируется на JVM; [ChatActivity] лишь маппит состояние
 * на views и форвардит нажатия. Формат цитаты делегирует [MessageActionPolicy],
 * цветовые пороги контекста — [ContextBudgetPolicy].
 */
class ComposerLayoutPresenter {
    /** Активная цитата/ответ; null, пока пользователь не выбрал действие в меню сообщения. */
    var activeQuote: ActiveComposerQuote? = null
        private set

    /** Состояние плашки для текущей [activeQuote]. */
    val quotePlate: ComposerQuotePlate get() = plateFor(activeQuote)

    /** «Цитировать» из меню сообщения. Пустой текст не цитируется — плашка не меняется. */
    fun setQuote(messageText: String, author: String = AUTHOR_MARS): ComposerQuotePlate =
        activate(ComposerQuoteMode.QUOTE, messageText, author)

    /** «Ответить» на сообщение. Пустой текст игнорируется, как в [setQuote]. */
    fun setReply(messageText: String, author: String = AUTHOR_MARS): ComposerQuotePlate =
        activate(ComposerQuoteMode.REPLY, messageText, author)

    /** Сброс цитаты/ответа: крестик плашки или очистка после успешной отправки. */
    fun clearQuote(): ComposerQuotePlate {
        activeQuote = null
        return ComposerQuotePlate.HIDDEN
    }

    /**
     * Исходящий текст: пока цитата активна, её markdown-блок встаёт сверху черновика
     * (цитата всегда сверху, как в [MessageActionPolicy.composeQuote]). После отправки
     * Activity вызывает [clearQuote].
     */
    fun outgoingText(draft: String): String {
        val quote = activeQuote ?: return draft
        val block = MessageActionPolicy.quoteBlock(quote.messageText)
        return if (block.isEmpty()) draft else block + draft
    }

    /** Полная резолюция состояния композера для рендера. */
    fun resolve(snapshot: ComposerSnapshot = ComposerSnapshot()): ComposerViewState =
        resolveWith(activeQuote, snapshot)

    private fun activate(mode: ComposerQuoteMode, messageText: String, author: String): ComposerQuotePlate {
        if (messageText.isBlank()) return quotePlate
        activeQuote = ActiveComposerQuote(mode, author.trim(), messageText)
        return quotePlate
    }

    companion object {
        /** Подсказка поля ввода (как в ChatActivity и WebUI). */
        const val HINT = "Сообщение Марсу…"

        /** Поле ввода расширяется максимум до пяти строк, дальше внутренний скролл. */
        const val MAX_INPUT_LINES = 5

        /** Автор по умолчанию для цитат ответа ассистента. */
        const val AUTHOR_MARS = "Марс"

        /** Предел отрывка в плашке: компактный предпросмотр, а не вся цитата. */
        const val PLATE_EXCERPT_CHARS = 120

        /** Подпись пилюли автономности (без вариантов — состояние передаёт тон). */
        const val AUTONOMY_LABEL = "Автономность"

        /** Метка «Авто» у reasoning-селектора: уровень не активен — чип скрыт. */
        const val REASONING_AUTO_LABEL = "Авто"

        /** Предел длины подписи чипа воркспейса (сокращаем хвостом пути). */
        const val WORKSPACE_LABEL_MAX = 24

        /**
         * Чистая резолюция состояния из внешней цитаты и снимка — для переиспользования
         * без экземпляра презентера и для тестов.
         */
        fun resolveWith(quote: ActiveComposerQuote?, snapshot: ComposerSnapshot): ComposerViewState =
            ComposerViewState(
                hint = HINT,
                maxInputLines = MAX_INPUT_LINES,
                quotePlate = plateFor(quote),
                chips = chipsRow(snapshot),
                send = sendButton(snapshot.status, snapshot.draft, snapshot.hasAttachments),
                attach = attachButton(snapshot.status),
                mic = micButton(snapshot.status, snapshot.recordingVoice),
            )

        /** Плашка по активной цитате: null/пустой текст — скрытая плашка. */
        fun plateFor(quote: ActiveComposerQuote?): ComposerQuotePlate {
            if (quote == null || quote.messageText.isBlank()) return ComposerQuotePlate.HIDDEN
            val title = if (quote.author.isBlank()) quote.mode.title else "${quote.mode.title} · ${quote.author}"
            return ComposerQuotePlate(
                visible = true,
                mode = quote.mode,
                title = title,
                excerpt = plateExcerpt(quote.messageText),
                cancelDescription = when (quote.mode) {
                    ComposerQuoteMode.QUOTE -> "Отменить цитату"
                    ComposerQuoteMode.REPLY -> "Отменить ответ"
                },
            )
        }

        /** Однострочный отрывок для плашки: переводы строк схлопываются, хвост помечается «…». */
        fun plateExcerpt(messageText: String): String {
            val collapsed = messageText.trim().lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            if (collapsed.length <= PLATE_EXCERPT_CHARS) return collapsed
            return collapsed.take(PLATE_EXCERPT_CHARS).trimEnd() + "…"
        }

        /** Видимые чипы сессии в фиксированном порядке: модель, reasoning, воркспейс, автономность, контекст. */
        fun chipsRow(snapshot: ComposerSnapshot): List<ComposerChip> = listOf(
            modelChip(snapshot.modelLabel),
            reasoningChip(snapshot.reasoningLabel),
            workspaceChip(snapshot.workspacePath),
            autonomyChip(snapshot.autonomyEnabled),
            contextChip(snapshot.contextPercent),
        ).filter { it.visible }

        /** Чип модели с шевроном; без подписи (каталог ещё не загружен) — скрыт. */
        fun modelChip(label: String?): ComposerChip {
            val name = label?.trim().orEmpty()
            if (name.isEmpty()) return ComposerChip.hidden(ComposerChipId.MODEL)
            return ComposerChip(
                id = ComposerChipId.MODEL,
                visible = true,
                label = name,
                showChevron = true,
                contentDescription = "Модель: $name",
            )
        }

        /** Чип уровня рассуждений; «Авто»/пусто означает неактивный уровень — чип скрыт. */
        fun reasoningChip(label: String?): ComposerChip {
            val effort = label?.trim().orEmpty()
            if (effort.isEmpty() || effort.equals(REASONING_AUTO_LABEL, ignoreCase = true)) {
                return ComposerChip.hidden(ComposerChipId.REASONING)
            }
            return ComposerChip(
                id = ComposerChipId.REASONING,
                visible = true,
                label = effort,
                showChevron = true,
                contentDescription = "Рассуждения: $effort",
            )
        }

        /** Чип воркспейса: сокращённый путь в подписи, полный — в описании. */
        fun workspaceChip(path: String?): ComposerChip {
            val full = path?.trim().orEmpty()
            if (full.isEmpty()) return ComposerChip.hidden(ComposerChipId.WORKSPACE)
            return ComposerChip(
                id = ComposerChipId.WORKSPACE,
                visible = true,
                label = shortenWorkspacePath(full),
                showChevron = true,
                contentDescription = "Воркспейс: $full",
            )
        }

        /** Сокращение пути до последнего сегмента; слишком длинный сегмент подрезается спереди «…». */
        fun shortenWorkspacePath(path: String?): String {
            val full = path?.trim().orEmpty()
            if (full.isEmpty()) return ""
            val tail = full.trim('/').substringAfterLast('/')
            val segment = tail.ifBlank { full }
            return if (segment.length > WORKSPACE_LABEL_MAX) {
                "…" + segment.takeLast(WORKSPACE_LABEL_MAX - 1)
            } else {
                segment
            }
        }

        /** Пилюля автономности: видна всегда, активный режим подсвечивается рубиновым тоном. */
        fun autonomyChip(enabled: Boolean): ComposerChip = ComposerChip(
            id = ComposerChipId.AUTONOMY,
            visible = true,
            label = AUTONOMY_LABEL,
            tone = if (enabled) ComposerChipTone.RUBY else ComposerChipTone.NEUTRAL,
            contentDescription = if (enabled) {
                "Автономность включена — действия выполняются без подтверждений"
            } else {
                "Автономность выключена — действия требуют подтверждения"
            },
        )

        /**
         * Индикатор контекста: процент заполнения окна, тон по порогам [ContextBudgetPolicy].
         * Неизвестный замер (null) или безумный процент чип не показывают — как пилюля WebUI.
         */
        fun contextChip(percent: Int?): ComposerChip {
            if (percent == null || percent < 0 || percent > 100) return ComposerChip.hidden(ComposerChipId.CONTEXT)
            val tone = when (ContextBudgetPolicy.level(percent)) {
                ContextBudgetLevel.NORMAL -> ComposerChipTone.NEUTRAL
                ContextBudgetLevel.WARNING -> ComposerChipTone.WARNING
                ContextBudgetLevel.CRITICAL -> ComposerChipTone.CRITICAL
            }
            return ComposerChip(
                id = ComposerChipId.CONTEXT,
                visible = true,
                label = "$percent%",
                tone = tone,
                contentDescription = "Заполнение контекста: $percent%",
            )
        }

        /**
         * Кнопка отправки/остановки: TYPING (идёт поток) — стоп, доступен всегда;
         * иначе — отправка, доступна при непустом черновике или вложениях.
         */
        fun sendButton(status: ChatStatus, draft: String, hasAttachments: Boolean): ComposerSendButton {
            if (status == ChatStatus.TYPING) {
                return ComposerSendButton(
                    mode = ComposerSendMode.STOP,
                    enabled = true,
                    showStopIcon = true,
                )
            }
            return ComposerSendButton(
                mode = ComposerSendMode.SEND,
                enabled = draft.isNotBlank() || hasAttachments,
                showStopIcon = false,
            )
        }

        /** Скрепка: прикрепить файл к следующему ходу нельзя, пока идёт поток. */
        fun attachButton(status: ChatStatus): ComposerActionButton = ComposerActionButton(
            enabled = status != ChatStatus.TYPING,
            active = false,
            contentDescription = "Прикрепить файл",
        )

        /** Микрофон: во время записи кнопка останавливает её; во время потока диктовка недоступна. */
        fun micButton(status: ChatStatus, recordingVoice: Boolean): ComposerActionButton {
            if (recordingVoice) {
                return ComposerActionButton(enabled = true, active = true, contentDescription = "Остановить запись")
            }
            return ComposerActionButton(
                enabled = status != ChatStatus.TYPING,
                active = false,
                contentDescription = "Голосовой ввод",
            )
        }
    }
}
