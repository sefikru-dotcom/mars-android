package ru.mars.mobile

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Рендер транскрипта: инкрементальные снимки карточек, патч/полная сборка, карточки.
 * 
 * Вынесено из [ChatActivity] (W2) как internal-расширения; [CardEntry] переехал сюда же
 * как внутренний класс пакета. Решения о том, что перерисовывать, по-прежнему принимают
 * чистые политики ([IncrementalTranscriptPolicy], [StreamRenderThrottle], [FrameVersionGate]).
 */

/**
 * Одна нарисованная карточка: children контейнера `messages` ↔ список карточек 1:1 по индексу.
 */
internal class CardEntry(
    val wrapper: LinearLayout,
    var snapshot: CardSnapshot,
    /** Секция markdown внутри пузыря; null, пока текст пуст (секции ещё нет). */
    var markdownHost: LinearLayout?,
    /** Состояние инкрементальной отрисовки секции (переиспользование префикса). */
    val markdownState: ChatMarkdownRenderer.MarkdownRenderState? = null,
)

/**
 * Отрисовка транскрипта: ИНКРЕМЕНТАЛЬНАЯ (ANR-фикс волны 1310). Контейнер больше не
 * сносится целиком на каждый кадр — чистая IncrementalTranscriptPolicy сравнивает
 * снимки карточек ([CardSnapshotPolicy]) и выбирает план:
 * - NoChange: ничего не рисуем (политика скролла всё равно применяется — pin-to-bottom жив);
 * - FullRebuild: сессия сменилась / список укоротился / эпоха хрома — разовая полная сборка;
 * - Patch: пересобираются ТОЛЬКО «грязные» карточки и дозапускается хвост. На кадре стрима
 *   грязным обычно является ровно один пузырь (TEXT_ONLY) — его markdown парсится в фоне.
 *
 * Любой состоявшийся рендер закрывает окно троттлинга, гасит отложенный trailing-flush
 * и инвалидирует фоновые кадры: этот рендер синхронный и более свежий, чем они.
 */
internal fun ChatActivity.renderMessages() {
    if (!isMessagesReady()) return
    // Take the auto-scroll decision from the PRE-render viewport (synchronously, before any
    // mutation): a user scrolled up reading history stays put while SSE tokens keep coming;
    // only a pinned viewport follows the stream to the bottom.
    // Дистанция до низа считается один раз: два вызова = два обхода дерева на кадр.
    val distance = distanceFromBottomPx()
    autoScroll.onViewportDistanceFromBottom(distance)
    scrollToBottomPolicy.onViewportDistanceFromBottom(distance)

    streamFlushJob?.cancel()
    streamFlushJob = null
    streamThrottle.onRendered(SystemClock.elapsedRealtime())
    markdownParseJob?.cancel()
    markdownParseJob = null
    frameGate.invalidateInFlight()

    val current = currentSnapshots()
    when (
        val plan = messageListPresenter.plan(
            previous = renderedSnapshots,
            current = current,
            streaming = uiState.status == ChatStatus.TYPING,
        )
    ) {
        TranscriptPlan.FullRebuild -> rebuildTranscript(current)
        is TranscriptPlan.Patch -> {
            // Fast path: только текст стримящегося пузыря — тяжёлый парсинг уходит в фон.
            if (tryAsyncTextOnlyUpdate(plan, current)) return
            applyTranscriptPatch(plan, current)
        }
        TranscriptPlan.NoChange -> Unit
    }
    renderedSnapshots = current
    finishTranscriptRender()
}

/**
 * Перерисовка кадров стрима: НЕ на каждый токен (это и был ANR), а через
 * StreamRenderThrottle — передний кадр сразу + ровно один trailing-flush на окно (~20 к/с).
 * Нетипинговые состояния (Done/Error → RECEIVED/ERROR, интерактив → IDLE) рисуются
 * немедленно: хвост терминального события не должен теряться. Вызывается из двух точек:
 * первичный collect и RecoveryListener.onStreamEvent (attachToStream + Last-Event-ID).
 */

/**
 * Перерисовка кадров стрима: НЕ на каждый токен (это и был ANR), а через
 * StreamRenderThrottle — передний кадр сразу + ровно один trailing-flush на окно (~20 к/с).
 * Нетипинговые состояния (Done/Error → RECEIVED/ERROR, интерактив → IDLE) рисуются
 * немедленно: хвост терминального события не должен теряться. Вызывается из двух точек:
 * первичный collect и RecoveryListener.onStreamEvent (attachToStream + Last-Event-ID).
 */
internal fun ChatActivity.scheduleStreamRender() {
    if (!isMessagesReady()) return
    // Локальный буфер прерванного хода: последний видимый текст ответа переживает смерть
    // процесса/поворот. Троттлинг и apply() — внутри store, диск не участвует в кадре.
    interruptedTurn.rememberFrame(
        sessionId = currentSessionId,
        streamId = currentStreamId,
        streaming = uiState.status == ChatStatus.TYPING,
        text = uiState.lastAssistantText(),
    )
    if (uiState.status != ChatStatus.TYPING) {
        renderMessages()
        return
    }
    when (val decision = streamThrottle.onStreamEvent(SystemClock.elapsedRealtime())) {
        StreamRenderDecision.RenderNow -> renderMessages()
        is StreamRenderDecision.ScheduleAfter -> {
            streamFlushJob?.cancel()
            streamFlushJob = lifecycleScope.launch {
                delay(decision.delayMs)
                streamFlushJob = null
                renderMessages() // сам сбрасывает окно (onRendered) и гасит этот job
            }
        }
        StreamRenderDecision.Ignore -> Unit
    }
}

/** Актуальные снимки всех сообщений: сообщение + день-разделитель + стрим-флаг + эпоха хрома. */

/** Актуальные снимки всех сообщений: сообщение + день-разделитель + стрим-флаг + эпоха хрома. */
internal fun ChatActivity.currentSnapshots(): List<CardSnapshot> =
    snapshotCache.update(
        messages = uiState.messages,
        chromeEpoch = transcriptChromeEpoch,
        typing = uiState.status == ChatStatus.TYPING,
    )

/** Хвост рендера (общий для синхронного и фонового путей): поиск, чипы, автоскролл. */

/** Хвост рендера (общий для синхронного и фонового путей): поиск, чипы, автоскролл. */
internal fun ChatActivity.finishTranscriptRender() {
    // Открытый поиск переживает перерисовку (стрим/новый ход): совпадения пересчитываются,
    // позиция сохраняется, скролл не дёргается — пользователь остаётся там, где читал.
    if (isSearchPlateReady() && searchPlate.visibility == View.VISIBLE) {
        refreshTranscriptSearch(resetPosition = false, scrollToMatch = false)
    }
    renderSmartChips()
    if (autoScroll.shouldScrollOnContentChanged()) scrollToBottom() else syncScrollToBottomFab()
}


/** Разовая полная сборка (смена сессии / эпоха хрома / укорочение списка) — как старый рендер. */

/** Разовая полная сборка (смена сессии / эпоха хрома / укорочение списка) — как старый рендер. */
internal fun ChatActivity.rebuildTranscript(current: List<CardSnapshot>) {
    messages.removeAllViews()
    cardEntries.clear()
    uiState.messages.forEachIndexed { index, message ->
        val entry = buildCardEntry(index, message, current[index])
        messages.addView(entry.wrapper, LinearLayout.LayoutParams(-1, -2))
        cardEntries.add(entry)
    }
}

/** Точечный патч: грязные карточки заменяются на месте, новые дозапускаются в хвост. */

/** Точечный патч: грязные карточки заменяются на месте, новые дозапускаются в хвост. */
internal fun ChatActivity.applyTranscriptPatch(plan: TranscriptPlan.Patch, current: List<CardSnapshot>) {
    for (patch in plan.updates) {
        val message = uiState.messages.getOrNull(patch.index) ?: continue
        val old = cardEntries.getOrNull(patch.index) ?: continue
        val newSnapshot = current[patch.index]
        // Text-only: карточка живёт, пересобирается только markdown-секция (без шапки/кнопок).
        // Секция рисуется инкрементально: неизменившийся префикс строк остаётся на месте.
        val host = old.markdownHost
        val state = old.markdownState
        if (patch.kind == CardUpdateKind.TEXT_ONLY && host != null && state != null) {
            renderMarkdownBlocks(message.text, host, message.role == "user", patch.index, state)
            old.snapshot = newSnapshot
            continue
        }
        val replacement = buildCardEntry(patch.index, message, newSnapshot)
        val position = messages.indexOfChild(old.wrapper).takeIf { it >= 0 } ?: patch.index
        messages.removeViewAt(position)
        messages.addView(replacement.wrapper, position, LinearLayout.LayoutParams(-1, -2))
        cardEntries[patch.index] = replacement
    }
    for (index in plan.appendFrom until current.size) {
        val message = uiState.messages.getOrNull(index) ?: continue
        val entry = buildCardEntry(index, message, current[index])
        messages.addView(entry.wrapper, LinearLayout.LayoutParams(-1, -2))
        cardEntries.add(entry)
    }
}

/**
 * Fast path стрима: грязная карточка ровно одна и изменился только её текст. Тяжёлый
 * MarkdownFormatter.parseBlocks уходит на Dispatchers.Default; на UI-поток возвращается
 * только публикация готовых блоков. Версионирование кадров ([FrameVersionGate]): устаревший
 * кадр не публикуется поверх более свежего рендера. Job живёт в lifecycleScope — умирает
 * вместе с активити, висящих корутин не оставляет.
 *
 * @return true — обновление запланировано фоном; синхронный патч в этот кадр не нужен.
 */

/**
 * Fast path стрима: грязная карточка ровно одна и изменился только её текст. Тяжёлый
 * MarkdownFormatter.parseBlocks уходит на Dispatchers.Default; на UI-поток возвращается
 * только публикация готовых блоков. Версионирование кадров ([FrameVersionGate]): устаревший
 * кадр не публикуется поверх более свежего рендера. Job живёт в lifecycleScope — умирает
 * вместе с активити, висящих корутин не оставляет.
 *
 * @return true — обновление запланировано фоном; синхронный патч в этот кадр не нужен.
 */
internal fun ChatActivity.tryAsyncTextOnlyUpdate(plan: TranscriptPlan.Patch, current: List<CardSnapshot>): Boolean {
    if (plan.appendFrom != current.size) return false // есть новые карточки — общий синхронный патч
    val only = plan.updates.singleOrNull() ?: return false
    if (only.kind != CardUpdateKind.TEXT_ONLY) return false
    val entry = cardEntries.getOrNull(only.index) ?: return false
    val host = entry.markdownHost ?: return false // секции ещё нет (пустой пузырь) — полная сборка
    val state = entry.markdownState ?: return false
    val message = uiState.messages.getOrNull(only.index) ?: return false
    val text = message.text
    val isUser = message.role == "user"
    val messageIndex = only.index
    val version = frameGate.beginFrame()
    markdownParseJob = lifecycleScope.launch(Dispatchers.Default) {
        val blocks = MarkdownFormatter.parseBlocks(text)
        withContext(Dispatchers.Main.immediate) {
            if (!frameGate.canApply(version)) return@withContext // кадр устарел (прошёл синхронный рендер)
            markdownParseJob = null
            if (isDestroyed || !isMessagesReady()) return@withContext
            // Карточку могли заменить синхронным патчем, пока парсинг летел, — обновляем только «свою».
            if (cardEntries.getOrNull(messageIndex) !== entry) return@withContext
            ChatMarkdownRenderer.render(
                context = this@tryAsyncTextOnlyUpdate,
                blocks = blocks,
                parent = host,
                user = isUser,
                messageIndex = messageIndex,
                state = state,
                isCodeBlockExpanded = uiState::isCodeBlockExpanded,
                toggleCodeBlock = { index, blockIndex -> uiState.toggleCodeBlock(index, blockIndex); transcriptChromeEpoch++; renderMessages() },
                copyMessage = ::copyMessage,
                openImage = lightbox::open,
                openUrl = { url -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
            )
            entry.snapshot = current[messageIndex]
            renderedSnapshots = current
            frameGate.commit(version)
            finishTranscriptRender()
        }
    }
    return true
}

/** Собирает одну карточку: wrapper = необязательный день-разделитель + пузырь (метрики прежние). */

/** Собирает одну карточку: wrapper = необязательный день-разделитель + пузырь (метрики прежние). */
internal fun ChatActivity.buildCardEntry(index: Int, message: ChatMessage, snapshot: CardSnapshot): CardEntry {
    val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    if (snapshot.showDaySeparator) {
        wrapper.addView(
            buildDaySeparator(snapshot.separatorTimestamp),
            LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(10)
            }
        )
    }
    if (message.isTrimAnchor) {
        wrapper.addView(buildTrimAnchorCard(message), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return CardEntry(wrapper, snapshot, null)
    }
    val views = ChatMessageCardRenderer.build(this, message, index, snapshot, cardRendererHost)
    wrapper.addView(views.root, cardLayoutParams(snapshot))
    return CardEntry(wrapper, snapshot, views.markdownHost, views.markdownState)
}

/** Геометрия пузыря: 88% ширины экрана, справа у пользователя, слева у ассистента, отступ 8dp. */

/** Геометрия пузыря: 88% ширины экрана, справа у пользователя, слева у ассистента, отступ 8dp. */
internal fun ChatActivity.cardLayoutParams(snapshot: CardSnapshot): LinearLayout.LayoutParams {
    val params = LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * .88).toInt(), -2)
    params.topMargin = dp(8)
    params.gravity = if (snapshot.isUser) Gravity.END else Gravity.START
    return params
}

/** Карточка-якорь «старые сообщения подрезаны» ([TranscriptCapPolicy]). */

/** Карточка-якорь «старые сообщения подрезаны» ([TranscriptCapPolicy]). */
internal fun ChatActivity.buildTrimAnchorCard(message: ChatMessage): View = TextView(this).apply {
    text = TranscriptCapPolicy.anchorLabel(message.trimmedCount)
    textSize = 11f
    setTextColor(MarsTheme.TEXT_MUTED)
    gravity = Gravity.CENTER
    setPadding(dp(12), dp(6), dp(12), dp(6))
}

// --- Поиск по диалогу: хост-обвязка TranscriptSearchHighlightController -------------------

/** День-чип разделителя суток («Сегодня»/«Вчера»/«9 октября») — подпись из MessageTimePolicy. */

// --- Поиск по диалогу: хост-обвязка TranscriptSearchHighlightController -------------------

/** День-чип разделителя суток («Сегодня»/«Вчера»/«9 октября») — подпись из MessageTimePolicy. */
internal fun ChatActivity.buildDaySeparator(timestampMillis: Long): TextView = TextView(this).apply {
    text = MessageTimePolicy.dayLabel(timestampMillis, System.currentTimeMillis())
    textSize = 10.5f
    setTextColor(MarsTheme.TEXT_MUTED)
    background = MarsTheme.pill(this@buildDaySeparator, MarsTheme.SURFACE_1, strokeColor = MarsTheme.BORDER_SUBTLE)
    setPadding(dp(10), dp(3), dp(10), dp(3))
    gravity = Gravity.CENTER
}
