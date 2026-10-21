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
 * ## Перебивание: что зовёт UI-слой
 * Цикл микрофон не открывает и во время [State.SPEAKING] его НЕ закрывает: duplex-захват целиком
 * на хосте. Полный контракт (порядок важен):
 * 1. `capture.start(captureListener, rawPcm = { pcm, len -> stt.acceptPcm(pcm, len) })` — микрофон
 *    остаётся открытым и пока Марс говорит; на [onAnswerStarted] его не останавливать.
 * 2. `HandsFreeCapture.Listener.onSpeechStarted` → `loop.onSpeechStarted()`, а
 *    `onSpeechProgress(voicedMs)` → `loop.onSpeechProgress(voicedMs)` (оба через `runOnUiThread`).
 * 3. `loop.onAnswerStarted(spokenText)` — текст, который РЕАЛЬНО озвучивается. Без него на
 *    динамике перебивание невозможно по конструкции: эхо отличить не от чего.
 * 4. `Listener.onStopSpeaking()` → остановить TTS и очистить очередь реплик (локальная тишина).
 * 5. `Listener.onBargeIn(event)` → upstream-прерывание (сообщить серверу/шлюзу, что человек
 *    перебил). НЕ `cancelStream()`: ход должен дописаться в чат, иначе перебивание стоит
 *    пользователю ответа.
 * 6. `audioRoute = { monitor.route }` ([AndroidAudioRouteMonitor]) и
 *    `aecActive = { capture.echoCanceled }` — иначе политика всегда считает худший случай.
 * 7. `Listener.onAnswerFinished` вызывается хостом, когда озвучка кончилась: цикл выбрасывает
 *    монитор-реплику, но сохраняет её, если человек в этот момент ещё говорит.
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
 * ## Barge-in — только за флагом, только подтверждённый
 * [bargeInEnabled] по умолчанию `false`. Перебивание останавливает ТОЛЬКО воспроизведение
 * ([Listener.onStopSpeaking]) и сообщает хосту событие ([Listener.onBargeIn]) для upstream-прерывания:
 * ход на сервере продолжается, ответ дописывается в чат. Отмены стрима здесь нет и быть не может —
 * `cancelStream()` в [ChatActivity] убивает ход вместе с recovery-циклом, и перебивание голосом
 * не должно стоить пользователю ответа.
 *
 * Главная проблема: на Android микрофон слышит собственный динамик, и раньше цикл перебивал себя
 * на первом же слове ответа. Поэтому barge-in больше НЕ срабатывает на `SpeechStarted` — он лишь
 * открывает попытку, а подтверждение даёт [BargeInGate] по длительности речи и/или partial ASR:
 *
 * - на наушниках ([AudioRoute.HEADPHONES]) хватает короткой подтверждённой речи;
 * - на динамике нужен partial, слов которого нет в тексте ответа — длительность там ничего не
 *   доказывает, потому что TTS даёт ровно такую же непрерывную громкость. Текст ответа хост
 *   передаёт в [onAnswerStarted]; без него текстовое доказательство на динамике не принимается;
 * - `AcousticEchoCanceler` помогает (порог ниже), но НЕ решает: он ослабляет эхо, а не убирает.
 *
 * ## Duplex-захват во время ответа
 * Пока Марс говорит, цикл держит у [SpeechToText] ОТКРЫТУЮ «монитор-реплику» (только
 * [SttMode.STREAMING]): её partial идут в политику, а звук начала реплики человека не теряется.
 * Поэтому после подтверждённого перебивания реплику НЕ переоткрывают: движок уже слышит человека,
 * и его `Final` становится следующим ходом. Если ответ закончился, а человек так и не перебил,
 * монитор-реплика выбрасывается — иначе её финал отправил бы озвучку Марса как реплику человека
 * (кроме случая, когда человек в этот момент ещё говорит: тогда она сохраняется).
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
    /**
     * Политика подтверждения перебивания. Параметр нужен тестам и хосту, который хочет другие
     * пороги; по умолчанию — [BargeInConfig] с дефолтами.
     */
    private val bargeInGate: BargeInGate = BargeInGate(),
    /** Маршрут вывода: наушники/динамик. Спрашивается на старте каждого ответа. */
    private val audioRoute: () -> AudioRoute = { AudioRoute.UNKNOWN },
    /** Аппаратный AEC реально включён (см. [HandsFreeCapture.echoCanceled]). */
    private val aecActive: () -> Boolean = { false },
    /** Часы в миллисекундах: подменяются в тестах, чтобы окно эхо-успокоения не ждали реально. */
    private val nowMs: () -> Long = System::currentTimeMillis,
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
        fun onStopSpeaking()

        /**
         * Перебивание ПОДТВЕРЖДЕНО (человек действительно говорит, а не эхо динамика).
         *
         * Отдельный колбэк от [onStopSpeaking] нужен upstream-прерыванию: локальная тишина — это
         * `SpeechQueue`/TTS, а сервер (SSE/voice gateway) узнаёт о перебивании только отсюда.
         * Ход НЕ отменяется: [ChatActivity.cancelStream] убил бы ответ вместе с recovery.
         */
        fun onBargeIn(event: BargeInEvent) {}

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

    /** Сколько раз сегментер услышал начало речи во время ответа (кандидаты, не перебивания). */
    var bargeInCandidates: Int = 0
        private set

    /** Сколько перебиваний подтверждено и обработано. */
    var bargeIns: Int = 0
        private set

    /** Чем подтвердилось последнее перебивание; `null` — перебиваний не было. */
    var lastBargeInEvidence: BargeInEvidence? = null
        private set

    /** Сколько `Final` отброшено как эхо озвучки (движок услышал собственный динамик). */
    var echoFinalsDropped: Int = 0
        private set

    /** Сколько сегментов на динамике не подтвердилось: текста нет, длительность ничего не доказывает. */
    var bargeInRejected: Int = 0
        private set

    /**
     * Реплика у [SpeechToText] открыта циклом ради прослушивания ответа (duplex-монитор), а не
     * ради обычного ввода. Нужна, чтобы отбросить её финал как эхо, если человек так и не перебил.
     */
    private var monitorUtterance = false

    /** Текст, который озвучивается прямо сейчас: по нему [BargeInGate] отличает эхо от речи. */
    private var answerSpokenText: String? = null

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
        bargeInCandidates = 0
        bargeIns = 0
        lastBargeInEvidence = null
        echoFinalsDropped = 0
        bargeInRejected = 0
        answerSpokenText = null
        monitorUtterance = false
        bargeInGate.reset()
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
     * Сегментер услышал начало речи. В [State.SPEAKING] это только КАНДИДАТ на перебивание:
     * политика ([BargeInGate]) подтвердит его длительностью речи и/или partial ASR. Одного
     * `SpeechStarted` недостаточно — ровно так же звучит собственный динамик.
     *
     * @return true, если озвучка была перебита немедленно (для наушников возможно, если политика
     *   уже видела достаточно речи). Обычно `false`: подтверждение придёт в [onSpeechProgress]
     *   или в [SpeechToText.Events.onPartial].
     */
    fun onSpeechStarted(): Boolean {
        if (!started) return false
        if (state != State.SPEAKING) return false
        if (!bargeInEnabled) return false
        bargeInCandidates++
        bargeInGate.onSpeechStarted(nowMs())
        // Даже принятая попытка — ещё не перебивание: нужен порог длительности или текст.
        return false
    }

    /**
     * Сегментер сообщил, сколько речи уже накоплено (тикает с потока чтения микрофона). Именно
     * здесь срабатывает barge-in по длительности, а не в конце фразы.
     *
     * `voicedMs == 0` — речь закончилась: попытка сбрасывается, чтобы пауза между всплесками эха
     * не превращалась в «человек говорит уже три секунды». Счётчик перебиваний при этом цел.
     *
     * @return true, если перебивание подтверждено этим тиком.
     */
    fun onSpeechProgress(voicedMs: Long): Boolean {
        if (!started) return false
        if (state != State.SPEAKING) return false
        if (!bargeInEnabled) return false
        if (voicedMs <= 0L) {
            bargeInGate.onSpeechEnded()
            return false
        }
        val decision = bargeInGate.onVoiced(voicedMs, nowMs())
        if (decision !is BargeInDecision.Trigger) return false
        confirmBargeIn(decision)
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
                // Whisper-путь: текста ещё нет, есть только длительность. На наушниках этого
                // достаточно; на динамике — нет, и сегмент не отправляем (иначе озвучка Марса
                // вернулась бы в чат как реплика человека).
                val decision = bargeInGate.onSegment(recording.durationMs, nowMs())
                if (decision !is BargeInDecision.Trigger) {
                    bargeInRejected++
                    droppedSegments++
                    listener.onSegmentDropped()
                    return false
                }
                confirmBargeIn(decision)
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

    /**
     * Ответ пришёл и начал звучать: цикл в [State.SPEAKING].
     *
     * @param spokenText текст, который озвучивается. Нужен политике перебивания: partial ASR,
     *   слова которого есть в этом тексте, — эхо динамика, а не речь человека. Без него на
     *   динамике текстовое доказательство не принимается вовсе (на наушниках не нужно).
     */
    fun onAnswerStarted(spokenText: String? = null): Boolean {
        if (!started) return false
        if (state != State.THINKING && state != State.LISTENING) return false
        awaitingAnswer = false
        answerSpokenText = spokenText
        bargeInGate.onAnswerStarted(nowMs(), audioRoute(), aecActive(), spokenText)
        transition(State.SPEAKING)
        openMonitorUtterance()
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
        // Человек ещё говорит, но не перебил (например, тихо или на динамике без текста): реплику
        // сохраняем, иначе его слова потеряются на границе ответа.
        val keepUtterance = bargeInEnabled && bargeInGate.isCandidate
        bargeInGate.onAnswerFinished()
        transition(State.LISTENING)
        if (sttMode == SttMode.STREAMING && utteranceOpen && !keepUtterance) {
            // Монитор-реплика копила эхо озвучки: её `Final` отправил бы ответ Марса как реплику
            // человека. Закрываем без текста и открываем чистую реплику для ввода.
            discardUtterance()
        }
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
        answerSpokenText = null
        monitorUtterance = false
        bargeInGate.reset()
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
        // Вторая реплика поверх первой потеряла бы уже принятый звук и дала бы два финала.
        if (utteranceOpen) return
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
     * Duplex-захват: пока Марс говорит, реплика у движка ОТКРЫТА. Это даёт partial ASR для
     * подтверждения перебивания и сохраняет первые слова человека, если он всё-таки перебьёт.
     * Только потоковый путь: whisper режет по тишине и во время озвучки слышит её.
     */
    private fun openMonitorUtterance() {
        if (!bargeInEnabled || sttMode != SttMode.STREAMING) return
        if (utteranceOpen) return
        beginUtterance()
        if (utteranceOpen) monitorUtterance = true
    }

    /** Выбросить реплику без текста (эхо озвучки) и приготовиться слушать дальше. */
    private fun discardUtterance() {
        utteranceOpen = false
        monitorUtterance = false
        partialTranscript = null
        runCatching { stt?.cancelUtterance() }
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
        val clean = text.trim()
        if (clean.isEmpty()) return
        when (state) {
            State.LISTENING -> {
                partialTranscript = clean
                listener.onPartialTranscript(clean)
            }
            State.SPEAKING -> {
                // Монитор-реплика слышит и Марса, и человека: показывать этот partial нельзя,
                // но именно он (если слов нет в тексте ответа) подтверждает перебивание.
                if (!bargeInEnabled) return
                val decision = bargeInGate.onPartial(clean, nowMs())
                if (decision is BargeInDecision.Trigger) confirmBargeIn(decision)
            }
            else -> Unit
        }
    }

    /**
     * `Final` от движка — это endpoint: реплика закончена, ход уходит в чат. Поздний или
     * повторный финал отбрасывается: реплика уже закрыта ([utteranceOpen] == false) или цикл
     * занят ходом.
     *
     * Во время ответа финал может быть двух видов, и оба важны:
     * - эхо озвучки (слова есть в тексте ответа) — выбрасываем и открываем чистую реплику;
     * - речь человека, которую движок успел распознать целиком, — это перебивание по факту
     *   (`Final` = сильнейшее доказательство), поэтому останавливаем озвучку и отправляем текст
     *   один раз.
     */
    private fun onSttFinal(text: String) {
        if (!started || sttMode != SttMode.STREAMING || !utteranceOpen) return
        when (state) {
            State.LISTENING -> {
                val clean = text.trim()
                if (monitorUtterance && clean.isNotEmpty() && bargeInGate.isEchoOfAssistant(clean)) {
                    echoFinalsDropped++
                    discardUtterance()
                    beginUtterance()
                    return
                }
                completeUtterance(text)
            }
            State.SPEAKING -> onFinalWhileSpeaking(text)
            else -> Unit
        }
    }

    /**
     * `Final` во время озвучки. Разделяем эхо и речь человека: эхо — в мусор, речь — в новый ход
     * (и озвучку останавливаем: человек уже договорил, слушать больше нечего).
     */
    private fun onFinalWhileSpeaking(raw: String) {
        if (!bargeInEnabled) return
        val clean = raw.trim()
        if (clean.isEmpty() || bargeInGate.isEchoOfAssistant(clean)) {
            echoFinalsDropped++
            // Реплика остаётся монитором: человек может заговорить в ту же секунду.
            discardUtterance()
            beginUtterance()
            monitorUtterance = utteranceOpen
            return
        }
        // Текст есть — это перебивание независимо от длительности: движок услышал фразу целиком.
        confirmBargeIn(
            BargeInDecision.Trigger(BargeInEvidence.TRANSCRIPT, 0L, bargeInGate.route),
        )
        completeUtterance(clean)
    }

    /**
     * Перебивание подтверждено политикой: локальная тишина немедленно, событие — хосту для
     * upstream-прерывания. Ход НЕ отменяется.
     *
     * Реплику у движка НЕ закрываем: в ней уже звук человека, и её `Final` станет следующим ходом.
     */
    private fun confirmBargeIn(decision: BargeInDecision.Trigger) {
        bargeInGate.onAccepted()
        bargeIns++
        lastBargeInEvidence = decision.evidence
        listener.onStopSpeaking()
        listener.onBargeIn(
            BargeInEvent(
                evidence = decision.evidence,
                voicedMs = decision.voicedMs,
                route = decision.route,
                aecActive = bargeInGate.aecActive,
                partial = bargeInGate.lastPartial,
            ),
        )
        awaitingAnswer = false
        transition(State.LISTENING)
        // В потоковом режиме реплика уже открыта — beginUtterance не откроет вторую.
        resumeListening()
    }

    /**
     * Закрыть реплику и отправить её текст. Один путь для `Final` и для ручного стопа, поэтому
     * дубля быть не может: после первого вызова [utteranceOpen] == false.
     */
    private fun completeUtterance(raw: String): Boolean {
        utteranceOpen = false
        monitorUtterance = false
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
