package ru.mars.mobile

import android.os.SystemClock
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.network.repository.SessionRepository

/**
 * Черновики композера: локальное сохранение и серверный draft-sync.
 * 
 * Вынесено из [ChatActivity] (W2) как internal-расширения; решения — в
 * [ServerDraftSyncPolicy] и [SessionDraftController].
 */

// --- Черновики несент-сообщений ------------------------------------------------------------

/** Сохраняет текст композитора как черновик текущего диалога (до смены диалога/ухода с экрана). */
internal fun ChatActivity.persistComposerDraft() {
    if (!isDraftsReady() || !isInputReady()) return
    // После logout/реавторизации сохранение запрещено: onPause закрывающегося экрана
    // не должен записывать черновик обратно в уже очищенное приватное хранилище.
    if (isAuthExitCleanupReady() && !authExitCleanup.draftPersistenceAllowed) return
    drafts.saveDraft(currentSessionId, input.text.toString())
    // A15: уход с экрана — остаток серверного черновика досылается вне окна дебаунса.
    flushServerDraftOnPause()
}

/** Показывает сохранённый черновик текущего диалога (null — отдельный черновик нового чата). */

/** Показывает сохранённый черновик текущего диалога (null — отдельный черновик нового чата). */
internal fun ChatActivity.restoreComposerDraft() {
    if (!isDraftsReady() || !isInputReady()) return
    val text = drafts.draftFor(currentSessionId).orEmpty()
    setComposerTextProgrammatically(text)
}

/**
 * Вставляет текст в композитор без реакции вотчера на «ввод пользователя»:
 * без флага программная вставка немедленно ушла бы на сервер как черновик
 * и перетёрла бы более свежую серверную копию.
 *
 * O7: перед записью спрашиваем [ComposerInputGuardPolicy]. Пока пользователь набирал
 * текст, [TextApplyOrigin.DRAFT_RESTORE] получает `Preserve` — восстановление
 * черновика (возврат на экран, холодный старт) не затирает набранное.
 */

/**
 * Вставляет текст в композитор без реакции вотчера на «ввод пользователя»:
 * без флага программная вставка немедленно ушла бы на сервер как черновик
 * и перетёрла бы более свежую серверную копию.
 *
 * O7: перед записью спрашиваем [ComposerInputGuardPolicy]. Пока пользователь набирал
 * текст, [TextApplyOrigin.DRAFT_RESTORE] получает `Preserve` — восстановление
 * черновика (возврат на экран, холодный старт) не затирает набранное.
 */
internal fun ChatActivity.setComposerTextProgrammatically(
    text: String,
    origin: TextApplyOrigin = TextApplyOrigin.DRAFT_RESTORE,
) {
    if (composerGuard.resolveTextApply(origin) == ComposerTextDecision.Preserve) return
    val previous = composerTextProgrammatic
    composerTextProgrammatic = true
    try {
        input.setText(text)
        if (text.isNotEmpty()) input.setSelection(text.length)
    } finally {
        composerTextProgrammatic = previous
    }
    composerGuard.onTextAppliedProgrammatically()
}

// --- A15: серверный черновик composer -------------------------------------------------------

/**
 * Планирует запись черновика в `POST /api/session/draft` по правилу
 * [ServerDraftSyncPolicy]: не чаще ~2 с покоя при вводе. Пустая строка тоже
 * записывается — она очищает серверную копию после отправки/стирания текста.
 * Ошибки подавляются: фоновый автосейв не имеет права шуметь в статусе.
 */

// --- A15: серверный черновик composer -------------------------------------------------------

/**
 * Планирует запись черновика в `POST /api/session/draft` по правилу
 * [ServerDraftSyncPolicy]: не чаще ~2 с покоя при вводе. Пустая строка тоже
 * записывается — она очищает серверную копию после отправки/стирания текста.
 * Ошибки подавляются: фоновый автосейв не имеет права шуметь в статусе.
 */
internal fun ChatActivity.scheduleServerDraftSync(text: String) {
    val id = currentSessionId ?: return
    serverDraftJob?.cancel()
    val wait = serverDraftSync.delayFor(text, SystemClock.elapsedRealtime()) ?: return
    serverDraftJob = lifecycleScope.launch {
        if (wait > 0) delay(wait)
        saveServerDraft(id, text)
    }
}

/** Уход с экрана: остаток черновика досылается немедленно, если он отличается от записанного. */

/** Уход с экрана: остаток черновика досылается немедленно, если он отличается от записанного. */
internal fun ChatActivity.flushServerDraftOnPause() {
    val id = currentSessionId ?: return
    val text = if (isInputReady()) input.text.toString() else return
    if (!serverDraftSync.drainOnPause(text)) return
    serverDraftJob?.cancel()
    // Отдельная корутина вне lifecycleScope: onPause может переживать сам экран
    // (пересоздание), а незаписанный черновик — единственная копия текста пользователя.
    lifecycleScope.launch { saveServerDraft(id, text) }
}

internal suspend fun ChatActivity.saveServerDraft(sessionId: String, text: String) {
    val credential = store.read() ?: return
    if (isAuthExitCleanupReady() && !authExitCleanup.draftPersistenceAllowed) return
    withContext(Dispatchers.IO) {
        runCatching {
            SessionRepository().saveComposerDraft(ChatActivity.BASE_URL, credential, sessionId, text)
        }
    }
    serverDraftSync.onSaved(text, SystemClock.elapsedRealtime())
}

/**
 * A15: подстановка серверного черновика при открытии диалога. Порядок строгий
 * ([ServerDraftAdoption]): набранный пользователем текст > локальный черновик >
 * серверный. Серверный черновик только дополняет локальный и никогда не
 * затирает то, что печатают прямо сейчас.
 */

/**
 * A15: подстановка серверного черновика при открытии диалога. Порядок строгий
 * ([ServerDraftAdoption]): набранный пользователем текст > локальный черновик >
 * серверный. Серверный черновик только дополняет локальный и никогда не
 * затирает то, что печатают прямо сейчас.
 */
internal fun ChatActivity.adoptServerDraft(serverDraft: String?) {
    if (serverDraft.isNullOrBlank()) return
    val local = drafts.draftFor(currentSessionId)
    val target = ServerDraftAdoption.textFor(serverDraft, local, input.text.toString()) ?: return
    setComposerTextProgrammatically(target)
    drafts.saveDraft(currentSessionId, target)
}

// --- A14: расход/стоимость сессии ----------------------------------------------------------

/**
 * Запрашивает расход сессии (`GET /api/session/usage`) и показывает компактную
 * пилюлю: токены и стоимость. Недоступный/нулевой расход пилюлю скрывает —
 * «$0.00» вместо неизвестной цены был бы выдумкой. Вызывается при открытии
 * диалога и по завершении хода (расход меняется только ходами).
 */
