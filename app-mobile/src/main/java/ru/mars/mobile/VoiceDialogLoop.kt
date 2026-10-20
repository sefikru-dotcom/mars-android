package ru.mars.mobile

/**
 * Состояния голосового диалога — те же четыре, что в WebUI (`static/boot.js:1590-1672`):
 * `idle → listening → thinking → speaking → listening`.
 *
 * ```
 * IDLE ──start()──▶ LISTENING ──реплика──▶ THINKING ──ответ──▶ SPEAKING ──▶ LISTENING
 *   ▲                   ▲                      │                  │            │
 *   │                   └─────ошибка STT───────┘                  │            │
 *   └────stop()───────────────────────────────────────────────────┴────────────┘
 * ```
 *
 * Зачем отдельный класс: у голосового ВХОДА уже есть [VoiceSessionController] (одна запись по
 * кнопке), у ВЫХОДА — [VoiceOutputController] (что и когда читать). Не хватало третьего: политики
 * ЦИКЛА, которая связывает их и решает, когда микрофон снова слушает, а когда реплику нельзя
 * принимать. Если оставить это в Activity, то же самое придётся писать для TV и для любого
 * будущего экрана, а проверять — только руками на устройстве.
 *
 * Чистый JVM: ни одного импорта Android. Хост выполняет побочные эффекты (сеть, UI), цикл
 * принимает решения — поэтому все переходы, включая защиту от дубля и потери реплики, проверены
 * юнит-тестами без устройства.
 *
 * ## Два источника текста ([SttMode])
 * Цикл не знает, чем распознают речь: он зависит от шва [SpeechToText].
 *
 * - [SttMode.STREAMING] — текст приходит событиями [SpeechToText.Events]: движок на устройстве
 *   ([StreamingStt]) сам определяет конец реплики и отдаёт `Final` за десятки миллисекунд, поэтому
 *   [VoiceSegmenter] на этом пути не нужен, а [Listener.onTranscribeRequested] не вызывается
 *   вовсе. Сегменты, которые всё равно приходят из [HandsFreeCapture] (они нужны для fallback),
 *   здесь ИГНОРИРУЮТСЯ — иначе та же фраза ушла бы вторым распознаванием.
 * - [SttMode.SEGMENTS] — старый путь: сегмент → [Listener.onTranscribeRequested] →
 *   `POST /api/transcribe` → [onTranscript]. Он остаётся, пока движка нет ([stt] == null) или он
 *   не готов, и это единственное, что видит сегодняшний ChatActivity.
 *
 * Деградация плавная и явная: если движок не готов на [start] или отвалился посреди сессии
 * ([SpeechToText.Events.onFailed]), цикл переключается на [SttMode.SEGMENTS] и сообщает хосту
 * причину через [Listener.onSttFallback] — хост пишет её в лог. Никаких исключений и «немого»
 * микрофона.
 *
 * ## Подключение потокового пути (что делает хост)
 * Цикл ничего не открывает сам — микрофон, движок и перекладывание потоков остаются у хоста:
 * 1. `stt = StreamingStt(port)` (движок не готов — `isReady` == false, цикл сам уйдёт на whisper);
 * 2. `voiceLoop.start()` ДО открытия микрофона — цикл открывает первую реплику;
 * 3. `capture.start(listener, rawPcm = { pcm, len -> stt.acceptPcm(pcm, len) })` — сырой поток
 *    идёт в движок с потока чтения, сегментер продолжает работать для fallback;
 * 4. события [SpeechToText] и [HandsFreeCapture] перекладываются на главный поток
 *    (`runOnUiThread`), как и раньше;
 * 5. ручной стоп — [stopUtterance]; выключение режима — [stop] (реплика выбрасывается);
 * 6. после `engine.load()` — [refreshSttMode], чтобы переключиться на движок без перезапуска.
 *
 * Всё, что не готово, деградирует к шагу 3 старого пути: `stt == null` — ровно прежнее поведение.
 *
 * ## Защита от дубля (по конструкции)
 * - у каждого запроса распознавания есть [Long]-идентификатор; колбэк с чужим/старым id
 *   игнорируется, поэтому поздний или повторно доставленный ответ STT не может отправить
 *   второе сообщение;
 * - в [State.THINKING] новый сегмент не запускает второе распознавание;
 * - в [SttMode.STREAMING] ход отправляется ТОЛЬКО по `Final` (или по ручному стопу), и только
 *   пока реплика открыта: `Final`, пришедший после отправки (поздний, дублирующий, от прошлой
 *   реплики), отбрасывается флагом [utteranceOpen], а не таймаутом;
 * - `send` и `steer` — РАЗНЫЕ режимы ([SendMode]): пока ход идёт, текст уходит steer-ом, а не
 *   вторым сообщением, поэтому задвоения нет по конструкции, а не по таймауту.
 *
 * ## Защита от потери реплики
 * Сегмент, пришедший во время распознавания/ожидания ответа, не выбрасывается: он ждёт в
 * единственном слоте [pendingSegment] и уходит в распознавание, как только цикл снова слушает.
 * Слот один: третья реплика поверх двух не копится (иначе человек получит очередь из старых
 * фраз), но и первая не теряется.
 *
 * ## Barge-in (L3) — только за флагом
 * [bargeInEnabled] по умолчанию `false`. Перебивание останавливает ТОЛЬКО воспроизведение
 * ([Listener.onStopSpeaking]): ход на сервере продолжается, ответ дописывается в чат. Отмены
 * стрима здесь нет и быть не может — `cancelStream()` в [ChatActivity] убивает ход вместе с
 * recovery-циклом, и перебивание голосом не должно стоить пользователю ответа.
 *
 * ВАЖНО (почему флаг выключен): на Android микрофон слышит собственный динамик. Без
 * `AcousticEchoCanceler` и `AudioSource.VOICE_COMMUNICATION` TTS попадает в микрофон, сегментер
 * видит «речь» и цикл перебивает сам себя на первом же слове ответа. Включать только после
 * замеров на живом устройстве (POCO F5) с AEC.
 *
 * ## Потоки
 * Цикл однопоточный: все методы зовёт хост с главного потока. События [SpeechToText] приходят с
 * потока движка — хост обязан переложить их на главный поток, как и колбэки [HandsFreeCapture].
 *
 * @param stt источник текста, управляемый движком (например [StreamingStt]). `null` — старый
 *   whisper-путь через [Listener.onTranscribeRequested], поведение цикла не меняется.
 * @param turnActive ход уже идёт (в UI это TYPING)? Тогда текст уходит [SendMode.STEER], а не
 *   вторым сообщением. Спрашивается только в [SttMode.STREAMING]: в старом пути хост сам передаёт
 *   `turnActive` в [onTranscript].
 */
class VoiceDialogLoop(
    private val listener: Listener,
    /** L3: перебивание озвучки голосом. Выключено по умолчанию — см. KDoc класса. */
    private val bargeInEnabled: Boolean = false,
    /** Источник текста от движка; `null` — whisper-путь через [Listener.onTranscribeRequested]. */
    private val stt: SpeechToText? = null,
    /** Идёт ли сейчас ход: определяет `send` против `steer` в потоковом режиме. */
    private val turnActive: () -> Boolean = { false },
) {

    /** Состояния цикла: ровно те, что показывает WebUI. */
    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    /** Как отправлять распознанный текст: новым сообщением или направлением в живой ход. */
    enum class SendMode { SEND, STEER }

    /**
     * Кто владеет текстом реплики.
     *
     * - [STREAMING] — [SpeechToText] (движок на устройстве или [WhisperStt]); конец реплики
     *   определяет он, цикл отправляет ход по [SpeechToText.Events.onFinal];
     * - [SEGMENTS] — цикл: сегмент из [HandsFreeCapture] уходит хосту в
     *   [Listener.onTranscribeRequested], текст возвращается в [onTranscript].
     */
    enum class SttMode { STREAMING, SEGMENTS }

    /** Побочные эффекты хоста. Решения принимает цикл, выполняет — хост. */
    interface Listener {
        /** Вызывается при каждой СМЕНЕ состояния (повтор того же состояния не приходит). */
        fun onStateChanged(state: State) {}

        /** Пора распознать сегмент: [requestId] вернуть в [onTranscript]/[onTranscriptFailed]. */
        fun onTranscribeRequested(requestId: Long, recording: Recording) {}

        /**
         * Отправить текст. [SendMode.STEER] — ход уже идёт (TYPING): steer не создаёт второе
         * сообщение, поэтому дубля не будет даже при быстрой второй реплике.
         */
        fun onSendRequested(requestId: Long, text: String, mode: SendMode) {}

        /** Barge-in: немедленная тишина. Ход НЕ отменяется. */
        fun onStopSpeaking() {}

        /** Сегмент отброшен (цикл не слушает или слот занят) — для диагностики/статуса. */
        fun onSegmentDropped() {}

        /**
         * Живой транскрипт (потоковый режим): хост может показать текст прямо в композере.
         * Приходит на каждое уточнение движка, поэтому дёшево и без анимаций.
         */
        fun onPartialTranscript(text: String) {}

        /**
         * Распознавание ушло на whisper-путь. [reason] — готовая строка для лога (движок не
         * загружен, упал, отдал ошибку). Хост логирует её и продолжает диалог как раньше.
         */
        fun onSttFallback(reason: String) {}
    }

    var state: State = State.IDLE
        private set

    /** true между отправкой текста и завершением ответа: цикл занят ходом. */
    var awaitingAnswer: Boolean = false
        private set

    /** Реплика, дождавшаяся своей очереди (единственный слот). */
    var pendingSegment: Recording? = null
        private set

    /** Сколько реплик отброшено из-за занятости — метрика, а не очередь. */
    var droppedSegments: Int = 0
        private set

    /** Сколько сообщений/направлений цикл попросил отправить (для тестов и диагностики). */
    var sendRequests: Int = 0
        private set

    /** Кто владеет текстом прямо сейчас — для логов хоста и тестов. */
    var sttMode: SttMode = SttMode.SEGMENTS
        private set

    /** Последний живой транскрипт (потоковый режим); `null` — реплика не начата. */
    var partialTranscript: String? = null
        private set

    /** Сколько сегментов проигнорировано в потоковом режиме (их звук уже ушёл в движок). */
    var streamingSegmentsIgnored: Int = 0
        private set

    /** Сколько реплик цикл открыл у [SpeechToText] (для тестов и диагностики). */
    var startedUtterances: Int = 0
        private set

    private var requestSeq: Long = 0L

    /** id распознавания, ответ которого цикл ещё ждёт; чужой id — устаревший колбэк. */
    private var activeRequestId: Long? = null

    /** Цикл запущен пользователем (иначе сегменты игнорируются). */
    private var started = false

    /**
     * Реплика открыта у [SpeechToText]: только в этом окне принимается `Final`. Именно этот флаг,
     * а не таймаут, защищает от двойной отправки — поздний финал после ручного стопа отбрасывается.
     */
    private var utteranceOpen = false

    /** Сообщение о деградации уже отдано хосту — чтобы лог не повторялся на каждую фразу. */
    private var fallbackReported = false

    /** События движка → цикл. Один объект: реализация STT держит его, пока жив цикл. */
    private val sttEvents = object : SpeechToText.Events {
        override fun onSpeechStarted() {
            // Именно внешний метод: без квалификатора вызов ушёл бы в этот же объект (рекурсия).
            this@VoiceDialogLoop.onSpeechStarted()
        }

        override fun onPartial(text: String) {
            onSttPartial(text)
        }

        override fun onFinal(text: String) {
            onSttFinal(text)
        }

        override fun onFailed(reason: String) {
            degradeToWhisper("${stt?.id ?: "stt"}: $reason")
        }
    }

    /**
     * Начать слушать. Из [State.IDLE] — открывает цикл; из любого другого состояния — `false`
     * (второй цикл поверх первого не запускается).
     *
     * В потоковом режиме здесь же открывается первая реплика у [SpeechToText]. Хост обязан
     * вызвать [start] ДО открытия микрофона (как в ChatActivity): иначе первые буферы PCM
     * придут в закрытую реплику и будут отброшены.
     */
    fun start(): Boolean {
        if (started) return false
        started = true
        activeRequestId = null
        awaitingAnswer = false
        pendingSegment = null
        droppedSegments = 0
        sendRequests = 0
        streamingSegmentsIgnored = 0
        startedUtterances = 0
        partialTranscript = null
        utteranceOpen = false
        fallbackReported = false
        resolveMode()
        transition(State.LISTENING)
        beginUtterance()
        return true
    }

    /**
     * Пере-проверить готовность движка (модель могла догрузиться после [start]). Возвращает
     * режим, в котором цикл будет работать дальше. Пока реплика открыта или идёт ход, режим не
     * меняется: переключать источник текста посреди фразы нельзя.
     */
    fun refreshSttMode(): SttMode {
        if (!started || utteranceOpen || state != State.LISTENING) return sttMode
        val before = sttMode
        resolveMode()
        if (before != SttMode.STREAMING && sttMode == SttMode.STREAMING) beginUtterance()
        return sttMode
    }

    /** Работает ли цикл по потоковому пути (движок/STT сам детектит конец реплики). */
    val usesStreaming: Boolean get() = sttMode == SttMode.STREAMING

    /**
     * Сегментер услышал начало речи. В [State.SPEAKING] это barge-in: с флагом —
     * останавливаем озвучку и возвращаемся в слушание; без флага — не мешаем ответу.
     *
     * @return true, если озвучка была перебита.
     */
    fun onSpeechStarted(): Boolean {
        if (!started) return false
        if (state != State.SPEAKING) return false
        if (!bargeInEnabled) return false
        listener.onStopSpeaking()
        awaitingAnswer = false
        transition(State.LISTENING)
        resumeListening()
        return true
    }

    /**
     * Пришёл готовый сегмент речи. Принимается в [State.LISTENING]; в [State.THINKING] ждёт в
     * единственном слоте; в [State.SPEAKING] — только при включённом barge-in.
     *
     * В [SttMode.STREAMING] сегменты НЕ распознаются: их звук уже ушёл в движок потоком, и
     * второй запрос к whisper отправил бы ту же фразу второй раз. Сегмент считается
     * проигнорированным ([streamingSegmentsIgnored]) и хосту не показывается.
     *
     * @return true, если сегмент сразу ушёл в распознавание.
     */
    fun onSegment(recording: Recording): Boolean {
        if (!started) {
            droppedSegments++
            listener.onSegmentDropped()
            return false
        }
        if (sttMode == SttMode.STREAMING) {
            // Звук этой фразы движок уже получил по [SpeechToText.acceptPcm].
            streamingSegmentsIgnored++
            return false
        }
        when (state) {
            State.IDLE -> {
                droppedSegments++
                listener.onSegmentDropped()
                return false
            }
            State.LISTENING -> return dispatch(recording)
            State.THINKING -> {
                if (pendingSegment == null) {
                    pendingSegment = recording
                } else {
                    droppedSegments++
                    listener.onSegmentDropped()
                }
                return false
            }
            State.SPEAKING -> {
                if (!bargeInEnabled) {
                    droppedSegments++
                    listener.onSegmentDropped()
                    return false
                }
                listener.onStopSpeaking()
                awaitingAnswer = false
                transition(State.LISTENING)
                return dispatch(recording)
            }
        }
    }

    /**
     * Распознанный текст. Принимается только для [requestId] активного запроса: повторная
     * доставка того же ответа (retry, двойной колбэк) и поздний ответ прошлого хода
     * игнорируются — второго сообщения не будет.
     *
     * В [SttMode.STREAMING] активного запроса распознавания нет (ход отправляется по `Final`),
     * поэтому любой такой колбэк — устаревший и отбрасывается.
     *
     * @param turnActive ход уже идёт (в UI это TYPING): текст уходит [SendMode.STEER].
     * @return true, если текст отправлен.
     */
    fun onTranscript(requestId: Long, text: String, turnActive: Boolean): Boolean {
        if (!started) return false
        if (requestId != activeRequestId) return false
        activeRequestId = null
        val clean = text.trim()
        if (clean.isEmpty()) {
            // Пустой результат — не повод рвать цикл: снова слушаем (и забираем ожидающую реплику).
            transition(State.LISTENING)
            dispatchPending()
            return false
        }
        val mode = if (turnActive) SendMode.STEER else SendMode.SEND
        awaitingAnswer = true
        sendRequests++
        listener.onSendRequested(requestId, clean, mode)
        return true
    }

    /** Распознавание не удалось (сеть/400/503): цикл остаётся живым и снова слушает. */
    fun onTranscriptFailed(requestId: Long): Boolean {
        if (!started) return false
        if (requestId != activeRequestId) return false
        activeRequestId = null
        transition(State.LISTENING)
        dispatchPending()
        return true
    }

    /**
     * Ручной стоп (человек нажал «отправить»/«хватит»): реплика закрывается принудительно, даже
     * если endpoint не сработал. Работает только в [SttMode.STREAMING] — в старом пути реплику
     * закрывает [HandsFreeCapture.stop], и текст приходит в [onTranscript].
     *
     * @return true, если текст распознан и отправлен. `false` — распознавать нечего, реплика не
     *   открыта, или текст ещё в пути (тогда ход уйдёт по `Final`, как только он придёт).
     */
    fun stopUtterance(): Boolean {
        if (!started || sttMode != SttMode.STREAMING) return false
        if (!utteranceOpen) return false
        val target = stt ?: return false
        val text = target.finishUtterance()
        if (text == null) {
            // Асинхронный путь ([WhisperStt]): реплика остаётся открытой, ход отправит `Final`.
            return false
        }
        return completeUtterance(text)
    }

    /** Ответ пришёл и начал звучать: цикл в [State.SPEAKING], микрофон не перехватывает его. */
    fun onAnswerStarted(): Boolean {
        if (!started) return false
        if (state != State.THINKING && state != State.LISTENING) return false
        awaitingAnswer = false
        transition(State.SPEAKING)
        return true
    }

    /**
     * Ответ закончился (TTS дочитал, или озвучка недоступна, или ход упал). Hands-free: снова
     * слушаем — и сразу забираем реплику, если человек говорил, пока Марс отвечал.
     */
    fun onAnswerFinished(): Boolean {
        if (!started) return false
        if (state != State.SPEAKING && state != State.THINKING) return false
        awaitingAnswer = false
        transition(State.LISTENING)
        resumeListening()
        return true
    }

    /** Выключить цикл: микрофон закрывает хост, ожидающая реплика сбрасывается. */
    fun stop() {
        started = false
        activeRequestId = null
        awaitingAnswer = false
        pendingSegment = null
        partialTranscript = null
        if (utteranceOpen) {
            utteranceOpen = false
            // Недоговорённую реплику не отправляем: человек выключил микрофон.
            runCatching { stt?.cancelUtterance() }
        }
        if (state != State.IDLE) transition(State.IDLE)
    }

    /** Слушает ли цикл прямо сейчас (микрофон может быть открыт, но цикл уже выключен). */
    val isActive: Boolean get() = started

    // --- внутреннее ---------------------------------------------------------------------------

    /**
     * Определить режим по готовности [SpeechToText]. Побочный эффект — подписка на события и
     * ОДНО сообщение хосту о деградации (только при смене режима, иначе лог заспамится).
     */
    private fun resolveMode() {
        val target = stt
        if (target != null) target.events = sttEvents
        val next = if (target != null && target.isReady) SttMode.STREAMING else SttMode.SEGMENTS
        sttMode = next
        if (next == SttMode.SEGMENTS && target != null && !fallbackReported) {
            fallbackReported = true
            listener.onSttFallback("${target.id}: движок не готов — распознавание через whisper")
        }
    }

    /** Открыть реплику у [SpeechToText] (только в потоковом режиме и только когда слушаем). */
    private fun beginUtterance() {
        if (!started || sttMode != SttMode.STREAMING) return
        val target = stt ?: return
        if (!target.isReady) {
            degradeToWhisper("${target.id}: движок перестал быть готовым")
            return
        }
        utteranceOpen = true
        startedUtterances++
        target.startUtterance()
    }

    /**
     * Снова слушаем после паузы: в потоковом режиме открываем новую реплику, в старом — забираем
     * ожидающий сегмент.
     */
    private fun resumeListening() {
        if (sttMode == SttMode.STREAMING) beginUtterance() else dispatchPending()
    }

    /** Плавная деградация: дальше по сессии распознаём сегментами через whisper. */
    private fun degradeToWhisper(reason: String) {
        if (sttMode == SttMode.SEGMENTS) return
        sttMode = SttMode.SEGMENTS
        partialTranscript = null
        fallbackReported = true
        val wasOpen = utteranceOpen
        utteranceOpen = false
        if (wasOpen) runCatching { stt?.cancelUtterance() }
        listener.onSttFallback(reason)
        // Идущий ход и озвучку не трогаем: сегменты подождут в pendingSegment.
        if (state != State.LISTENING) return
        dispatchPending()
    }

    /** Промежуточный результат движка: только пока реплика открыта и мы действительно слушаем. */
    private fun onSttPartial(text: String) {
        if (!started || sttMode != SttMode.STREAMING || !utteranceOpen) return
        if (state != State.LISTENING) return
        val clean = text.trim()
        if (clean.isEmpty()) return
        partialTranscript = clean
        listener.onPartialTranscript(clean)
    }

    /**
     * `Final` от движка — это endpoint: реплика закончена, ход уходит в чат. Поздний или
     * повторный финал отбрасывается: реплика уже закрыта ([utteranceOpen] == false) или цикл
     * занят ходом.
     */
    private fun onSttFinal(text: String) {
        if (!started || sttMode != SttMode.STREAMING || !utteranceOpen) return
        if (state != State.LISTENING) return
        completeUtterance(text)
    }

    /**
     * Закрыть реплику и отправить её текст. Один путь для `Final` и для ручного стопа, поэтому
     * дубля быть не может: после первого вызова [utteranceOpen] == false.
     */
    private fun completeUtterance(raw: String): Boolean {
        utteranceOpen = false
        partialTranscript = null
        val clean = raw.trim()
        if (clean.isEmpty()) {
            transition(State.LISTENING)
            resumeListening()
            return false
        }
        val mode = if (turnActive()) SendMode.STEER else SendMode.SEND
        requestSeq++
        awaitingAnswer = true
        sendRequests++
        transition(State.THINKING)
        listener.onSendRequested(requestSeq, clean, mode)
        return true
    }

    private fun dispatch(recording: Recording): Boolean {
        requestSeq++
        activeRequestId = requestSeq
        transition(State.THINKING)
        listener.onTranscribeRequested(requestSeq, recording)
        return true
    }

    /** Забрать ожидающую реплику, как только цикл снова свободен (не более одной за раз). */
    private fun dispatchPending() {
        if (!started || state != State.LISTENING) return
        val waiting = pendingSegment ?: return
        pendingSegment = null
        dispatch(waiting)
    }

    private fun transition(next: State) {
        if (state == next) return
        state = next
        listener.onStateChanged(next)
    }
}
