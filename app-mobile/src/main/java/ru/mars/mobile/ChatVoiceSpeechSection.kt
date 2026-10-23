package ru.mars.mobile

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.ClipboardManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.network.cache.SessionOfflineCache
import ru.mars.network.cache.SharedPreferencesKeyValueStore
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.ActiveTurnInfo
import ru.mars.network.model.ChatAttachment
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.ContextStatusUpdate
import ru.mars.network.model.MusicTrack
import ru.mars.network.model.SessionItem
import ru.mars.network.model.SessionDetail
import ru.mars.network.model.SessionUsage
import ru.mars.network.model.SessionAttention
import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.SubagentStatusUpdate
import ru.mars.network.repository.ChatRepository
import ru.mars.network.repository.ApprovalChoice
import ru.mars.network.repository.CompressionSummary
import ru.mars.network.repository.ContextCompressionRepository
import ru.mars.network.repository.InteractiveRequestsRepository
import ru.mars.network.repository.ModelsRepository
import ru.mars.network.repository.PairingRepository
import ru.mars.network.repository.PendingApprovalLookup
import ru.mars.network.repository.PendingClarifyLookup
import ru.mars.network.repository.ReasoningRepository
import ru.mars.network.repository.ReasoningStatusResult
import ru.mars.network.repository.ProviderQuotaOutcome
import ru.mars.network.repository.SessionToolsetsOutcome
import ru.mars.network.repository.SessionRepository
import ru.mars.network.repository.SessionAutonomyRepository
import ru.mars.network.repository.AutonomyLookup
import ru.mars.network.repository.TranscribeOutcome
import ru.mars.network.repository.UploadOutcome
import ru.mars.network.repository.UploadTooLargeException
import ru.mars.network.repository.WorkspaceRepository
import ru.mars.network.repository.readBoundedUploadPayload

/**
 * Голосовой вывод: потоковая озвучка, barge-in, перебивание.
 *
 * Вынесено из [ChatActivity] (W4, технический долг): те же extension-функции на
 * Activity, что были в God Object, поэтому проводка и поведение не меняются.
 */

/**
 * Ход закончился (Done / конец стрима / recovery-финиш). Если ответ зазвучал — цикл в
 * SPEAKING и ждёт конца озвучки; если читать нечего — сразу снова слушаем.
 *
 * «Зазвучал» проверяется по ОБОИМ путям озвучки ([isVoiceSpeakingNow]): потоковая очередь
 * фраз — не `voiceOutput.speaking`, и старая проверка только по нему отправляла цикл в
 * LISTENING посреди звучащего ответа (микрофон слышал динамик).
 */
internal fun ChatActivity.onVoiceTurnCompleted() {
    if (!isVoiceLoopReady() || !voiceLoop.isActive) return
    if (isVoiceSpeakingNow()) voiceLoop.onAnswerStarted(spokenAnswerText()) else voiceLoop.onAnswerFinished()
}

/**
 * Что реально звучит/звучало в этом ходе — для echo-гейта перебивания.
 *
 * Приоритет у потоковой озвучки: именно её текст ушёл в синтез фразами. Если её не было —
 * берём ответ целиком (путь [VoiceOutputController]). `null` означает «текста нет»: тогда
 * на динамике перебивание по тексту не подтвердится, и это правильный отказ, а не догадка.
 */
internal fun ChatActivity.spokenAnswerText(): String? {
    val streamed = if (isStreamSpeechReady()) streamingSpeech.spokenText else null
    if (!streamed.isNullOrBlank()) return streamed
    return latestAssistantAnswer()?.takeIf { it.isNotBlank() }
}

/** Звучит ли ответ прямо сейчас: платформенный TTS или потоковая очередь фраз. */
internal fun ChatActivity.isVoiceSpeakingNow(): Boolean =
    (isVoiceOutputReady() && voiceOutput.speaking) ||
        (isStreamSpeechReady() && streamingSpeech.isSpeaking)

/**
 * Текущее описание вида: фаза выводится из состояния цикла и факта звука.
 *
 * `null` вместо состояния цикла, когда режим выключен: `IDLE` бывает и «цикл не запущен»,
 * и «выключен пользователем» — для интерфейса это одно и то же (кнопка нейтральная).
 */
internal fun ChatActivity.currentVoiceUi(): VoiceDialogUi {
    val loopState = if (isVoiceLoopReady() && voiceLoop.isActive) voiceLoop.state else null
    val phase = voicePresentation.phaseFor(loopState, isVoiceSpeakingNow())
    return voicePresentation.uiFor(phase, voiceIcons)
}

/** Подпись для статусной строки; `null` — режим выключен, строкой владеет чат. */
internal fun ChatActivity.voiceStatusLabel(): String? {
    val ui = currentVoiceUi()
    return if (ui.active && ui.label.isNotEmpty()) ui.label else null
}

/**
 * Нарисовать кнопку микрофона по текущей фазе. Перерисовка только на смене фазы: во время
 * стрима setStatus() зовётся на каждый троттлированный кадр, и собирать GradientDrawable
 * каждый раз значило бы мусорить в GC на ровном месте.
 */
internal fun ChatActivity.renderVoiceButton() {
    val button = voiceBtn ?: return
    val ui = currentVoiceUi()
    if (ui.phase == renderedVoicePhase) return
    renderedVoicePhase = ui.phase
    VoiceMicButtonRenderer.render(this, button, ui)
}

/**
 * Перебивание: локальная остановка звука + сетевой interrupt, БЕЗ отмены хода.
 *
 * Порядок важен: сначала тишина (человек уже говорит), потом сеть. `cancelStream()` здесь
 * вызывать нельзя — он убил бы ход, а перебивание означает лишь «замолчи и слушай».
 */
internal fun ChatActivity.bargeIn() {
    if (isVoiceOutputReady()) voiceOutput.stopSpeaking()
    cancelStreamSpeech()
    requestVoiceInterrupt()
    setStatus()
}

/**
 * Серверный interrupt текущего голосового канала.
 *
 * Шов заполнен в [onCreate] ([VoiceInterruptChannel]): `voice_id` создаётся по фактическому
 * API при первом перебивании в сессии и закрывается на выходе из режима. Перебивание НЕ
 * отменяет ход агента — отмены (`api/chat/cancel`) здесь нет намеренно: человек хочет
 * замолчать, а не оборвать ответ. Сессия ещё не создана (`currentSessionId == null`) —
 * перебивать нечего, канал сам отбросит пустой id без сетевого вызова.
 */
internal fun ChatActivity.requestVoiceInterrupt() {
    val hook = voiceInterruptHook ?: return
    hook(currentSessionId)
}

/**
 * Конец озвучки хода — единственный путь «дочитать/не дочитывать финал».
 *
 * Правило: если ответ уже звучал потоково, финал не читается второй раз
 * ([VoiceTurnSpeechPolicy.onFinal] вернёт `null`), а потоковая очередь только дочитывает
 * остаток буфера. Если потоковой озвучки не было — финал звучит обычным путём.
 */
internal fun ChatActivity.finishVoiceTurn(speakFinal: Boolean) {
    if (isVoiceOutputReady()) {
        val finalText = if (speakFinal) voiceTurnSpeech.onFinal(latestAssistantAnswer()) else null
        if (isStreamSpeechReady()) endStreamSpeech()
        // Условие «ответ ещё не звучал» уже внутри политики (onFinal вернул null). Проверять
        // здесь streamSpeech нельзя: при включённой потоковой озвучке и ходе без
        // текстовых дельт (только инструменты, ответ пришёл целиком в Done) финал иначе
        // не прозвучал бы вообще.
        if (finalText != null) voiceOutput.onTurnComplete(finalText)
    }
    onVoiceTurnCompleted()
}

/**
 * Начался вызов инструмента: одна короткая фраза «Проверяю…» на ход, без имени инструмента
 * и без выдуманного результата. Звучит только когда ответ вообще озвучивается
 * ([StreamSpeechLifecycle.isSpeechEnabled]) — иначе режим «читать вслух» выключен и лезть
 * в динамик нечем.
 */
internal fun ChatActivity.announceToolProgress(toolName: String) {
    if (!streamSpeech.isSpeechEnabled || !isStreamSpeechReady()) return
    val line = voiceTurnSpeech.onToolStarted(toolName) ?: return
    streamingSpeech.speakWhole(line)
}

/**
 * Дельта размышлений. В речь не идёт никогда: это черновик модели, а не ответ. Метод
 * существует, чтобы правило было видно в проводке, а не только в политике.
 */
internal fun ChatActivity.onAssistantThought(text: String) {
    voiceTurnSpeech.onDeliberation(text)
}

/**
 * Ход надо озвучивать потоково: авторежим или одноразовое взведение голосового ввода, и
 * микрофонный цикл при этом не в диалоге (иначе озвучка накладывалась бы на запись).
 */
internal fun ChatActivity.streamSpeechEnabled(): Boolean =
    isStreamSpeechReady() && isVoiceOutputReady() && voiceOutput.wantsSpeech

/**
 * Начало хода: взять решение об озвучке ОДИН раз. Дальше все дельты этого хода идут в
 * [streamingSpeech] либо не озвучиваются вовсе — переключение на середине дало бы
 * полуфразу в динамике и вторую половину в конце.
 */
internal fun ChatActivity.beginStreamSpeech() {
    streamSpeech.begin(streamSpeechEnabled())
    if (streamSpeech.isSpeechEnabled) voiceOutput.consumeSpeakNextAnswer()
    streamingSpeech.stop()
    // Новый ход — новая квота на «Проверяю…» и сброс «ответ уже звучал»: иначе второй ход
    // молчал бы про инструменты, а финал первого глушил бы озвучку второго.
    voiceTurnSpeech.onTurnStarted()
}

/**
 * Дельта ответа (только текст ассистента, не размышления и не инструменты).
 *
 * Факт «ответ уже пошёл в звук» фиксируется и тогда, когда озвучка выключена: финальный
 * Done не должен читать весь ответ целиком поверх частично произнесённого.
 */
internal fun ChatActivity.onAssistantDelta(text: String) {
    if (text.isEmpty()) return
    // Политика идёт ПЕРВОЙ намеренно: факт «ответ уже пошёл» нужен и при выключенной
    // озвучке, иначе терминальный Done прочитал бы весь ответ целиком. Раньше это же
    // свойство превращало баг жизненного цикла в потерю звука: очередь пустела между
    // фразами, [streamSpeech] гас навсегда, дельты отбрасывались, а финал был уже
    // «озвучен». Теперь гаснет только конец хода ([StreamSpeechLifecycle.end]), поэтому
    // отбрасывать здесь можно лишь хвост уже завершённого или отменённого хода.
    val speakable = voiceTurnSpeech.onGeneratedText(text) ?: return
    if (!streamSpeech.acceptsDelta) return
    streamingSpeech.onDelta(speakable)
}

/**
 * Ход завершён: остаток буфера тоже должен прозвучать.
 *
 * «Доиграло» объявляется только по пустой ОЧЕРЕДИ, а не по отсутствию звука: хвостовая фраза,
 * ушедшая в синтез на [streamingSpeech.onTurnFinished], ещё не играет, поэтому
 * `isSpeaking == false` при непустой очереди — это «звук впереди», а не «конец». Ранний
 * drained в этот момент гасит [streamSpeech], и следующая [onStreamSpeechPhraseStarted]
 * глушит уже готовое аудио.
 */
internal fun ChatActivity.endStreamSpeech() {
    if (!streamSpeech.isSpeechEnabled) return
    streamingSpeech.onTurnFinished()
    streamSpeech.end()
    // Стрим закрылся, а новых фраз нет — сигнал «доиграло» может не прийти вовсе.
    if (!streamingSpeech.hasPendingSpeech) onStreamSpeechDrained()
}

/** Ход отменён/прерван/экран ушёл: тишина немедленно, хвост ответа не догоняет. */
internal fun ChatActivity.cancelStreamSpeech() {
    streamSpeech.cancel()
    if (!isStreamSpeechReady()) return
    streamingSpeech.stop()
    if (isVoiceOutputReady()) voiceOutput.setExternalSpeaking(false)
}

/**
 * Пошло первое аудио ответа: внешний динамик помечается занятым.
 *
 * Эталон эха здесь НЕ задаётся — его несёт [onStreamSpeechPhraseStarted] для каждой
 * реально зазвучавшей фразы. Раньше снимок брался именно тут, один раз на ответ, и
 * вторая фраза в эталон уже не попадала.
 */
internal fun ChatActivity.onStreamSpeechStarted() {
    if (!streamSpeech.isSpeechEnabled) { streamingSpeech.stop(); return }
    if (isVoiceOutputReady()) voiceOutput.setExternalSpeaking(true)
}

/**
 * Очередная фраза пошла в динамик — цикл получает её как эталон эха.
 *
 * Раньше цикл получал снимок [spokenAnswerText] один раз, на первом звуке, и в нём была
 * только первая фраза (или весь накопленный текст). Вторая и следующие фразы в эталон не
 * попадали, поэтому их эхо с динамика подтверждало перебивание — и Марс глушил сам себя.
 * Теперь передаётся ровно та фраза, которую очередь отдала в воспроизведение.
 */
internal fun ChatActivity.onStreamSpeechPhraseStarted(spokenText: String) {
    if (!streamSpeech.isSpeechEnabled) { streamingSpeech.stop(); return }
    if (isVoiceOutputReady()) voiceOutput.setExternalSpeaking(true)
    if (isVoiceLoopReady() && voiceLoop.isActive) voiceLoop.onAnswerSpeechStarted(spokenText)
}

/** Очередь фраз пуста и всё доиграно: цикл снова слушает. */
internal fun ChatActivity.onStreamSpeechDrained() {
    if (!streamSpeech.isSpeechEnabled) return
    if (isVoiceOutputReady()) voiceOutput.setExternalSpeaking(false)
    if (!streamSpeech.onQueueDrained()) return
    if (isVoiceLoopReady() && voiceLoop.isActive) voiceLoop.onAnswerFinished()
}

/** Speak a message through [VoiceOutputController] (markdown is filtered inside). */
internal fun ChatActivity.speakMessage(text: String) {
    // Ручная озвучка готового сообщения — тот же речевой путь, что у стрима: разметка,
    // код и таблицы фильтруются, фразы синтезируются очередью.
    if (isStreamSpeechReady()) {
        cancelStreamSpeech()
        // Ручная озвучка тоже проходит через цикл хода (SPEAKING → снова слушание),
        // поэтому помечается активной: иначе первый же звук был бы заглушён как «чужой».
        streamSpeech.begin(true)
        streamingSpeech.speakWhole(text)
        // Ход ручной озвучки состоит из одного куска: новых фраз не будет, очередь только
        // доигрывает — цикл должен услышать «ответ закончился» ровно один раз.
        streamSpeech.end()
        return
    }
    when (voiceOutput.speakNow(text)) {
        VoiceOutputController.SpeakOutcome.NOTHING_TO_SPEAK ->
            statusView.text = "В сообщении нет текста для озвучивания"
        VoiceOutputController.SpeakOutcome.ENGINE_UNAVAILABLE ->
            statusView.text = VOICE_OUTPUT_UNAVAILABLE
        VoiceOutputController.SpeakOutcome.SPOKEN -> Unit
    }
}

/** Финальный текст последнего ассистент-сообщения — голос озвучивает только его. */
internal fun ChatActivity.latestAssistantAnswer(): String? =
    uiState.messages.lastOrNull { it.role == "assistant" }?.text

internal fun ChatActivity.speakLastMessage() {
    uiState.messages.lastOrNull { it.role == "assistant" && it.text.isNotBlank() }?.let {
        speakMessage(it.text)
    }
}
