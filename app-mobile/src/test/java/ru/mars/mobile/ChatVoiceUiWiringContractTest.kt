package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Контракт проводки голосового диалога в UI-зоне.
 *
 * Instrumented-слоя в проекте нет, поэтому проводка закреплена как source-контракт (тот же
 * стиль, что [ChatAccessibilityContractTest]): тест читает РЕАЛЬНЫЙ production-исходник и
 * проверяет, что решения приняты политиками, а эффекты стоят именно там, где нужно.
 *
 * Что защищается (по требованиям пользователя):
 * 1. потоковая озвучка идёт на дельтах и НЕ повторяется на финале — один путь `finishVoiceTurn`;
 * 2. `tool.started` даёт одну фразу, `Thought` не озвучивается;
 * 3. тап-стоп глушит звук локально и шлёт сетевой interrupt, но НЕ отменяет ход;
 * 4. кнопка красная весь активный диалог и рисуется из одной политики;
 * 5. второй канал (голосовой SSE) не встраивается «для галочки» — живёт один chat SSE.
 */
class ChatVoiceUiWiringContractTest {

    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            val nested = File(dir, "app-mobile/$relative")
            if (nested.isFile) return nested.readText()
            dir = dir.parentFile
        }
        throw AssertionError("Не найден исходник: $relative")
    }

    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "app-mobile/src/main/java/ru/mars/mobile/ChatActivity.kt").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("Не найден корень репозитория")
    }

    private val chat by lazy { source("src/main/java/ru/mars/mobile/ChatActivity.kt") }
    private val turnSection by lazy { source("src/main/java/ru/mars/mobile/ChatTurnSection.kt") }
    private val sessionSection by lazy { source("src/main/java/ru/mars/mobile/ChatSessionSection.kt") }

    /**
     * Секции-расширения Activity после разбиения файла (W2): вынесенный код остаётся тем же
     * production-кодом экрана, поэтому проводка голоса читается по Activity + секциям.
     * Набор требований не сужен — те же строки ищутся шире.
     */
    private val chatSections by lazy {
        listOf(
            "ChatInitSection.kt",
            "ChatShellSection.kt",
            "ChatComposerSection.kt",
            "ChatCardHostSection.kt",
            "ChatStatusSection.kt",
            "ChatTurnSection.kt",
            "ChatSlashCommandsSection.kt",
            "ChatSessionSection.kt",
            "ChatSessionLifecycleSection.kt",
            "ChatTranscriptSection.kt",
            "ChatSearchSection.kt",
            "ChatDraftSection.kt",
            "ChatModelSection.kt",
            "ChatUsageSection.kt",
            "ChatTodoSection.kt",
            "ChatMessageActionsSection.kt",
            "ChatOfflineQueueSection.kt",
            "ChatVoiceInputSection.kt",
            "ChatVoiceSpeechSection.kt",
        ).joinToString("\n") { source("src/main/java/ru/mars/mobile/$it") }
    }

    private val chatUi by lazy { chat + "\n" + chatSections }

    /** Тело функции по имени: от `fun name(` до парной закрывающей скобки. */
    private fun body(src: String, name: String): String {
        // `private fun` первым: у `startVoiceInput`/`toggleHandsFree` есть ещё и override-делегат
        // в listener'е каркаса (`this@ChatActivity.startVoiceInput()`), и его тело ничего не
        // доказывает про проводку.
        var start = src.indexOf("private fun $name(")
        if (start < 0) start = src.indexOf("internal fun $name(")
        if (start < 0) start = src.indexOf("fun $name(")
        if (start < 0) {
            // Функция-расширение: `internal fun ChatActivity.name(`. Ищем по сигнатуре, а не по
            // подстроке, иначе `fun name(` не находится вовсе.
            val match = Regex("""fun\s+(?:[\w.]+\.)?${Regex.escape(name)}\s*\(""").find(src)
            if (match != null) start = match.range.first
        }
        assertTrue("функция $name не найдена", start >= 0)
        val open = src.indexOf('{', start)
        assertTrue("у $name нет тела", open > start)
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("не закрыто тело $name")
    }

    // --- 1. потоковая озвучка: дельты, без повтора на финале ------------------------------------

    @Test
    fun `streaming speech starts on deltas and resets the turn policy`() {
        val begin = body(chatUi, "beginStreamSpeech")
        assertTrue(begin.contains("voiceTurnSpeech.onTurnStarted()"))
        val delta = body(chatUi, "onAssistantDelta")
        assertTrue(delta.contains("voiceTurnSpeech.onGeneratedText"))
        assertTrue(delta.contains("streamingSpeech.onDelta"))
    }

    @Test
    fun `turn end goes through one finishVoiceTurn decision`() {
        val finish = body(chatUi, "finishVoiceTurn")
        // Финал спрашивается у политики, а не читается напрямую: политика помнит, звучал ли ответ.
        assertTrue(finish.contains("voiceTurnSpeech.onFinal"))
        assertTrue(finish.contains("endStreamSpeech()"))
        assertTrue(finish.contains("onVoiceTurnCompleted()"))
        // Прямой onTurnComplete — только когда политика разрешила финал (ответ ещё не звучал).
        assertTrue(finish.contains("voiceOutput.onTurnComplete(finalText)"))
    }

    @Test
    fun `done and end of stream use the same path`() {
        assertTrue(turnSection.contains("if (event is ChatEvent.Done) finishVoiceTurn(speakFinal = true)"))
        // Второй путь «стрим закрылся без Done» не должен расходиться с первым.
        val sectionBody = turnSection.substringAfter("if (uiState.status == ChatStatus.TYPING) {")
        assertTrue(sectionBody.contains("finishVoiceTurn(speakFinal = true)"))
        // И ни один из них больше не озвучивает финал напрямую.
        assertFalse(turnSection.contains("voiceOutput.onTurnComplete"))
    }

    @Test
    fun `turn end drains on an empty queue, not on silent playback`() {
        val end = body(chatUi, "endStreamSpeech")
        // Решение «доиграло» принимается по очереди, а не по факту звука: хвостовая фраза,
        // ушедшая в синтез на конце хода, ещё не играет — `isSpeaking == false` при непустой
        // очереди означает «звук впереди». На этом предикате ранний drained гасил streamSpeech,
        // и следующая фраза глушилась в onStreamSpeechPhraseStarted.
        assertTrue(
            "конец хода должен смотреть на очередь, а не на текущий звук",
            end.contains("if (!streamingSpeech.hasPendingSpeech) onStreamSpeechDrained()"),
        )
        assertFalse(
            "ранний drained по isSpeaking обрезает хвост, который ещё синтезируется",
            end.contains("streamingSpeech.isSpeaking"),
        )
        // Флаш остатка обязан идти ДО решения: иначе последняя фраза не попадёт в очередь и
        // решение «пусто» станет верным по ошибке.
        val flushAt = end.indexOf("streamingSpeech.onTurnFinished()")
        val drainAt = end.indexOf("hasPendingSpeech")
        assertTrue("хвост флашится до решения о drained", flushAt in 0 until drainAt)
        // И очередь действительно сообщает о конце хода сама, когда доиграет.
        assertTrue(chatUi.contains("onDrained = { runOnUiThread { onStreamSpeechDrained() } }"))
    }

    @Test
    fun `recovered turn finishes through the same helper`() {
        assertTrue(sessionSection.contains("finishVoiceTurn(speakFinal = speakFinalAnswer)"))
        assertFalse(sessionSection.contains("voiceOutput.onTurnComplete"))
        // Reattach — начало хода: без сброса политики восстановленный финал молчал бы.
        assertTrue(sessionSection.contains("voiceTurnSpeech.onTurnStarted()"))
    }

    // --- 2. мысли и инструменты -----------------------------------------------------------------

    @Test
    fun `thoughts are routed to the silent policy branch`() {
        assertTrue(turnSection.contains("if (event is ChatEvent.Thought) onAssistantThought(event.text)"))
        val thought = body(chatUi, "onAssistantThought")
        assertTrue(thought.contains("voiceTurnSpeech.onDeliberation"))
        // Размышления не должны попадать в потоковую озвучку ни под каким видом.
        assertFalse(thought.contains("streamingSpeech"))
    }

    @Test
    fun `tool start announces one progress line and nothing else`() {
        assertTrue(turnSection.contains("if (event is ChatEvent.ToolCall) announceToolProgress(event.name)"))
        val progress = body(chatUi, "announceToolProgress")
        assertTrue(progress.contains("voiceTurnSpeech.onToolStarted"))
        assertTrue(progress.contains("streamingSpeech.speakWhole(line)"))
        // Результат инструмента не произносится: ToolResult в проводке озвучки отсутствует.
        assertFalse(turnSection.contains("ToolResult) announce"))
    }

    // --- 3. barge-in: локальный стоп + сетевой interrupt, без отмены хода -----------------------

    @Test
    fun `barge-in stops sound locally and never cancels the agent turn`() {
        val barge = body(chatUi, "bargeIn")
        assertTrue(barge.contains("voiceOutput.stopSpeaking()"))
        assertTrue(barge.contains("cancelStreamSpeech()"))
        assertTrue(barge.contains("requestVoiceInterrupt()"))
        // Главное: перебивание не убивает ход — «Стоп» для этого существует отдельно.
        assertFalse("bargeIn не должен отменять ход", barge.contains("cancelStream()"))
        assertFalse(barge.contains("teardownLiveTurn"))
    }

    @Test
    fun `loop barge-in callback also reaches the network seam`() {
        val listener = chatUi.substringAfter("override fun onStopSpeaking()")
        val stopBody = listener.substringBefore("override fun onSegmentDropped")
        assertTrue(stopBody.contains("cancelStreamSpeech()"))
        assertTrue(stopBody.contains("requestVoiceInterrupt()"))
    }

    @Test
    fun `network interrupt is a documented seam, not a second channel`() {
        assertTrue(chat.contains("internal var voiceInterruptHook"))
        val request = body(chatUi, "requestVoiceInterrupt")
        assertTrue(request.contains("currentSessionId"))
        // Голосовой SSE-канал не встроен в Activity: живой путь — существующий chat SSE.
        assertFalse("второй канал не должен появляться в UI-слое", chatUi.contains("VoiceGatewayClient"))
    }

    // --- 4. кнопка и статус ---------------------------------------------------------------------

    @Test
    fun `status line and button are driven by one call`() {
        val status = body(chatUi, "setStatus")
        assertTrue(status.contains("voiceStatusLabel()"))
        assertTrue(status.contains("renderVoiceButton()"))
        // Явный статус (обрыв/восстановление) остаётся сильнее голосового.
        assertTrue(status.indexOf("explicitStatus") < status.indexOf("voiceStatusLabel()"))
    }

    @Test
    fun `button rendering is phase-gated and delegates to the renderer`() {
        val render = body(chatUi, "renderVoiceButton")
        assertTrue(render.contains("VoiceMicButtonRenderer.render"))
        // Перерисовка только на смене фазы: setStatus зовётся на каждый кадр стрима.
        assertTrue(render.contains("renderedVoicePhase"))
        // Размер кнопки не трогаем — layout-параметры принадлежат каркасу композера.
        assertFalse(render.contains("layoutParams"))
        assertFalse(chatUi.contains("voiceBtn?.layoutParams"))
    }

    @Test
    fun `active button uses the accent colour with a constant outline`() {
        val renderer = source("src/main/java/ru/mars/mobile/VoiceMicButtonRenderer.kt")
        assertTrue(renderer.contains("MarsTheme.ACCENT"))
        assertTrue(renderer.contains("setStroke("))
        assertTrue(renderer.contains("MarsTheme.ACCENT_HOVER"))
        // Никаких пульсаций/анимаций: «постоянная обводка» — постоянная.
        assertFalse(renderer.contains("ObjectAnimator"))
        assertFalse(renderer.contains("ValueAnimator"))
        assertFalse(renderer.contains("animate()"))
    }

    @Test
    fun `tap and hold are decided by the gesture policy`() {
        val tap = body(chatUi, "ChatActivity.startVoiceInput")
        assertTrue(tap.contains("voiceGestures.onTap("))
        assertTrue(tap.contains("VoiceMicTapAction.STOP_SPEECH -> bargeIn()"))
        val hold = body(chatUi, "ChatActivity.toggleHandsFree")
        assertTrue(hold.contains("voiceGestures.onHold("))
        assertTrue(hold.contains("VoiceMicHoldAction.DISABLE_DIALOG"))
    }

    // --- 5. контракт с зонами Z1-Z3 -------------------------------------------------------------

    @Test
    fun `voice dialog flag in the request is explicit and backward compatible`() {
        // Фактическое поле сервера — `voice_mode:boolean`. `voice_dialog` из первой редакции
        // UI-контракта сервер не читает: ход молча уходил бы как текстовый.
        assertEquals("voice_mode", VoiceDialogRequestMode.FIELD)
        assertEquals("voice_dialog", VoiceDialogRequestMode.LEGACY_WRONG_FIELD)
        assertEquals(true, VoiceDialogRequestMode.fieldValue(voiceDialog = true))
        // Не голосовой ход — поля нет вовсе (сервер сохраняет свою политику).
        assertNull(VoiceDialogRequestMode.fieldValue(voiceDialog = false))
    }

    @Test
    fun `voice mode flag reaches the chat start payload`() {
        // Тело функции живёт в ChatTurnSection.kt (Activity разбит на секции-файлы).
        val turn = body(turnSection, "startSendStream")
        assertTrue(
            "в теле chat/start должно стоять фактическое поле voice_mode",
            turn.contains("voiceMode = VoiceDialogRequestMode.fieldValue(voiceTurn) == true"),
        )
        // Источник признака — состояние голосового цикла, а не «микрофон когда-то открывался».
        assertTrue(turn.contains("voiceTurnFromDialog()"))
        // Очередь/ручной повтор передают сохранённое намерение: пересчёт по циклу в момент
        // флаша дал бы `voice_mode: false` (цикл уже выключен) — тихую подмену режима.
        assertTrue(turn.contains("voiceTurnOverride ?: voiceTurnFromDialog()"))
        // Признак фиксируется ДО ожидания сессии: иначе выключение режима в момент создания
        // сессии сделало бы ход неголосовым.
        val fixAt = turn.indexOf("val voiceTurn = voiceTurnOverride ?: voiceTurnFromDialog()")
        val awaitAt = turn.indexOf("EnsureSessionResult")
        assertTrue("признак голоса должен фиксироваться до создания сессии", fixAt in 0 until awaitAt)
        // Имя поля — ровно то, что читает сервер.
        assertEquals("voice_mode", VoiceDialogRequestMode.FIELD)
    }

    @Test
    fun `unsupported voice backend rejection is explained and never silently downgraded`() {
        val turn = body(turnSection, "startSendStream")
        // Отказ разбирается политикой, а не общим путём «сообщение не дошло».
        assertTrue(turn.contains("VoiceModeRejectionPolicy"))
        assertTrue(turn.contains("FrameDecision.REJECT_VOICE_MODE"))
        // Человеку — понятный русский текст вместо сырого «HTTP 409: …».
        assertTrue(turn.contains("VoiceModeRejectionPolicy.NOTICE"))
        assertTrue(turn.contains("onVoiceModeRejectedTurn("))
        // Ход не уходит в очередь как «не дошло»: ветка отказа не вызывает onTurnFailed.
        val rejected = body(turnSection, "dispatchQueuedMessage")
        assertTrue(rejected.contains("onTurnRejected = {"))
        assertTrue(rejected.contains("offlineQueuePresenter.onVoiceModeRejected("))
        // Намерение из персистентной записи — до создания сессии.
        assertTrue(rejected.contains("voiceTurnOverride = queued.voiceMode"))
    }

    @Test
    fun `cross-zone contract is written down`() {
        val contract = File(repoRoot().parentFile, ".mars/voice-upgrade/UI-CONTRACT.md")
        assumeTrue("UI-CONTRACT.md вне репозитория — проверка только на этой машине", contract.isFile)
        val text = contract.readText()
        assertTrue(text.contains("/api/voice/interrupt"))
        assertTrue(text.contains("voiceInterruptHook"))
        assertTrue(text.contains("sendMessage"))
    }
}
