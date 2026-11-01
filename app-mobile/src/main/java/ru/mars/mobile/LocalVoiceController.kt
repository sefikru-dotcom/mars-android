package ru.mars.mobile

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import ru.mars.mobile.service.MarsVoiceService

/**
 * Голосовой ввод локального чата: микрофон → существующий ASR → текст в композер, и
 * ответ → [LocalVoiceTts]. Второго движка распознавания здесь нет и быть не может.
 *
 * ## Из чего собран путь «микрофон → текст»
 * Все звенья уже существуют, новые — только проводка и фазы:
 * - [HandsFreeCapture] открывает микрофон и отдаёт СЫРОЙ PCM. Он же исполняет
 *   [AudioCapturePolicy] (`VOICE_COMMUNICATION` + AEC/NS, откат на `MIC`) — своей
 *   маршрутизации захвата здесь нет: `echoCancellation` остаётся дефолтным, потому что
 *   озвучка ответа и микрофон в локальном чате живут рядом;
 * - [StreamingStt] — обёртка над портом [AsrEnginePort]: принимает PCM только внутри реплики,
 *   поэтому хвост озвучки не попадает в распознавание;
 * - [StreamingAsrEnginePort] — единственная точка, где встречаются движок ([StreamingAsrEngine],
 *   события [AsrEvent]) и порт. Он же перекладывает события движка на главный поток.
 *
 * Поток событий ровно такой, каким его отдаёт движок: `AsrEvent.SpeechStarted` → серия
 * `AsrEvent.Partial` → `AsrEvent.Final`. [StreamingAsrEnginePort] раскладывает их на
 * [AsrEnginePort.Events], [StreamingStt] — на [SpeechToText.Events], а этот класс переводит
 * в фазы [LocalVoiceState] и один вызов [onTranscript].
 *
 * ## Одна реплика на одно нажатие
 * Локальный чат — не hands-free: тап открывает микрофон, [SpeechToText.Events.onFinal] (endpoint
 * движка) или повторный тап закрывают его, микрофон освобождается, текст уходит в композер.
 * Микрофон НЕ остаётся открытым после реплики: иначе озвучка ответа попадала бы в захват, а
 * фоновая запись в открытом приложении не нужна (фон и заблокированный экран — следующая волна).
 *
 * ## Чего здесь нет
 * Whisper-путь (`POST /api/transcribe`) не подключён: локальный чат ходит к провайдеру напрямую
 * и серверной авторизации Марса у него нет. Если движка распознавания на устройстве нет,
 * голосовой ввод честно недоступен ([LocalVoiceState.asrAvailable] = false), а не «молчит».
 *
 * @param activity хост: нужен для `requestPermissions` и `applicationContext`;
 * @param onTranscript распознанный текст реплики — экран пишет его в композер;
 * @param onState снимок состояния на каждое изменение (кнопка и хост рисуют по нему);
 * @param asrEngine готовый порт движка (для тестов/повторного использования); `null` — порт
 *   создаётся существующей фабрикой [StreamingAsrEnginePort.create];
 * @param serverTts серверный синтез для озвучки ответа; `null` — системный TTS;
 * @param speakAnswers озвучивать ответ на голосовую реплику (по умолчанию да: спросил голосом —
 *   получил голосом).
 */
class LocalVoiceController(
    private val activity: Activity,
    private val onTranscript: (String) -> Unit,
    private val onState: (LocalVoiceState) -> Unit,
    private val asrEngine: AsrEnginePort? = null,
    private val serverTts: ServerTtsClient? = null,
    /**
     * Офлайн-синтез (Piper). Если дан и готов — ответ звучит им, а не системным
     * роботом. `null` — остаётся только системный TTS.
     */
    private val offlineTts: (() -> ru.mars.mobile.tts.OfflineTtsPort?)? = null,
    private val speakAnswers: Boolean = true,
) {

    private val main = Handler(Looper.getMainLooper())

    /** Область для серверного синтеза: живёт вместе с контроллером, а не с Activity. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var state = LocalVoiceState()

    /** Разрешение уже спрашивали: нужно, чтобы отличить «не спрашивали» от «отклонили навсегда». */
    private var requestedPermission = false

    private var port: AsrEnginePort? = null
    private var stt: StreamingStt? = null
    private var capture: HandsFreeCapture? = null
    private var routes: AndroidAudioRouteMonitor? = null
    private var tts: LocalVoiceTts? = null

    private var loading = false
    private var pendingStart = false

    /** Реплика уже отдана хосту: второй `Final` (endpoint + ручной стоп) не должен её дублировать. */
    private var handledUtterance = false

    /**
     * Ход начат голосовой репликой: озвучивается только такой ответ.
     *
     * Без этого флага [onAnswerCompleted] читал бы вслух и ответ на ПЕЧАТНЫЙ ввод — а контракт
     * ([speakAnswers], [onAnswerCompleted]) и весь остальной проект (`armSpeakNextAnswer` в
     * `ChatVoiceInputSection`) говорят одно: спросил голосом — получил голосом. Ставится в
     * [finishWith], снимается первым же [onAnswerCompleted], поэтому не переживает свой ход.
     */
    private var voiceTurn = false

    private var released = false

    // ---------------------------------------------------------------------------------------
    // Публичный API
    // ---------------------------------------------------------------------------------------

    /** Текущий снимок состояния (тот же, что ушёл в [onState]). */
    fun state(): LocalVoiceState = state

    /**
     * Одно действие кнопки. Тап по микрофону ВСЕГДА открывает новую реплику — из покоя, из
     * «Думаю» и поверх озвучки (жест перебивания: глушим и сразу слушаем). Единственное
     * исключение — идущий захват: там тап значит «закончить и отдать текст».
     *
     * Почему не «в THINKING ничего не делать»: у контроллера нет своего сигнала «ход завершён» —
     * фазу снимал только хост ([onAnswerCompleted]), а кнопка в THINKING ещё и выключена
     * (`LocalVoiceButton.render`). Один пропущенный колбэк хоста — и микрофон не открывался
     * больше никогда (ровно один цикл на экран).
     *
     * Пока микрофон держит фоновый [MarsVoiceService], ветка «начать реплику» не открывает
     * захват: второй `AudioRecord` в том же процессе отобрал бы звук у сервиса. Вместо этого —
     * подсказка [BACKGROUND_LISTENING], фаза остаётся IDLE.
     */
    fun toggle() {
        if (released) return
        when (state.phase) {
            LocalVoicePhase.IDLE,
            LocalVoicePhase.THINKING,
            -> if (!blockedByBackgroundConversation()) startListening()

            LocalVoicePhase.LISTENING -> stopListening()

            LocalVoicePhase.SPEAKING -> {
                // Перебивание жестом: тишина и сразу слушаем — второй тап для этого не нужен.
                stopSpeaking()
                if (!blockedByBackgroundConversation()) startListening()
            }
        }
    }

    /**
     * Начать реплику.
     *
     * Порядок решения: фоновый сервис держит микрофон — уступаем; нет движка — честно
     * недоступно; нет разрешения — системный запрос; движок не загружен — фоновая загрузка и
     * автостарт после неё; иначе — микрофон.
     */
    fun startListening() {
        if (released) return
        // Живой захват не переоткрываем: второй `AudioRecord` отобрал бы звук у первого, а тап
        // в этой фазе значит «закончить» (это решает [toggle]).
        if (capture?.isCapturing == true) return
        // Прошлый захват мог остаться «полузакрытым» (поток чтения ещё не отпустил микрофон):
        // освобождаем его ДО нового `start()`, иначе [HandsFreeCapture.start] вернёт `false` и
        // тап будет выглядеть мёртвым.
        closeCapture()
        // Проверка ДО [ensureEngine]: при живом сервисе незачем даже поднимать движок и грузить
        // модель — микрофон всё равно не наш.
        if (blockedByBackgroundConversation()) return

        val wrapper = ensureEngine()
        if (wrapper == null) {
            emit(state.copy(asrAvailable = false, notice = ASR_UNAVAILABLE))
            return
        }
        if (!hasPermission()) {
            requestedPermission = true
            emit(state.copy(micPermissionGranted = false, notice = PERMISSION_ASKED))
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO,
            )
            return
        }
        if (!wrapper.isReady) {
            // Модель (23 МиБ) может докачиваться: микрофон откроем, когда движок реально готов,
            // иначе первые секунды речи уйдут в никуда.
            pendingStart = true
            emit(state.copy(notice = PREPARING))
            loadEngine(wrapper)
            return
        }
        startCapture(wrapper)
    }

    /** Закончить реплику и отдать текст в композер. Пустая реплика — «не расслышал». */
    fun stopListening() {
        if (released) return
        if (state.phase != LocalVoicePhase.LISTENING) return
        val wrapper = stt
        closeCapture()
        val text = runCatching { wrapper?.finishUtterance() }.getOrNull().orEmpty()
        // На главном потоке [StreamingStt] доставляет `onFinal` синхронно, поэтому реплика
        // обычно уже обработана. Здесь — только страховка для пустого/непришедшего финала.
        if (!handledUtterance) finishWith(text)
    }

    /** Выбросить реплику без текста (уход с экрана, отмена). */
    fun cancelListening() {
        if (released) return
        closeCapture()
        runCatching { stt?.cancelUtterance() }
        handledUtterance = true
        if (state.phase == LocalVoicePhase.LISTENING) {
            emit(state.copy(phase = LocalVoicePhase.IDLE, partial = "", notice = null))
        }
    }

    /**
     * Начался ход: прошлая озвучка не должна звучать поверх нового ответа.
     *
     * «Думаю» ставит голосовая реплика, но снять его может только хост — а если хост этого не
     * сделал, микрофон оставался заблокированным навсегда. Поэтому новый ход снимает фазу сам:
     * печатный ввод из THINKING тоже возвращает кнопку в строй.
     */
    fun onTurnStarted() {
        if (released) return
        stopSpeaking()
        if (state.phase == LocalVoicePhase.THINKING) emit(state.copy(phase = LocalVoicePhase.IDLE))
    }

    /**
     * Ход завершён: озвучить ответ, если он пришёл на голосовую реплику и озвучка доступна.
     * Без звука фаза возвращается в IDLE — кнопка не остаётся в «Говорю» навсегда.
     */
    fun onAnswerCompleted(text: String?) {
        if (released) return
        val body = text?.trim().orEmpty()
        // Флаг расходуется любым исходом: ответ на печатный ввод не озвучивается, а
        // «голосом спросили» не тянется в следующий ход.
        val fromVoice = voiceTurn
        voiceTurn = false
        if (!speakAnswers || !fromVoice || body.isEmpty()) {
            // Открытый микрофон не трогаем: иначе фаза стала бы IDLE при живом захвате, и
            // следующий тап «закончить» не нашёл бы, что закрывать.
            if (state.phase != LocalVoicePhase.LISTENING) {
                emit(state.copy(phase = LocalVoicePhase.IDLE, partial = "", notice = null))
            }
            return
        }
        // Микрофон уже открыт — человек задаёт следующий вопрос. Ответ прошлого хода читать
        // вслух нельзя: он прозвучал бы поверх его речи и попал в захват.
        if (state.phase == LocalVoicePhase.LISTENING || capture?.isCapturing == true) return
        val voice = ensureTts()
        if (voice == null || !voice.available) {
            emit(state.copy(phase = LocalVoicePhase.IDLE, ttsAvailable = false, notice = TTS_UNAVAILABLE))
            return
        }
        val accepted = runCatching { voice.speak(body) }.getOrDefault(false)
        if (accepted) {
            emit(state.copy(phase = LocalVoicePhase.SPEAKING, partial = "", ttsAvailable = true, notice = null))
        } else {
            emit(state.copy(phase = LocalVoicePhase.IDLE, notice = TTS_UNAVAILABLE))
        }
    }

    /** Озвучить произвольный текст (пункт «Озвучить» у сообщения). */
    fun speakNow(text: String?) {
        if (released) return
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) return
        val voice = ensureTts()
        if (voice == null || !voice.available) {
            emit(state.copy(ttsAvailable = false, notice = TTS_UNAVAILABLE))
            return
        }
        if (runCatching { voice.speak(body) }.getOrDefault(false)) {
            emit(state.copy(phase = LocalVoicePhase.SPEAKING, ttsAvailable = true, notice = null))
        }
    }

    /** Немедленная тишина (перебивание жестом или конец диалога). */
    fun stopSpeaking() {
        tts?.stopByUser()
        if (state.phase == LocalVoicePhase.SPEAKING) emit(state.copy(phase = LocalVoicePhase.IDLE))
    }

    /** Пересчитать доступность: разрешение могли выдать или отозвать в настройках. */
    fun refresh() {
        if (released) return
        val wrapper = ensureEngine()
        emit(
            state.copy(
                micPermissionGranted = hasPermission(),
                asrAvailable = wrapper != null,
                ttsAvailable = tts?.available ?: ttsPossible(),
            )
        )
    }

    /** Экран вернулся на передний план. */
    fun onResume() {
        if (released) return
        refresh()
    }

    /**
     * Экран ушёл в фон: микрофон закрывается, звук глушится. Недоговорённая реплика
     * ВЫБРАСЫВАЕТСЯ — в открытом приложении человек видит, что микрофон выключен, а фон и
     * заблокированный экран — отдельная волна.
     *
     * Исключение — когда микрофон и озвучку держит фоновый [MarsVoiceService]: у экрана и
     * сервиса ОДИН порт офлайн-синтеза, поэтому `stopSpeaking()` здесь гасил бы ответ,
     * который сервис читает вслух в кармане. Ровно это и ломало фоновый разговор: стоило
     * погасить экран — речь обрывалась, а сигнал «дочитал» не приходил, и сервис застревал
     * в SPEAKING.
     */
    fun onPause() {
        if (released) return
        if (state.phase == LocalVoicePhase.LISTENING) cancelListening()
        if (MarsVoiceService.conversationActive) return
        stopSpeaking()
    }

    /**
     * Результат системного запроса разрешения. Хост зовёт из `onRequestPermissionsResult`
     * (requestCode = [REQUEST_RECORD_AUDIO]); если он этого не сделает, состояние всё равно
     * исправится на следующем [refresh]/[onResume]/тапе.
     */
    fun onPermissionResult(granted: Boolean) {
        if (released) return
        emit(
            state.copy(
                micPermissionGranted = granted,
                notice = if (granted) null else PERMISSION_DENIED,
            )
        )
        if (granted) startListening()
    }

    /**
     * Разрешение отклонено навсегда: системный диалог больше не покажется, помочь может только
     * экран настроек — если хост дал такое действие ([LocalVoiceButton] принимает его как `onOpenX`).
     */
    fun permissionBlocked(): Boolean {
        if (hasPermission()) return false
        if (!requestedPermission) return false
        return !runCatching {
            ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.RECORD_AUDIO,
            )
        }.getOrDefault(false)
    }

    /** Хост уничтожен: микрофон, движок и озвучка освобождаются. */
    fun release() {
        if (released) return
        released = true
        closeCapture()
        runCatching { stt?.release() }
        stt = null
        port = null
        // Экранный TTS обёрнут вокруг общего офлайн-порта: `release()` у него доходит до
        // `port.release()` и убивает нативный движок, которым в этот момент говорит
        // фоновый сервис. Пока разговор в фоне жив, порт принадлежит ему.
        if (!MarsVoiceService.conversationActive) {
            runCatching { tts?.release() }
        }
        tts = null
        runCatching { routes?.stop() }
        routes = null
        scope.cancel()
    }

    // ---------------------------------------------------------------------------------------
    // Распознавание
    // ---------------------------------------------------------------------------------------

    /**
     * Порт движка — существующей фабрикой [StreamingAsrEnginePort.create] (она же связывает
     * события [AsrEvent] с портом). Создаётся один раз за жизнь контроллера: конструктор
     * движка нативный слой не трогает, модель грузится только в [loadEngine].
     */
    private fun ensureEngine(): StreamingStt? {
        stt?.let { return it }
        if (released) return null
        val created = asrEngine
            ?: runCatching { StreamingAsrEnginePort.create(activity.applicationContext) }.getOrNull()
            ?: return null
        port = created
        val wrapper = StreamingStt(created)
        wrapper.events = sttEvents
        stt = wrapper
        return wrapper
    }

    private fun loadEngine(wrapper: StreamingStt) {
        val engine = port ?: return
        if (loading) return
        loading = true
        Thread({
            val loaded = runCatching { engine.load() }.getOrDefault(false)
            main.post {
                loading = false
                if (!released) {
                    if (!loaded) {
                        pendingStart = false
                        emit(state.copy(asrAvailable = false, notice = ASR_UNAVAILABLE))
                    } else {
                        emit(state.copy(asrAvailable = true, notice = null))
                        if (pendingStart) {
                            pendingStart = false
                            if (hasPermission()) startCapture(wrapper) else startListening()
                        }
                    }
                }
            }
        }, THREAD_ASR_LOAD).start()
    }

    /**
     * Микрофон уже занят фоновым разговором — второй захват открывать нельзя.
     *
     * [MarsVoiceService] держит собственный `AudioRecord` через свой движок. Второй захват в том
     * же процессе (`HandsFreeCapture` экрана) не «делит» микрофон, а отбирает его: сервис в логе
     * получает `[mute]` и перестаёт слышать ключевую фразу. Поэтому экран честно уступает —
     * сообщает, что слушает фон, и остаётся в IDLE (свой захват не открыт, закрывать нечего).
     *
     * @return `true`, если захват открывать НЕЛЬЗЯ (notice уже выставлен).
     */
    private fun blockedByBackgroundConversation(): Boolean {
        if (!MarsVoiceService.conversationActive) return false
        emit(state.copy(notice = BACKGROUND_LISTENING))
        return true
    }

    /** Микрофон + сырой PCM в движок. Маршрут захвата — дефолт [AndroidAudioSourceFactory]. */
    private fun startCapture(wrapper: StreamingStt) {
        if (released) return
        // Живой захват не переоткрываем (второй `AudioRecord` отобрал бы звук у первого), но и
        // не выходим молча: перед новым захватом прошлый ОБЯЗАН быть освобождён — иначе тап
        // выглядит мёртвым, а `handledUtterance` не сбрасывается.
        if (capture?.isCapturing == true) closeCapture()
        // Отложенный автостарт после загрузки модели идёт мимо [startListening] — проверяем и здесь,
        // иначе сервис, поднявшийся за время загрузки, потерял бы микрофон.
        if (blockedByBackgroundConversation()) return
        // Явное нажатие микрофона — это и есть перебивание: доказательств речи не требуется.
        tts?.stopByUser()
        val fresh = HandsFreeCapture()
        // Реплика открывается ДО первого чтения: иначе первые буферы микрофона придут вне
        // реплики и [StreamingStt] их отбросит (а это начало фразы). Здесь же снимаются флаги
        // прошлого цикла: новая реплика — новый `Final`, и ответ прошлого хода уже не озвучивается.
        handledUtterance = false
        voiceTurn = false
        wrapper.startUtterance()
        val started = runCatching {
            fresh.start(
                captureListener,
                HandsFreeCapture.RawPcmListener { pcm, length -> wrapper.acceptPcm(pcm, length) },
            )
        }.getOrDefault(false)
        if (!started) {
            runCatching { wrapper.cancelUtterance() }
            emit(state.copy(notice = MIC_UNAVAILABLE))
            return
        }
        capture = fresh
        emit(state.copy(phase = LocalVoicePhase.LISTENING, partial = "", notice = null))
    }

    private fun closeCapture() {
        val current = capture ?: return
        capture = null
        runCatching { current.cancel() }
    }

    /** Реплика закрыта: микрофон освобождён, текст отдан один раз. */
    private fun finishWith(text: String) {
        if (handledUtterance) return
        handledUtterance = true
        closeCapture()
        runCatching { stt?.cancelUtterance() }
        val clean = text.trim()
        if (clean.isEmpty()) {
            emit(state.copy(phase = LocalVoicePhase.IDLE, partial = "", notice = NOT_RECOGNIZED))
            return
        }
        // Ход начат голосом: его ответ — единственный кандидат на озвучку ([onAnswerCompleted]).
        voiceTurn = true
        // Фаза — IDLE, а НЕ THINKING. THINKING закрыт для нажатий (`LocalVoiceButton.render`
        // гасит кнопку), а своего сигнала «ход завершён» у контроллера нет: снимал его только
        // хост. Один пропущенный колбэк — и микрофон не открывался больше никогда (на POCO
        // 29.10: первая реплика проходит, дальше кнопка мертва). Прогресс хода рисует экран;
        // здесь важно, что микрофон снова доступен для следующей реплики.
        emit(state.copy(phase = LocalVoicePhase.IDLE, partial = clean, notice = null))
        runCatching { onTranscript(clean) }
    }

    /**
     * События движка. [StreamingStt] уже переложил их на главный поток, но захват зовёт свои
     * колбэки с потока чтения — поэтому [post] применяется ко всем без исключения.
     */
    private val sttEvents = object : SpeechToText.Events {
        override fun onSpeechStarted() = post {
            if (state.phase == LocalVoicePhase.LISTENING) emit(state.copy(notice = null))
        }

        override fun onPartial(text: String) = post {
            if (state.phase == LocalVoicePhase.LISTENING) emit(state.copy(partial = text))
        }

        override fun onFinal(text: String) = post { finishWith(text) }

        override fun onFailed(reason: String) = post {
            emit(state.copy(asrAvailable = false, notice = ASR_UNAVAILABLE))
        }
    }

    /**
     * Колбэки захвата (поток чтения микрофона).
     *
     * `onSpeechProgress` — единственный поток «сколько человек реально говорил»; он и partial
     * ASR уходят в [LocalVoiceTts.offerSpeech], где решение принимает [BargeInGate]. В локальном
     * чате микрофон закрыт, пока звучит ответ, поэтому путь акустического перебивания — задел на
     * случай, если координатор оставит захват открытым; сейчас работает жестовое перебивание.
     */
    private val captureListener = object : HandsFreeCapture.Listener {
        override fun onSpeechStarted() = Unit

        override fun onSpeechProgress(voicedMs: Long) = post { tts?.offerSpeech(voicedMs) }

        /** Сегменты whisper-пути не нужны: движок режет реплику сам по endpoint. */
        override fun onSegment(recording: Recording) = Unit

        override fun onCaptureFailed() = post {
            closeCapture()
            emit(state.copy(phase = LocalVoicePhase.IDLE, partial = "", notice = MIC_UNAVAILABLE))
        }
    }

    // ---------------------------------------------------------------------------------------
    // Озвучка
    // ---------------------------------------------------------------------------------------

    private fun ensureTts(): LocalVoiceTts? {
        tts?.let { return it }
        if (released) return null
        // Статическую проверку «есть ли на устройстве TTS-сервис» здесь делать НЕЛЬЗЯ:
        // `PackageManager.queryIntentServices(INTENT_ACTION_TTS_SERVICE)` на Android 11+ при
        // targetSdk 34 фильтруется видимостью пакетов и без `<queries>` в манифесте возвращает
        // пустоту — живой системный TTS выглядел как «озвучки нет», и ответ не читался.
        // Правду о готовности сообщает сам движок: `AndroidSpeechEngine.onInitFinished` →
        // [LocalVoiceTts.available] → `onAvailableChanged` → `ttsAvailable`. До готовности речь
        // буферизуется внутри `AndroidSpeechEngine` и звучит сразу после инициализации.
        // Маршрут вывода нужен гейту: на наушниках хватает длительности речи, на динамике — нет.
        // Решение о маршруте принимает существующий [AudioRoutePolicy] внутри монитора.
        val monitor = routes ?: runCatching {
            AndroidAudioRouteMonitor(activity.applicationContext).also { it.start() }
        }.getOrNull()
        if (monitor != null) routes = monitor
        val created = LocalVoiceTts(
            context = activity.applicationContext,
            scope = scope,
            serverTts = serverTts,
            offline = offlineTts?.invoke(),
            route = { monitor?.route ?: AudioRoute.UNKNOWN },
            aecActive = { capture?.echoCanceled == true },
            onSpeakingChanged = { speaking -> post { onTtsSpeakingChanged(speaking) } },
            onAvailableChanged = { available -> post { emit(state.copy(ttsAvailable = available)) } },
        )
        tts = created
        emit(state.copy(ttsAvailable = created.available))
        return created
    }

    private fun onTtsSpeakingChanged(speaking: Boolean) {
        if (released) return
        if (speaking) {
            // Микрофон уже открыт — человек говорит; фазу не перебиваем, иначе следующий тап
            // «закончить» ушёл бы в [stopSpeaking] и реплика потерялась бы.
            if (state.phase != LocalVoicePhase.SPEAKING && state.phase != LocalVoicePhase.LISTENING) {
                emit(state.copy(phase = LocalVoicePhase.SPEAKING, ttsAvailable = true))
            }
        } else if (state.phase == LocalVoicePhase.SPEAKING) {
            emit(state.copy(phase = LocalVoicePhase.IDLE))
        }
    }

    /**
     * Есть ли чем озвучивать. Ответ даёт сам движок: [LocalVoiceTts.available] становится `false`
     * только после РЕАЛЬНОГО отказа инициализации (`AndroidSpeechEngine` → `onInitFinished(false)`).
     *
     * До создания движка считаем, что озвучка возможна: предсказать отказ через PackageManager
     * нельзя (см. [ensureTts] — запрос `INTENT_ACTION_TTS_SERVICE` без `<queries>` в манифесте
     * на Android 11+ всегда пуст, и рабочая озвучка выглядела мёртвой). Если движка на
     * устройстве правда нет, `onAvailableChanged` честно выставит `ttsAvailable = false`.
     */
    private fun ttsPossible(): Boolean = tts?.available ?: true

    // ---------------------------------------------------------------------------------------
    // Мелочи
    // ---------------------------------------------------------------------------------------

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun emit(next: LocalVoiceState) {
        if (released) return
        state = next
        runCatching { onState(next) }
    }

    /** Колбэк движка/захвата может прийти с потока чтения: состояние меняем только на главном. */
    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    companion object {
        /** requestCode рантайм-запроса `RECORD_AUDIO` для локального чата. */
        const val REQUEST_RECORD_AUDIO: Int = 4711

        const val NOT_RECOGNIZED: String = "Не расслышал — попробуйте ещё раз"
        const val MIC_UNAVAILABLE: String = "Микрофон недоступен"
        const val ASR_UNAVAILABLE: String = "Распознавание речи недоступно на этом устройстве"
        const val PREPARING: String = "Готовлю распознавание…"
        const val PERMISSION_ASKED: String = "Разрешите доступ к микрофону"
        const val PERMISSION_DENIED: String = "Без доступа к микрофону голосовой ввод не работает"
        const val TTS_UNAVAILABLE: String = "Озвучка недоступна на этом устройстве"

        /** Микрофон держит фоновый сервис: второй захват отобрал бы у него звук. */
        const val BACKGROUND_LISTENING: String = "Слушаю в фоне — говорите после „Марс, слушай“"

        private const val THREAD_ASR_LOAD = "mars-local-voice-asr-load"
    }
}
