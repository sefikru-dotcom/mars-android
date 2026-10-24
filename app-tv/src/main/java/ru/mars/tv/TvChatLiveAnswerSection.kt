package ru.mars.tv

import android.os.SystemClock
import android.view.View
import android.widget.TextView
import ru.mars.tv.chat.TvLiveRenderBatchPolicy
import ru.mars.tv.chat.TvMarkdownBlocks
import ru.mars.tv.chat.TvMarkdownRenderer

/**
 * Секция «Живой ответ» экрана ТВ-чата.
 *
 * Вынесено из [TvChatActivity] (декомпозиция хоста, W2).
 *
 * Батчинг токенов живого пузыря ([TvLiveRenderBatchPolicy]), финализация в rich-пузырь
 * и автоскролл: прокрутка держится внизу, пока пользователь не ушёл вверх.
 *
 * Форма переноса: extension-функции на Activity — состояние (View-поля, job-ы,
 * биндинг сессии) остаётся в хосте, тела перенесены дословно. Члены хоста, которые
 * читают секции, подняты `private` -> `internal`.
 */

/** Пузырь-заготовка живого ответа (поток chunk'ов пишет в него через [liveAnswer]). */
internal fun TvChatActivity.appendLivePlaceholder(): TextView =
    plainBubble("Марс", "", isUser = false).also { addTranscriptView(it) }

/**
 * Ответ ДОТЕКАЛ до конца и содержит таблицу/чек-лист: плоский стриминговый пузырь
 * заменяется «богатым» рендером того же текста (markdown-паритет с мобильным).
 * Делается ровно один раз — [TvAnswerAccumulator.onDone] возвращает true только первым.
 */
internal fun TvChatActivity.finalizeLiveAnswerRich() {
    val accumulator = liveAccumulator ?: return
    val answer = liveAnswer ?: return
    if (accumulator.state != TvAnswerAccumulator.State.DONE) return
    val text = accumulator.text()
    if (!TvMarkdownBlocks.isRich(text)) return
    val index = messages.indexOfChild(answer)
    if (index < 0) return
    val params = answer.layoutParams
    detachTranscriptView(answer)
    messages.removeViewAt(index)
    messages.addView(TvMarkdownRenderer.richBubble(this, text, { value -> dp(value) }), index, params)
    // Пузырь заменён — поздние chunk'и терминального ответа уже отброшены аккумулятором,
    // а reattach после Done заведёт свежий placeholder (liveAnswer == null).
    liveAnswer = null
}

// --- батчинг живого пузыря (TvLiveRenderBatchPolicy) ----------------------------------

/** Накопленный текст чанков рисуется не чаще одного раза в интервал. */
internal fun TvChatActivity.onLiveAnswerText(rendered: String) {
    pendingLiveRender = rendered
    when (val decision = TvLiveRenderBatchPolicy.decide(
        flushPending = liveFlushScheduled,
        elapsedSinceLastRenderMs = SystemClock.uptimeMillis() - lastLiveRenderMs,
    )) {
        TvLiveRenderBatchPolicy.Decision.Now -> flushLiveAnswer()
        is TvLiveRenderBatchPolicy.Decision.Defer -> {
            liveFlushScheduled = true
            uiHandler.postDelayed(liveFlushRunnable, decision.delayMs)
        }
        TvLiveRenderBatchPolicy.Decision.Nothing -> Unit
    }
}

/** Фактическая отрисовка: всегда ПОСЛЕДНИЙ накопленный текст (не очередь чанков). */
internal fun TvChatActivity.flushLiveAnswer() {
    uiHandler.removeCallbacks(liveFlushRunnable)
    liveFlushScheduled = false
    val rendered = pendingLiveRender ?: return
    pendingLiveRender = null
    lastLiveRenderMs = SystemClock.uptimeMillis()
    liveAnswer?.text = rendered
    scrollDown()
}

/**
 * Снятие отложенного flush (терминал/сброс/новый ход). [applyPending] дорисовывает
 * недоставленный текст — используется на Done, чтобы хвостовой чанк не потерялся.
 */
internal fun TvChatActivity.cancelLiveFlush(applyPending: Boolean) {
    uiHandler.removeCallbacks(liveFlushRunnable)
    liveFlushScheduled = false
    if (applyPending) {
        pendingLiveRender?.let { rendered ->
            lastLiveRenderMs = SystemClock.uptimeMillis()
            liveAnswer?.text = rendered
            scrollDown()
        }
    }
    pendingLiveRender = null
}

/**
 * Auto-scroll of a live transcript: runs ONLY while the reader is pinned to the
 * bottom ([TvAutoScrollPolicy.shouldAutoScroll]) — a user rereading a scrolled-up
 * line is never yanked back by an incoming chunk or card.
 */
internal fun TvChatActivity.scrollDown() {
    if (autoScroll.shouldAutoScroll()) scrollToBottom()
}

/**
 * Unconditional re-pin + scroll (outgoing message, opened/reloaded session, jump
 * control): hides «К последнему» and puts the transcript at the live edge.
 */
internal fun TvChatActivity.pinAndScrollToBottom() {
    renderJumpButton(autoScroll.pinToBottom())
    scrollToBottom()
}

/**
 * Focus-stable bottom scroll: `scrollTo` (clamped by the pure
 * [TvAutoScrollPolicy.scrollTargetForBottom]) instead of `fullScroll(FOCUS_DOWN)` —
 * the old call also moved the D-pad focus into the transcript on EVERY token.
 */
internal fun TvChatActivity.scrollToBottom() {
    messageScroll.post {
        val child = messageScroll.getChildAt(0) ?: return@post
        messageScroll.scrollTo(
            0,
            TvAutoScrollPolicy.scrollTargetForBottom(
                child.bottom, messageScroll.height, messageScroll.paddingBottom,
            ),
        )
    }
}

/**
 * «К последнему» visibility. Hiding a FOCUSED control would drop the D-pad focus
 * into nowhere (unstable focus on disappearance), so the focus moves to the
 * composer first — the natural continuation after jumping to the live edge.
 */
internal fun TvChatActivity.renderJumpButton(visible: Boolean) {
    if (!visible && jumpToBottom.hasFocus()) input.requestFocus()
    jumpToBottom.visibility = if (visible) View.VISIBLE else View.GONE
}
