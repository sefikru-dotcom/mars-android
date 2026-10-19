package ru.mars.mobile

/**
 * Состояния голосового диалога — те же четыре, что в WebUI (`static/boot.js:1590-1672`):
 * `idle → listening → thinking → speaking → listening`.
 *
 * ```
 * IDLE ──start()──▶ LISTENING ──сегмент──▶ THINKING ──ответ──▶ SPEAKING ──▶ LISTENING
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
 * ## Защита от дубля (по конструкции)
 * - у каждого запроса распознавания есть [Long]-идентификатор; колбэк с чужим/старым id
 *   игнорируется, поэтому поздний или повторно доставленный ответ STT не может отправить
 *   второе сообщение;
 * - в [State.THINKING] новый сегмент не запускает второе распознавание;
 * - `send` и `steer` — РАЗНЫЕ режимы ([SendMode]): пока ход идёт (`turnActive`), текст уходит
 *   steer-ом, а не вторым сообщением, поэтому задвоения нет по конструкции, а не по таймауту.
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
 */
class VoiceDialogLoop(
    private val listener: Listener,
    /** L3: перебивание озвучки голосом. Выключено по умолчанию — см. KDoc класса. */
    private val bargeInEnabled: Boolean = false,
) {

    /** Состояния цикла: ровно те, что показывает WebUI. */
    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    /** Как отправлять распознанный текст: новым сообщением или направлением в живой ход. */
    enum class SendMode { SEND, STEER }

    /** Побочные эффекты хоста. Решения принимает цикл, выполняет — хост. */
    interface Listener {
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

    private var requestSeq: Long = 0L

    /** id распознавания, ответ которого цикл ещё ждёт; чужой id — устаревший колбэк. */
    private var activeRequestId: Long? = null

    /** Цикл запущен пользователем (иначе сегменты игнорируются). */
    private var started = false

    /**
     * Начать слушать. Из [State.IDLE] — открывает цикл; из любого другого состояния — `false`
     * (второй цикл поверх первого не запускается).
     */
    fun start(): Boolean {
        if (started) return false
        started = true
        activeRequestId = null
        awaitingAnswer = false
        pendingSegment = null
        droppedSegments = 0
        sendRequests = 0
        transition(State.LISTENING)
        return true
    }

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
        dispatchPending()
        return true
    }

    /**
     * Пришёл готовый сегмент речи. Принимается в [State.LISTENING]; в [State.THINKING] ждёт в
     * единственном слоте; в [State.SPEAKING] — только при включённом barge-in.
     *
     * @return true, если сегмент сразу ушёл в распознавание.
     */
    fun onSegment(recording: Recording): Boolean {
        if (!started) {
            droppedSegments++
            listener.onSegmentDropped()
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
        dispatchPending()
        return true
    }

    /** Выключить цикл: микрофон закрывает хост, ожидающая реплика сбрасывается. */
    fun stop() {
        started = false
        activeRequestId = null
        awaitingAnswer = false
        pendingSegment = null
        if (state != State.IDLE) transition(State.IDLE)
    }

    /** Слушает ли цикл прямо сейчас (микрофон может быть открыт, но цикл уже выключен). */
    val isActive: Boolean get() = started

    // --- внутреннее ---------------------------------------------------------------------------

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
