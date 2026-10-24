package ru.mars.tv

import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import ru.mars.network.model.SessionDetail
import ru.mars.tv.chat.TvHistoryReloadPlan
import ru.mars.tv.chat.TvHistoryReloadPolicy
import ru.mars.tv.chat.TvMarkdownBlocks
import ru.mars.tv.chat.TvMarkdownRenderer
import ru.mars.tv.chat.TvToolGroupPolicy
import ru.mars.tv.chat.TvTranscriptRetentionPolicy

/**
 * Секция «Лента» экрана ТВ-чата.
 *
 * Вынесено из [TvChatActivity] (декомпозиция хоста, W2).
 *
 * Пузыри (markdown/plain), строки активности, cap-ы и подрезка
 * ([TvTranscriptRetentionPolicy]), маркер «ранее скрыто» с догрузкой страницы и
 * инкрементальная перезагрузка истории ([TvHistoryReloadPolicy]).
 *
 * Форма переноса: extension-функции на Activity — состояние (View-поля, job-ы,
 * биндинг сессии) остаётся в хосте, тела перенесены дословно. Члены хоста, которые
 * читают секции, подняты `private` -> `internal`.
 */

/**
 * WebUI-parity chat bubble: user side gets the deep-navy bubble
 * (USER_BUBBLE + USER_BORDER), Mars side gets the bot card
 * (BOT_CARD + BOT_BORDER). Both are focusable — under D-pad focus the
 * bg_bubble_* StateListDrawable cross-fades to a ruby border.
 *
 * Ответ Марса из ИСТОРИИ с таблицей/чек-листом рендерится «богато»
 * ([TvMarkdownRenderer.richBubble]) вместо плоского текста.
 */
internal fun TvChatActivity.append(author: String, text: String, isUser: Boolean): View =
    buildBubble(author, text, isUser).also { addTranscriptView(it) }

/** Единая фабрика пузыря (лента И внеэкранная сборка истории): вид, но не добавление. */
internal fun TvChatActivity.buildBubble(author: String, text: String, isUser: Boolean): View =
    if (!isUser && text.isNotEmpty() && TvMarkdownBlocks.isRich(text)) {
        TvMarkdownRenderer.richBubble(this, text, { value -> dp(value) })
    } else {
        plainBubble(author, text, isUser)
    }

/** Плоский текстовый пузырь (прежний рендер): пользователь, пустой placeholder, обычный ответ. */
internal fun TvChatActivity.plainBubble(author: String, text: String, isUser: Boolean): TextView = TextView(this).apply {
    this.text = "$author: $text"
    // Кегль — ТОЛЬКО из шкалы (tv_text_*). Единица указывается явно: свойство
    // `textSize` трактует число как SP, а getDimension отдаёт PX — на xhdpi
    // «16dp» превратилось бы в 32sp и лента разъехалась бы.
    setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.tv_text_body))
    setTextColor(ContextCompat.getColor(context, R.color.mars_text))
    val padH = resources.getDimensionPixelSize(R.dimen.tv_chat_bubble_pad_h)
    val padV = resources.getDimensionPixelSize(R.dimen.tv_chat_bubble_pad_v)
    setPadding(padH, padV, padH, padV)
    setBackgroundResource(if (isUser) R.drawable.bg_bubble_user else R.drawable.bg_bubble_bot)
    isFocusable = true
    TvFocusLift.attach(this, reducedMotion)
}

/** Общие параметры пузыря в ленте (отступ сверху). */
internal fun TvChatActivity.bubbleParams(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = resources.getDimensionPixelSize(R.dimen.tv_chat_bubble_gap)
    }

/**
 * Добавление в ленту с общим cap'ом ([TvTranscriptRetentionPolicy.trimPlan]):
 * контейнер никогда не растёт бесконечно — самые старые записи подрезаются.
 */
internal fun TvChatActivity.addTranscriptView(view: View, params: LinearLayout.LayoutParams = bubbleParams()) {
    messages.addView(view, params)
    enforceTranscriptCap()
}

/**
 * Режим «Всё»: мысли/инструменты дополнительно дублируются в ленту приглушёнными
 * строками на плашке «размышлений» (bg_thinking) — ТВ-эквивалент мобильного
 * раскрытого thoughts/tools блока.
 *
 * Строки capped ([TvTranscriptRetentionPolicy.ACTIVITY_LINE_CAP]): долгий ход в
 * режиме «Всё» — это тысячи событий, бесконечный рост View ронял TV-приставку.
 * Самые старые строки снимаются (со снятием колбэков), счётчик уходит в маркер
 * «…ранее скрыто».
 */
internal fun TvChatActivity.appendActivityLine(line: String) {
    val view = TextView(this).apply {
        text = line
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.tv_text_label))
        setTextColor(ContextCompat.getColor(context, R.color.mars_muted_on_glass))
        setPadding(
            resources.getDimensionPixelSize(R.dimen.tv_gap_l),
            resources.getDimensionPixelSize(R.dimen.tv_gap_s),
            resources.getDimensionPixelSize(R.dimen.tv_gap_l),
            resources.getDimensionPixelSize(R.dimen.tv_gap_s),
        )
        setBackgroundResource(R.drawable.bg_thinking)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        isFocusable = true
        TvFocusLift.attach(this, reducedMotion)
        tag = TvChatActivity.TAG_ACTIVITY_LINE
    }
    activityLineViews.addLast(view)
    addTranscriptView(view, LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = resources.getDimensionPixelSize(R.dimen.tv_gap_s)
    })
    enforceActivityLineCap()
}

// --- устойчивость транскрипта: caps, подрезка, внеэкранная подмена истории ------------

/** Cap строк активности: снимаем самые старые, пока не вписались. */
internal fun TvChatActivity.enforceActivityLineCap() {
    val overflow = TvTranscriptRetentionPolicy.activityOverflow(activityLineViews.size)
    if (overflow <= 0) return
    repeat(overflow) {
        val oldest = activityLineViews.removeFirstOrNull() ?: return
        hiddenActivity++
        detachTranscriptView(oldest)
        messages.removeView(oldest)
    }
    updateHiddenMarker()
}

/** Общий cap ленты: план подрезки (чистый) + снятие самых старых View с колбэками. */
internal fun TvChatActivity.enforceTranscriptCap() {
    val count = messages.childCount
    if (count <= TvTranscriptRetentionPolicy.MAX_TRANSCRIPT_VIEWS) return
    val kinds = List(count) { kindOf(messages.getChildAt(it)) }
    val plan = TvTranscriptRetentionPolicy.trimPlan(kinds)
    if (plan.isEmpty()) return
    // Индексы плана валидны до первого удаления — снимаем с конца.
    plan.asReversed().forEach { index ->
        val victim = messages.getChildAt(index) ?: return@forEach
        when (kindOf(victim)) {
            TvTranscriptRetentionPolicy.Kind.ACTIVITY_LINE -> {
                hiddenActivity++
                activityLineViews.remove(victim)
            }
            TvTranscriptRetentionPolicy.Kind.PROTECTED -> Unit // план их не содержит
            TvTranscriptRetentionPolicy.Kind.MESSAGE -> hiddenMessages++
        }
        detachTranscriptView(victim)
        messages.removeViewAt(index)
    }
    updateHiddenMarker()
}

/**
 * Категория ребёнка ленты для чистой политики: интерактивные карточки, живой пузырь
 * и маркер скрытого защищены; строки активности помечены тегом; прочее — сообщения.
 */
internal fun TvChatActivity.kindOf(child: View): TvTranscriptRetentionPolicy.Kind = when {
    child is TvInteractiveCard -> TvTranscriptRetentionPolicy.Kind.PROTECTED
    child === hiddenMarkerView -> TvTranscriptRetentionPolicy.Kind.PROTECTED
    child === turnNoticeView -> TvTranscriptRetentionPolicy.Kind.PROTECTED
    child === liveAnswer -> TvTranscriptRetentionPolicy.Kind.PROTECTED
    child.tag == TvChatActivity.TAG_ACTIVITY_LINE -> TvTranscriptRetentionPolicy.Kind.ACTIVITY_LINE
    else -> TvTranscriptRetentionPolicy.Kind.MESSAGE
}

/**
 * Снятие колбэков у удаляемой View (защита от утечек): фокус уводится в композер,
 * анимации TvFocusLift и focus-листенер гасятся — удалённая View не держит ссылок
 * на окно и не получает событий.
 */
internal fun TvChatActivity.detachTranscriptView(view: View) {
    if (view.hasFocus()) input.requestFocus()
    view.animate().cancel()
    view.onFocusChangeListener = null
    view.tag = null
}

/** Полная очистка ленты («Новый чат»): View, строки активности, счётчики, live-состояние. */
internal fun TvChatActivity.clearTranscript() {
    for (index in 0 until messages.childCount) detachTranscriptView(messages.getChildAt(index))
    // removeAllViewsInLayout: снятие без requestLayout/layout на каждого ребёнка —
    // именно этот проход по сотням View давал ANR на длинной истории.
    messages.removeAllViewsInLayout()
    messages.requestLayout()
    activityLineViews.clear()
    hiddenMessages = 0
    hiddenActivity = 0
    hiddenMarkerView = null
    renderedRows = emptyList()
    // Новая лента — новое окно рендера: «Показать ещё» не должно наследовать
    // разросшуюся страницу прежнего диалога.
    historyPageRows = TvTranscriptRetentionPolicy.MAX_RENDERED_MESSAGES
    fullHistoryRows = emptyList()
    clearContextStatus()
    resetLiveAnswerState()
}

/**
 * Подмена транскрипта историей — инкрементально, без полного сноса ленты.
 *
 * План считает чистая [TvHistoryReloadPolicy]: неизменившиеся пузыри
 * переиспользуются, доинфлейтится только хвост ([TvHistoryReloadPlan.Append])
 * или изменившаяся середина ([TvHistoryReloadPlan.Patch]). Прежний путь
 * (`messages.removeAllViews()` + инфлейт ВСЕЙ истории) на длинном диалоге давал
 * ANR: сотни addView и measure/layout в одном кадре на главном потоке.
 * Полная пересборка осталась fallback'ом — когда общего префикса/суффикса нет
 * (сменился диалог) или в ленте есть не-исторические View (карточки, строки
 * активности, живой пузырь): их индексы сдвинули бы патч.
 *
 * Длинная история режется до последних
 * [TvTranscriptRetentionPolicy.MAX_RENDERED_MESSAGES] сообщений с маркером
 * «…ранее скрыто» (чистое решение — [TvTranscriptRetentionPolicy.historySlice]).
 */
internal fun TvChatActivity.renderTranscriptRows(rows: List<TvSessionBrowserPolicy.TranscriptRow>) {
    fullHistoryRows = rows
    val slice = TvTranscriptRetentionPolicy.historySlice(rows.size, historyPageRows)
    val target = rows.drop(slice.firstRowIndex)
    val plan = TvHistoryReloadPolicy.plan(renderedRows, target)
    if (plan is TvHistoryReloadPlan.NoChange && slice.hiddenMessages == hiddenMessages) return

    // Маркер на время операции снимается: индексы строк совпадают с индексами детей.
    hiddenMarkerView?.let { marker -> if (marker.parent === messages) messages.removeView(marker) }
    hiddenMarkerView = null

    val effective = if (plan is TvHistoryReloadPlan.FullRebuild || !historyOnlyTranscript()) {
        TvHistoryReloadPlan.FullRebuild
    } else {
        plan
    }
    when (effective) {
        is TvHistoryReloadPlan.NoChange -> Unit
        is TvHistoryReloadPlan.Append -> appendHistoryRows(target, effective.fromIndex)
        is TvHistoryReloadPlan.Patch ->
            patchHistoryRows(target, effective.rebuildFrom, effective.rebuildTo)
        is TvHistoryReloadPlan.FullRebuild -> rebuildTranscript(target)
    }
    activityLineViews.clear()
    renderedRows = target
    hiddenMessages = slice.hiddenMessages
    hiddenActivity = 0
    updateHiddenMarker()
    // Баннер хода живёт в ленте первым ребёнком: полная пересборка снимает его
    // вместе с пузырями, поэтому состояние восстанавливается из [currentNotice].
    applyTurnNotice()
}

/**
 * В ленте ровно пузыри истории (плюс маркер скрытого): только тогда индексы
 * строк и детей совпадают и точечный патч безопасен.
 */
internal fun TvChatActivity.historyOnlyTranscript(): Boolean {
    val markerAttached = hiddenMarkerView?.let { it.parent === messages } == true
    if (messages.childCount != renderedRows.size + if (markerAttached) 1 else 0) return false
    val start = if (markerAttached) 1 else 0
    for (index in start until messages.childCount) {
        if (messages.getChildAt(index).tag != TvChatActivity.TAG_HISTORY_ROW) return false
    }
    return true
}

/** Хвост истории: доинфлейт только новых строк, существующие View не трогаются. */
internal fun TvChatActivity.appendHistoryRows(rows: List<TvSessionBrowserPolicy.TranscriptRow>, fromIndex: Int) {
    for (index in fromIndex until rows.size) {
        messages.addView(buildHistoryRow(rows[index]), bubbleParams())
    }
}

/**
 * Изменившаяся середина: снимаются и собираются заново ТОЛЬКО строки
 * [rebuildFrom]..[rebuildTo); общий префикс и суффикс переиспользуются как есть.
 */
internal fun TvChatActivity.patchHistoryRows(
    rows: List<TvSessionBrowserPolicy.TranscriptRow>,
    rebuildFrom: Int,
    rebuildTo: Int,
) {
    if (rebuildTo <= rebuildFrom) return
    for (index in rebuildTo - 1 downTo rebuildFrom) {
        val victim = messages.getChildAt(index) ?: continue
        detachTranscriptView(victim)
        messages.removeViewAt(index)
    }
    rows.subList(rebuildFrom, rebuildTo).forEachIndexed { offset, row ->
        messages.addView(buildHistoryRow(row), rebuildFrom + offset, bubbleParams())
    }
}

/**
 * Fallback: пузыри собираются в offscreen-контейнере (не присоединён к окну —
 * addView не тянет measure/layout на каждую строку), затем подмена одним проходом.
 */
internal fun TvChatActivity.rebuildTranscript(rows: List<TvSessionBrowserPolicy.TranscriptRow>) {
    val offscreen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    rows.forEach { row -> offscreen.addView(buildHistoryRow(row), bubbleParams()) }
    swapTranscript(offscreen)
}

/** Пузырь строки истории с меткой переиспользования ([TvChatActivity.TAG_HISTORY_ROW]). */
internal fun TvChatActivity.buildHistoryRow(row: TvSessionBrowserPolicy.TranscriptRow): View {
    val view = if (row.kind == TvSessionBrowserPolicy.TranscriptKind.TOOL_GROUP) {
        buildToolGroupRow(row)
    } else {
        buildBubble(row.author, row.text, row.isUser)
    }
    view.tag = TvChatActivity.TAG_HISTORY_ROW
    return view
}

/**
 * Свёрнутая карточка серии вызовов инструментов: одна плашка вместо дюжины
 * «Марс»-пузырей с сырым выводом. Сводка («4 инструмента · Готово») — из чистой
 * [TvToolGroupPolicy], вторая строка — имена вызовов. Не фокусируется: это
 * информационная строка, обход D-pad она не удлиняет.
 */
internal fun TvChatActivity.buildToolGroupRow(row: TvSessionBrowserPolicy.TranscriptRow): View =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_focus_card)
        isFocusable = false
        setPadding(
            resources.getDimensionPixelSize(R.dimen.tv_gap_xl),
            resources.getDimensionPixelSize(R.dimen.tv_gap_m),
            resources.getDimensionPixelSize(R.dimen.tv_gap_xl),
            resources.getDimensionPixelSize(R.dimen.tv_gap_m),
        )
        addView(TextView(this@buildToolGroupRow).apply {
            text = row.text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.tv_text_label))
            setTextColor(ContextCompat.getColor(context, R.color.mars_status_online))
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        })
        TvToolGroupPolicy.namesLine(row.toolNames).takeIf { it.isNotEmpty() }?.let { names ->
            addView(TextView(this@buildToolGroupRow).apply {
                text = names
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.tv_text_micro))
                setTextColor(ContextCompat.getColor(context, R.color.mars_muted_on_glass))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, resources.getDimensionPixelSize(R.dimen.tv_gap_xs), 0, 0)
            })
        }
    }

/** Атомарная подмена содержимого ленты: фокус сохраняется, пустого кадра нет. */
internal fun TvChatActivity.swapTranscript(prepared: LinearLayout) {
    val focusedIndex = (0 until messages.childCount)
        .firstOrNull { messages.getChildAt(it).hasFocus() }
    for (index in 0 until messages.childCount) detachTranscriptView(messages.getChildAt(index))
    messages.removeAllViewsInLayout()
    while (prepared.childCount > 0) {
        val child = prepared.getChildAt(0)
        prepared.removeViewAt(0)
        messages.addView(child, child.layoutParams)
    }
    messages.requestLayout()
    if (focusedIndex != null) {
        val target = messages.getChildAt(minOf(focusedIndex, messages.childCount - 1))
        if (target != null && target.isFocusable) target.requestFocus() else input.requestFocus()
    }
}

/**
 * Маркер «…ранее скрыто»: приглушённая строка в начале ленты. Когда сверху есть
 * что догрузить, он становится ФОКУСИРУЕМЫМ действием — иначе голова длинного
 * диалога (>[TvTranscriptRetentionPolicy.MAX_RENDERED_MESSAGES]) была недостижима
 * пультом вовсе.
 */
internal fun TvChatActivity.buildHiddenMarker(text: String?): TextView = TextView(this).apply {
    this.text = text.orEmpty()
    setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.tv_text_micro))
    setTextColor(ContextCompat.getColor(context, R.color.mars_muted_on_glass))
    setPadding(
        resources.getDimensionPixelSize(R.dimen.tv_gap_xl),
        resources.getDimensionPixelSize(R.dimen.tv_chat_bubble_pad_v),
        resources.getDimensionPixelSize(R.dimen.tv_gap_xl),
        resources.getDimensionPixelSize(R.dimen.tv_gap_xs),
    )
    isFocusable = false
    tag = TvChatActivity.TAG_HIDDEN_MARKER
}

/**
 * Нажатие на маркер: окно рендера растёт на страницу
 * ([TvTranscriptRetentionPolicy.nextWindow]) и история перерисовывается. Фокус
 * остаётся на маркере, пока сверху ещё есть скрытое, иначе уходит на первую
 * показанную строку — пульт не «проваливается» в никуда.
 */
internal fun TvChatActivity.loadMoreHistory() {
    if (fullHistoryRows.isEmpty()) return
    val next = TvTranscriptRetentionPolicy.nextWindow(historyPageRows, fullHistoryRows.size)
    if (next <= historyPageRows) return
    historyPageRows = next
    renderTranscriptRows(fullHistoryRows)
    val marker = hiddenMarkerView
    if (marker != null && TvTranscriptRetentionPolicy.canLoadMore(hiddenMessages)) {
        marker.requestFocus()
    } else {
        val firstRow = (0 until messages.childCount)
            .map { messages.getChildAt(it) }
            .firstOrNull { it.tag == TvChatActivity.TAG_HISTORY_ROW && it.isFocusable }
        firstRow?.requestFocus() ?: input.requestFocus()
    }
}

/** Перерисовка/снятие маркера по счётчикам скрытого. */
internal fun TvChatActivity.updateHiddenMarker() {
    val text = TvTranscriptRetentionPolicy.pagerText(hiddenMessages, hiddenActivity)
    if (text == null) {
        hiddenMarkerView?.let { marker ->
            detachTranscriptView(marker)
            messages.removeView(marker)
        }
        hiddenMarkerView = null
        return
    }
    val loadMore = TvTranscriptRetentionPolicy.canLoadMore(hiddenMessages)
    val existing = hiddenMarkerView
    if (existing != null && messages.indexOfChild(existing) >= 0) {
        existing.text = text
        existing.isFocusable = loadMore
        existing.setOnClickListener(if (loadMore) View.OnClickListener { loadMoreHistory() } else null)
        if (loadMore) TvFocusLift.attach(existing, reducedMotion)
        return
    }
    val marker = buildHiddenMarker(text)
    marker.isFocusable = loadMore
    if (loadMore) {
        marker.setOnClickListener { loadMoreHistory() }
        TvFocusLift.attach(marker, reducedMotion)
    }
    hiddenMarkerView = marker
    // Баннер хода (если он есть) остаётся самым верхним: маркер идёт за ним.
    val index = if (turnNoticeView?.parent === messages) 1 else 0
    messages.addView(marker, index, LinearLayout.LayoutParams(-1, -2))
    // Маркер добавлен — общий cap проверяется заново (рекурсия ограничена: повторный
    // проход маркер уже находит и только обновляет текст).
    enforceTranscriptCap()
}

/**
 * Replaces the transcript with the server's settled history (live `GET /api/session`).
 * Сборка — вне экранного контейнера, подмена одним проходом ([renderTranscriptRows]):
 * длинная история больше не сносит/не инфлейтит ленту по одному view на главный поток.
 */
internal fun TvChatActivity.renderSessionDetailHistory(detail: SessionDetail?) {
    detail ?: return
    if (interactiveReady) interactive.dismissAll()
    resetLiveAnswerState()
    // Бейдж контекста принадлежит прежнему ходу — в новом диалоге он не актуален.
    clearContextStatus()
    renderTranscriptRows(TvSessionBrowserPolicy.transcript(detail))
    pinAndScrollToBottom()
}
