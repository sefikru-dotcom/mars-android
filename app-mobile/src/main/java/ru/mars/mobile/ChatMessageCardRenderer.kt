package ru.mars.mobile

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Рендерер ОДНОЙ карточки сообщения транскрипта: пузырь, шапка (автор, модель, час,
 * вложения), thinking-карточка, сводка/карточки вызовов инструментов, доска субагентов
 * и markdown-секция с кнопками действий.
 *
 * Вынесено из ChatActivity дословно (чистый рефакторинг): входы и выходы те же, поэтому
 * инкрементальный рендер и `cardEntries` не меняются — Activity по-прежнему кладёт
 * [CardViews] в wrapper и запоминает [CardViews.markdownHost]. Рендерер НЕ держит
 * Activity: всё, что раньше читалось/дёргалось напрямую (состояние [ChatUiState],
 * markdown, лайтбокс, озвучка, шторка действий), приходит через узкий [Host].
 *
 * Контракты, которые нельзя терять при правках:
 * - `card.tag = index` — по этому тегу поиск по диалогу находит карточку;
 * - markdown-секция создаётся только при непустом тексте (иначе null);
 * - копия сообщения/действия резолвятся по индексу В МОМЕНТ клика (карточка живёт кадры стрима).
 */
object ChatMessageCardRenderer {

    /**
     * Узкий хост карточки: данные состояния и колбэки вместо самого Activity.
     * Держится намеренно маленьким — новый доступ к Activity должен быть осознанным.
     */
    interface Host {
        /** Сообщение по индексу на момент клика (снимок при сборке устаревает за кадры стрима). */
        fun messageAt(index: Int): ChatMessage?

        /** Подпись локального пикера моделей — фоллбэк, пока сервер не прислал модель хода. */
        fun modelLabel(): String?

        /** Режим активности: задаёт НАЧАЛЬНУЮ свёртку набора инструментов. */
        val activityMode: ActivityDisplayMode

        /** Ручное переопределение свёртки группы инструментов (null — не переопределено). */
        fun isToolGroupExpanded(messageIndex: Int): Boolean?

        fun toggleThoughts(messageIndex: Int)

        fun toggleTool(messageIndex: Int, toolIndex: Int)

        fun toggleToolGroup(messageIndex: Int, currentlyExpanded: Boolean)

        fun isCodeBlockExpanded(messageIndex: Int, blockIndex: Int): Boolean

        /** Фолд code-блока меняет «эпоху хрома» и перерисовывает транскрипт целиком. */
        fun toggleCodeBlock(messageIndex: Int, blockIndex: Int)

        /** Перерисовать транскрипт после смены локального состояния карточки. */
        fun rerenderTranscript()

        fun copyMessage(text: String)

        fun speakMessage(text: String)

        fun showMessageActions(message: ChatMessage, index: Int)

        fun openImage(rawUrl: String)

        fun openUrl(url: String)

        /** Button-role + stateDescription для раскрывающихся заголовков ([ChatA11yPolicy]). */
        fun a11yHeader(header: View, description: String, state: String)
    }

    /** Корень пузыря + его markdown-секция (null, если текст пуст — секции нет). */
    class CardViews(
        val root: View,
        val markdownHost: LinearLayout?,
        /** Состояние инкрементальной отрисовки секции: переживает кадры стрима. */
        val markdownState: ChatMarkdownRenderer.MarkdownRenderState? = null,
    )

    /**
     * Сборка ОДНОЙ карточки сообщения. Тело перенесено из прежнего монолитного renderMessages
     * почти дословно; отличия: snapshot.streamingFlag вместо пересчёта, markdown в отдельной
     * секции (для text-only обновлений), текст/действия резолвятся по индексу в момент клика
     * (карточка теперь переживает кадры стрима и захваченная копия устаревает).
     */
    fun build(
        context: Context,
        message: ChatMessage,
        index: Int,
        snapshot: CardSnapshot,
        host: Host,
    ): CardViews {
        fun dp(value: Int) = MarsTheme.dp(context, value)
        // Имя сохранено прежним: call sites заголовков остались дословными, а сама семантика
        // (button-role + stateDescription) живёт в Activity-хелпере за [Host.a11yHeader].
        val applyExpandableHeaderA11y = host::a11yHeader

        val isUser = message.role == "user"
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = if (isUser) {
                MarsTheme.userBubble(context)
            } else {
                MarsTheme.botMessageCard(context)
            }
        }

        val labelRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        if (!isUser) {
            // Vector status dot with ambient glow (WebUI parity)
            val dot = MarsTheme.glowingStatusDot(context, MarsTheme.STATUS_ONLINE)
            labelRow.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
        }
        val label = TextView(context).apply {
            text = if (isUser) "Вы" else "Марс"
            setTextColor(if (isUser) MarsTheme.TEXT_MUTED else MarsTheme.TEXT_STRONG)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        }
        labelRow.addView(label)

        // Шапка: час сообщения ОБЯЗАН получить свою ширину раньше пилюли модели.
        // LinearLayout (HORIZONTAL) меряет детей слева направо и последнему ребёнку
        // отдаёт остаток строки. Пилюля с wrap_content и ДЛИННЫМ именем модели
        // (история берёт каталожную подпись вида «Gemini 3.8 Flash · vision и быстрые
        // задачи» — 41 символ, до 186dp при 9.5sp, и это ещё без fontScale 1.15–1.3)
        // съедала всю строку, и часу доставался MeasureSpec AT_MOST 0 → нулевая
        // ширина → на экране НИЧЕГО. Поэтому пилюля живёт в контейнере с
        // width=0+weight: в первом проходе LinearLayout такое дитя не меряет, часы
        // получают полную ширину, а пилюля забирает остаток и обрезается ellipsis'ом.
        var headerHasFlex = false
        if (!isUser) {
            // Фактическая модель хода (волна C, фича A): приоритет — серверный штамп
            // пузыря (открывающий кадр context_status, фоллбэк — effective_model из
            // chat/start, подпись — TurnModelPolicy); локальный выбор пикера остаётся
            // фоллбэком ТОЛЬКО до прихода серверного значения, чужая устаревшая
            // модель в шапке ответа больше не висит.
            val modelName = TurnModelPolicy.label(message.model, message.modelProvider)
                ?.let { ChatParamRowPresenter.safeLabel(it) }
                ?: host.modelLabel()
            modelName?.let { pillLabel ->
                val modelPill = TextView(context).apply {
                    text = pillLabel
                    textSize = 9.5f
                    setTextColor(MarsTheme.ACCENT_TEXT)
                    background = MarsTheme.pill(context, MarsTheme.SURFACE_0, strokeColor = MarsTheme.BORDER_SUBTLE)
                    setPadding(dp(6), dp(1), dp(6), dp(1))
                    // Одна строка: длинная подпись модели не переносится на вторую
                    // строку шапки и не отбирает место у часов.
                    isSingleLine = true
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                val pillHost = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(modelPill, LinearLayout.LayoutParams(-2, -2))
                }
                labelRow.addView(
                    pillHost,
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) },
                )
                headerHasFlex = true
            }
        }

        // Час сообщения справа в шапке карточки; при неизвестной метке не показываем.
        // Подпись — 11sp и TEXT_EM вместо прежних 10.5sp TEXT_MUTED: мелкий серый час
        // на тёмной карточке владелец не замечал, хотя технически он рисовался.
        MessageTimePolicy.clockLabel(message.timestamp)?.let { clock ->
            // Карточка пользователя («Вы» + час) не имеет пилюли с весом — распорка.
            if (!headerHasFlex) labelRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            labelRow.addView(
                TextView(context).apply {
                    text = clock
                    textSize = 11f
                    setTextColor(MarsTheme.TEXT_EM)
                    isSingleLine = true
                    maxLines = 1
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) },
            )
        }
        card.addView(labelRow)

        if (message.attachments.isNotEmpty()) {
            card.addView(TextView(context).apply {
                text = message.attachments.joinToString("\n") { "[Файл: $it]" }
                setTextColor(MarsTheme.TEXT_MUTED)
                textSize = 12f
                setPadding(0, dp(4), 0, 0)
            })
        }

        var markdownHost: LinearLayout? = null
        var markdownState: ChatMarkdownRenderer.MarkdownRenderState? = null
        if (message.text.isNotBlank()) {
            // markdown — в отдельную секцию: кадры стрима пересобирают только её
            // (text-only), не трогая шапку/кнопки карточки. Ядро ANR-фикса.
            val markdownColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            markdownState = ChatMarkdownRenderer.MarkdownRenderState()
            renderMarkdownBlocks(context, message.text, markdownColumn, isUser, index, host, markdownState)
            card.addView(markdownColumn, LinearLayout.LayoutParams(-1, -2))
            markdownHost = markdownColumn
            val actionsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, 0)
            }
            val copyBtn = Button(context).apply {
                text = "Копировать"
                textSize = 11f
                setTextColor(MarsTheme.TEXT_MUTED)
                background = null
                setPadding(0, 0, 0, 0)
                minimumHeight = 0
                minHeight = 0
                gravity = Gravity.START
                setOnClickListener { host.copyMessage(host.messageAt(index)?.text ?: message.text) }
            }
            actionsRow.addView(copyBtn)
            // «⋮» button: full message-actions sheet (copy/quote/share), WebUI parity.
            val moreBtn = MarsTheme.circleIconButton(
                context,
                R.drawable.ic_more_vert,
                "Действия с сообщением",
                bg = Color.TRANSPARENT,
                iconTint = MarsTheme.TEXT_MUTED,
                sizeDp = 30,
                strokeColor = 0
            ).apply {
                setOnClickListener { host.showMessageActions(host.messageAt(index) ?: message, index) }
            }
            actionsRow.addView(moreBtn, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(10) })
            if (!isUser) {
                // Speaker button: read Mars's answer aloud through the speech filter.
                val speakBtn = MarsTheme.circleIconButton(
                    context,
                    R.drawable.ic_volume,
                    "Озвучить сообщение",
                    bg = Color.TRANSPARENT,
                    iconTint = MarsTheme.TEXT_MUTED,
                    sizeDp = 30,
                    strokeColor = 0
                ).apply {
                    setOnClickListener { host.speakMessage(host.messageAt(index)?.text ?: message.text) }
                }
                actionsRow.addView(speakBtn, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(10) })
            }
            card.addView(actionsRow)
        }

        if (message.thoughts.isNotEmpty()) {
            val isStreaming = snapshot.streamingFlag
            val thinkingView = ThinkingCardPresenter.present(
                thoughts = message.thoughts,
                expanded = message.thoughtsExpanded,
                streaming = isStreaming
            )
            if (thinkingView.visible) {
                val thoughtBox = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = MarsTheme.thinkingCard(context)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                }
                val header = LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    setOnClickListener { host.toggleThoughts(index); host.rerenderTranscript() }
                }
                applyExpandableHeaderA11y(
                    header,
                    ChatA11yPolicy.expandableHeaderDescription(
                        thinkingView.expanded,
                        thinkingView.chipLabel,
                        thinkingView.statusLabel
                    ),
                    ChatA11yPolicy.stateDescription(thinkingView.expanded)
                )
                val chevron = ImageView(context).apply {
                    setImageDrawable(ContextCompat.getDrawable(
                        context,
                        if (thinkingView.chevronUp) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
                    ))
                    imageTintList = ColorStateList.valueOf(thinkingView.chipColor)
                }
                header.addView(chevron, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) })

                val chipTitle = TextView(context).apply {
                    text = thinkingView.chipLabel
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(thinkingView.chipColor)
                }
                header.addView(chipTitle, LinearLayout.LayoutParams(0, -2, 1f))

                if (thinkingView.statusLabel != null) {
                    val statusBadge = TextView(context).apply {
                        text = thinkingView.statusLabel
                        textSize = 10.5f
                        setTextColor(if (thinkingView.streaming) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_MUTED)
                        setPadding(dp(6), dp(2), dp(6), dp(2))
                        background = MarsTheme.pill(context, MarsTheme.SURFACE_0, strokeColor = 0)
                    }
                    header.addView(statusBadge)
                }
                thoughtBox.addView(header, LinearLayout.LayoutParams(-1, -2))

                if (thinkingView.expanded) {
                    val body = TextView(context).apply {
                        text = thinkingView.bodyText
                        setTextColor(thinkingView.bodyColor)
                        textSize = 12.5f
                        setTypeface(null, Typeface.ITALIC)
                        setLineSpacing(dp(2).toFloat(), 1f)
                        setPadding(dp(thinkingView.bodyIndentDp), dp(6), 0, dp(4))
                    }
                    thoughtBox.addView(body)

                    val copyBtn = Button(context).apply {
                        text = thinkingView.copyLabel
                        textSize = 11f
                        setTextColor(MarsTheme.TEXT_MUTED)
                        background = null
                        isAllCaps = false
                        setPadding(dp(thinkingView.bodyIndentDp), dp(2), 0, 0)
                        minimumHeight = 0
                        minHeight = 0
                        gravity = Gravity.START
                        setOnClickListener { host.copyMessage(thinkingView.copySource) }
                    }
                    thoughtBox.addView(copyBtn)
                }
                card.addView(thoughtBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
        }

        // A16: набор вызовов инструментов одного сообщения сворачивается в одну
        // строку-сводку («N инструментов · Готово» с шевроном). Режим активности
        // даёт лишь НАЧАЛЬНОЕ состояние; ручной тап переопределяет его и держится
        // до смены сессии. При COLLAPSED карточки ниже не создаются вообще —
        // именно поэтому сначала считается модель, а цикл получает guard.
        val toolGroup = ToolGroupPresenter.model(
            tools = message.tools,
            mode = host.activityMode,
            manualExpanded = host.isToolGroupExpanded(index),
        )
        if (toolGroup.showsSummary) {
            card.addView(
                buildToolGroupSummary(context, index, toolGroup, host),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
            )
        }

        message.tools.forEachIndexed { toolIndex, tool ->
            // Свёрнутая группа: дочерние карточки не инфлейтятся впустую.
            if (!toolGroup.showsCards) return@forEachIndexed
            val toolView = ToolCardPresenter.present(tool)
            val toolBox = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = MarsTheme.toolCallCard(context)
                setPadding(dp(10), dp(6), dp(8), dp(6))
            }
            val header = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setOnClickListener { host.toggleTool(index, toolIndex); host.rerenderTranscript() }
            }
            applyExpandableHeaderA11y(
                header,
                ChatA11yPolicy.expandableHeaderDescription(toolView.expanded, toolView.kindLabel, toolView.toolName, toolView.stateLabel),
                ChatA11yPolicy.stateDescription(toolView.expanded)
            )
            val toolIconRes = when (toolView.kind) {
                ToolKind.TERMINAL -> R.drawable.ic_chip
                ToolKind.FILESYSTEM -> R.drawable.ic_plan
                ToolKind.WEB -> R.drawable.ic_search
                ToolKind.MCP -> R.drawable.ic_refresh
                ToolKind.GENERIC -> R.drawable.ic_status
            }
            val toolIcon = ImageView(context).apply {
                setImageDrawable(ContextCompat.getDrawable(context, toolIconRes))
                imageTintList = ColorStateList.valueOf(MarsTheme.ACCENT_TEXT)
            }
            header.addView(toolIcon, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) })

            val titleCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val kindBadge = TextView(context).apply {
                text = toolView.kindLabel
                textSize = 9.5f
                setTextColor(MarsTheme.TEXT_MUTED)
            }
            titleCol.addView(kindBadge)
            val toolTitle = TextView(context).apply {
                text = toolView.toolName
                textSize = 11.5f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(toolView.titleColor)
            }
            titleCol.addView(toolTitle)
            header.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))

            val stateBadge = TextView(context).apply {
                text = toolView.stateLabel
                textSize = 9.5f
                setTypeface(Typeface.MONOSPACE)
                val statusEnum = if (toolView.state == ToolState.DONE) MarsGradients.Status.DONE else MarsGradients.Status.RUNNING
                MarsTheme.applyStatusBadge(this, statusEnum)
            }
            header.addView(stateBadge)

            val chevron = ImageView(context).apply {
                setImageDrawable(ContextCompat.getDrawable(
                    context,
                    if (toolView.chevronUp) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
                ))
                imageTintList = ColorStateList.valueOf(MarsTheme.TEXT_MUTED)
            }
            header.addView(chevron, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(6) })

            val copyBtn = MarsTheme.circleIconButton(
                context,
                R.drawable.ic_copy,
                "Копировать инструмент",
                bg = Color.TRANSPARENT,
                iconTint = MarsTheme.TEXT_MUTED,
                sizeDp = 28,
                strokeColor = 0
            ).apply {
                setOnClickListener {
                    host.copyMessage(toolView.copySource)
                    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                }
            }
            header.addView(copyBtn, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(4) })
            toolBox.addView(header, LinearLayout.LayoutParams(-1, -2))

            if (!toolView.expanded && toolView.detailLabel != null) {
                toolBox.addView(TextView(context).apply {
                    text = toolView.detailLabel
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    setTextColor(MarsTheme.TEXT_MUTED)
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(22), dp(4), 0, 0)
                })
            }

            if (toolView.expanded) {
                toolView.argsSection?.let { section ->
                    toolBox.addView(TextView(context).apply {
                        text = section.label
                        textSize = 10.5f
                        setTextColor(MarsTheme.TEXT_MUTED)
                        setTypeface(null, Typeface.BOLD)
                        setPadding(0, dp(6), 0, dp(2))
                    })
                    val argsScroll = HorizontalScrollView(context).apply {
                        addView(TextView(context).apply {
                            text = section.visibleText
                            textSize = 11f
                            typeface = Typeface.MONOSPACE
                            setTextColor(MarsTheme.TEXT)
                            setTextIsSelectable(true)
                            setPadding(dp(8), dp(6), dp(8), dp(6))
                            background = MarsTheme.card(context, MarsTheme.CODE_BG, radiusDp = 8, strokeColor = MarsTheme.CODE_BORDER)
                        })
                    }
                    toolBox.addView(argsScroll)
                }

                toolView.resultSection?.let { section ->
                    toolBox.addView(TextView(context).apply {
                        text = section.label
                        textSize = 10.5f
                        setTextColor(MarsTheme.TEXT_MUTED)
                        setTypeface(null, Typeface.BOLD)
                        setPadding(0, dp(6), 0, dp(2))
                    })
                    val resScroll = HorizontalScrollView(context).apply {
                        addView(TextView(context).apply {
                            text = section.visibleText
                            textSize = 11f
                            typeface = Typeface.MONOSPACE
                            setTextColor(MarsTheme.TEXT)
                            setTextIsSelectable(true)
                            setPadding(dp(8), dp(6), dp(8), dp(6))
                            background = MarsTheme.card(context, MarsTheme.CODE_BG, radiusDp = 8, strokeColor = MarsTheme.CODE_BORDER)
                        })
                    }
                    toolBox.addView(resScroll)
                }
            }
            card.addView(toolBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }

        // Доска живых статусов субагентов (волна C, фича B): дети текущего хода
        // читаются ПРЯМО на родительской карточке — без открытия дочернего
        // экрана. Активные — с пульсирующим индикатором, терминальные — со
        // знаком результата (бейдж статуса); у Failed — причина, если сервер
        // её прислал. Данные — редьюсер SubagentStatusPolicy в ChatUiState.
        if (message.subagents.isNotEmpty()) {
            val board = SubagentBoardPresenter.present(message.subagents)
            if (board.visible) {
                val boardBox = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = MarsTheme.toolCallCard(context)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                }
                val boardHeader = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
                if (board.activeCount > 0) {
                    val liveDot = ImageView(context).apply {
                        setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_status))
                        imageTintList = ColorStateList.valueOf(MarsGradients.Status.RUNNING.badge.dot)
                        contentDescription = null
                    }
                    boardHeader.addView(liveDot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(6) })
                }
                boardHeader.addView(TextView(context).apply {
                    text = board.headerLabel
                    textSize = 10.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(MarsTheme.TEXT_MUTED)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                boardBox.addView(boardHeader)
                board.rows.forEach { row ->
                    val rowLine = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, dp(4), 0, 0)
                    }
                    val titleLine = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
                    titleLine.addView(TextView(context).apply {
                        text = row.title
                        textSize = 11.5f
                        setTextColor(if (row.active) MarsTheme.TEXT_STRONG else MarsTheme.TEXT)
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    val statusBadge = TextView(context).apply {
                        text = row.statusLabel
                        textSize = 9.5f
                        val gradientStatus = MarsGradients.Status.valueOf(row.badge.name)
                        setTextColor(gradientStatus.badge.text)
                        background = MarsTheme.statusBadge(context, gradientStatus)
                        setPadding(dp(8), dp(3), dp(8), dp(3))
                    }
                    titleLine.addView(statusBadge)
                    rowLine.addView(titleLine)
                    val details = mutableListOf<String>()
                    row.detail?.let { details += it }
                    row.failureReason?.let { details += "причина: $it" }
                    if (details.isNotEmpty()) {
                        rowLine.addView(TextView(context).apply {
                            text = details.joinToString(" · ")
                            textSize = 10f
                            setTextColor(if (row.failureReason != null) MarsGradients.Status.ERROR.badge.text else MarsTheme.TEXT_MUTED)
                            setPadding(0, dp(1), 0, 0)
                        })
                    }
                    boardBox.addView(rowLine)
                }
                card.addView(boardBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
        }

        if (message.text.isNotBlank()) {
            // Long-press anywhere on the bubble opens the message-actions sheet.
            card.setOnLongClickListener { view ->
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                // Резолвим в момент клика: захваченная при сборке копия устаревает за кадры стрима.
                host.showMessageActions(host.messageAt(index) ?: message, index)
                true
            }
        }
        // Тег = индекс сообщения: по нему поиск находит карточку для подсветки/скролла.
        card.tag = index
        return CardViews(card, markdownHost, markdownState)
    }

    /**
     * A16: строка-сводка свёрнутого набора вызовов инструментов. Одна строка вместо
     * простыни карточек: иконка, «N инструментов · Готово» и шеврон. Тап переключает
     * ручное переопределение группы ([ChatUiState.toggleToolGroup]) и перерисовывает
     * транскрипт. Иконки — монохромные векторы темы, счётчик ASCII.
     */
    private fun buildToolGroupSummary(
        context: Context,
        messageIndex: Int,
        group: ToolGroupModel,
        host: Host,
    ): View {
        fun dp(value: Int) = MarsTheme.dp(context, value)
        // Тот же псевдоним, что в [build]: семантика заголовка остаётся за Activity-хелпером.
        val applyExpandableHeaderA11y = host::a11yHeader
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = MarsTheme.toolCallCard(context)
            setPadding(dp(10), dp(6), dp(8), dp(6))
            isClickable = true
        }
        val iconRes = when (group.iconToolName?.let { ToolCardPresenter.present(ToolExecution("", it, "")).kind }) {
            ToolKind.TERMINAL -> R.drawable.ic_chip
            ToolKind.FILESYSTEM -> R.drawable.ic_plan
            ToolKind.WEB -> R.drawable.ic_search
            ToolKind.MCP -> R.drawable.ic_refresh
            else -> R.drawable.ic_status
        }
        box.addView(ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, iconRes))
            imageTintList = ColorStateList.valueOf(MarsTheme.ACCENT_TEXT)
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) })

        box.addView(TextView(context).apply {
            text = group.summary
            textSize = 11.5f
            setTextColor(MarsTheme.TEXT_STRONG)
            typeface = Typeface.DEFAULT
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))

        box.addView(ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(
                context,
                if (group.chevronExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
            ))
            imageTintList = ColorStateList.valueOf(MarsTheme.TEXT_MUTED)
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(6) })

        applyExpandableHeaderA11y(
            box,
            group.summaryDescription,
            if (group.chevronExpanded) "развёрнуто" else "свёрнуто"
        )
        box.setOnClickListener {
            host.toggleToolGroup(messageIndex, group.chevronExpanded)
            host.rerenderTranscript()
        }
        return box
    }


    /** Markdown-секция пузыря: блоки парсит [MarkdownFormatter], рисует [ChatMarkdownRenderer]. */
    fun renderMarkdownBlocks(
        context: Context,
        value: String,
        parent: LinearLayout,
        user: Boolean,
        messageIndex: Int,
        host: Host,
        state: ChatMarkdownRenderer.MarkdownRenderState,
    ) {
        ChatMarkdownRenderer.render(
            context = context,
            blocks = MarkdownFormatter.parseBlocks(value),
            parent = parent,
            user = user,
            messageIndex = messageIndex,
            state = state,
            isCodeBlockExpanded = host::isCodeBlockExpanded,
            toggleCodeBlock = host::toggleCodeBlock,
            copyMessage = host::copyMessage,
            openImage = host::openImage,
            openUrl = host::openUrl,
        )
    }
}
