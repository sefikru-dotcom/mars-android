package ru.mars.mobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.mars.mobile.service.MarsVoiceService
import ru.mars.mobile.voice.MicCaptureWatchdog
import java.util.Locale

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
 * ## Одна реплика на одно нажатие — и непрерывный разговор поверх этого
 * В обычном чате тап открывает микрофон, [SpeechToText.Events.onFinal] (endpoint движка) или
 * повторный тап закрывают его, микрофон освобождается, текст уходит в композер. Микрофон НЕ
 * остаётся открытым после реплики: иначе озвучка ответа попадала бы в захват.
 *
 * Исключение — полноэкранный разговор ([continuousConversation]): пока слой
 * [VoiceModeOverlay] открыт, после ответа Марса микрофон открывается сам через
 * [CONTINUOUS_RESTART_DELAY_MS]. Это единственное место, где захват живёт дольше одной реплики,
 * и включается он только явным входом в голосовой режим.
 *
 * ## Два канала распознавания
 * Основной — офлайн-движок sherpa-onnx ([StreamingAsrEnginePort]): приватно, без сети, с живым
 * партиалом. Пока его модель (23 МиБ) качается или не встала, реплика открывается системным
 * `android.speech.SpeechRecognizer` ([startPlatformSpeechRecognizer]) — мгновенно, вместо
 * пятнадцати секунд молчания. Если порт движка передан в конструктор ([asrEngine]), резерв
 * выключен: путь ровно один, и он проверяемый.
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

    /**
     * Темп, выбранный до готовности движка (или применённый сейчас).
     *
     * Хранится копией, а не в [VoiceProsodyPrefs]: настройки читает экран, а контроллер
     * обязан быть независим от хранилища — его тесты собирают контроллер без Android.
     */
    private var pendingProsody: VoiceProsody? = null

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

    /**
     * Экран в фоне. Нужен ровно для одного: автостарт микрофона в непрерывном разговоре не
     * должен срабатывать, когда экрана нет (телефон в кармане — это не разговор).
     */
    private var paused = false

    /**
     * Сторож «захват открыт, а звука нет». Чистая машина состояний в
     * [MicCaptureWatchdog]: секунды, счётчик перезапусков и решение сдаться проверяются
     * юнит-тестом, а здесь только таймер и вызовы.
     */
    private val micWatchdog = MicCaptureWatchdog(MIC_WATCHDOG_MS)

    /** Ход с озвучкой дельтами: текст уже ушёл в синтез, повторять его целиком нельзя. */
    private var streamedTurn = false

    /** Подсказка на размышлениях уже прозвучала в этом ходу (второй раз не говорим). */
    private var hintSpoken = false

    /** Подсказка поставлена в очередь на [THINKING_HINT_DELAY_MS] — второй раз не ставим. */
    private var hintScheduled = false

    /** Идёт пауза между [closeCapture] и повторным захватом после срабатывания сторожа. */
    private var micRestartPending = false

    /**
     * Непрерывный разговор (hands-free turn-taking): пока включён, микрофон после ответа Марса
     * открывается сам — человеку не нужно тянуться к кнопке «Говорить» после каждой реплики.
     *
     * Включается слоем полноэкранного разговора ([VoiceModeOverlay.show]) и выключается вместе с
     * ним ([VoiceModeOverlay.hide]): в обычном чате микрофон по-прежнему «одна реплика на одно
     * нажатие», иначе он подхватывал бы озвучку и случайные слова.
     */
    var continuousConversation: Boolean = false
        set(value) {
            field = value
            // Выключили режим — отложенный автостарт прошлой реплики больше не нужен.
            if (!value) cancelContinuousRestart()
        }

    /**
     * Ответ хода ещё не передан в синтез: заглушка «Секунду, думаю.» договорила, но ответ
     * впереди — открывать микрофон в этот момент нельзя, он поймал бы начало ответа.
     */
    private var answerPending = false

    /**
     * Системный распознаватель Android (`android.speech.SpeechRecognizer`) — мгновенный
     * резерв, пока офлайн-модель sherpa-onnx (23 МиБ) качается или не встала.
     *
     * Создаётся на главном потоке и только по нажатию микрофона: держать его живым постоянно
     * значит держать живым чужой сервис распознавания.
     */
    private var platformAsr: SpeechRecognizer? = null

    /** Системный распознаватель слушает прямо сейчас (реплика открыта им, а не [capture]). */
    private var platformListening = false

    /** Отложенный автостарт следующей реплики в режиме непрерывного разговора. */
    private val continuousRestart = Runnable {
        if (released || paused || !continuousConversation) return@Runnable
        // Пока ждали, человек мог начать новую реплику или уйти с экрана.
        if (state.phase != LocalVoicePhase.IDLE) return@Runnable
        // Ответ всё-таки зазвучал (вторая половина фразы после паузы) — микрофон не открываем.
        if (tts?.isSpeaking == true) return@Runnable
        if (blockedByBackgroundConversation()) return@Runnable
        startListening()
    }

    /**
     * Живая громкость речи 0..1: сырой RMS микрофона во время реплики человека и синтетическая
     * огибающая во время ответа Марса.
     *
     * Зачем колбэк, а не поле в [LocalVoiceState]: уровень меняется 10–16 раз в секунду, и
     * гонять его через снимок состояния значило бы перерисовывать весь полноэкранный слой
     * (подписи, транскрипт, кнопку) на каждое слово. Подписчик один — орб
     * ([VoiceModeOverlay.setLevel] → [VoiceModeOrbView.setLevel]); ему нужна частота, а не
     * состояние.
     */
    var onAudioLevel: ((Float) -> Unit)? = null

    /** Когда уровень публиковался в прошлый раз: поток чтения микрофона идёт ~50 раз в секунду. */
    private var lastLevelAt = 0L

    /** Фаза синтетической огибающей речи: во время ответа микрофон закрыт, а пульсация нужна. */
    private var speechPhase = 0f

    /**
     * Огибающая «речи» Марса, пока звучит ответ.
     *
     * Почему не реальный уровень: на время озвучки микрофон закрыт намеренно (иначе в захват
     * попал бы собственный динамик), и брать громкость неоткуда. Синтетическая огибающая с
     * паузами между «словами» даёт ровно то, что видит человек: планета дышит вместе с голосом.
     *
     * Объект, а не лямбда: `main.postDelayed(this, …)` обязан получить сам Runnable, а в лямбде
     * `this` указывает на контроллер.
     */
    private val speechEnvelope = object : Runnable {
        override fun run() {
            if (released || state.phase != LocalVoicePhase.SPEAKING) return
            speechPhase += SPEECH_TICK_PHASE
            onAudioLevel?.invoke(envelopeAt(speechPhase))
            main.postDelayed(this, SPEECH_TICK_MS)
        }
    }

    init {
        // Канал становится «активным» сразу: слой разговора включает у него непрерывный режим,
        // не получая ссылки на контроллер от хоста (см. [active]).
        activeController = this
    }

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
     * фазу снимает только хост ([onAnswerCompleted]). Один пропущенный колбэк хоста — и микрофон
     * не открывался бы больше никогда (ровно один цикл на экран), поэтому кнопка в «Думаю»
     * остаётся живой ([LocalVoiceButton.render]) и тап начинает новую реплику.
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
     * Порядок решения: фоновый сервис держит микрофон — уступаем; нет разрешения — системный
     * запрос; офлайн-движок не готов, а системный распознаватель есть — мгновенный резерв;
     * движка нет вовсе — честно недоступно; движок не загружен — фоновая загрузка и автостарт
     * после неё; иначе — микрофон.
     */
    fun startListening() {
        if (released) return
        // Ручной старт отменяет отложенный автостарт: он открыл бы микрофон поверх реплики.
        cancelContinuousRestart()
        // Живой захват не переоткрываем: второй `AudioRecord` отобрал бы звук у первого, а тап
        // в этой фазе значит «закончить» (это решает [toggle]).
        if (capture?.isCapturing == true) return
        // Системный распознаватель уже слушает: второй `startListening` у него не открывает
        // новую реплику, а ломает текущую.
        if (platformListening) return
        // Прошлый захват мог остаться «полузакрытым» (поток чтения ещё не отпустил микрофон):
        // освобождаем его ДО нового `start()`, иначе [HandsFreeCapture.start] вернёт `false` и
        // тап будет выглядеть мёртвым.
        closeCapture()
        // Проверка ДО [ensureEngine]: при живом сервисе незачем даже поднимать движок и грузить
        // модель — микрофон всё равно не наш.
        if (blockedByBackgroundConversation()) return

        val wrapper = ensureEngine()
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
        // Мгновенный резерв: офлайн-модель ещё качается (или не встала), а человек уже нажал.
        // Ждать 23 МиБ с GitHub нельзя — системный распознаватель открывает реплику сразу, а
        // модель догружается в фоне к следующему ходу.
        if (platformFallbackAllowed(wrapper) && startPlatformSpeechRecognizer()) {
            if (wrapper != null && !wrapper.isReady) loadEngine(wrapper)
            return
        }
        if (wrapper == null) {
            emit(state.copy(asrAvailable = false, notice = ASR_UNAVAILABLE))
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

    /**
     * Тихо прогреть офлайн-распознавание заранее — при открытии экрана локального чата.
     *
     * Зачем: загрузка sherpa-onnx (23 МиБ) на первом нажатии микрофона превращалась в
     * 15–20 секунд молчания с одним статичным «Готовлю распознавание…». Здесь движок поднимается
     * до того, как он понадобился, а человек видит живой статус, а не мёртвую кнопку.
     *
     * Идемпотентно и беззвучно: повторный вызов при готовом движке ничего не делает.
     */
    fun warmUpAsrInBackground() {
        if (released) return
        val wrapper = ensureEngine()
        if (wrapper == null) {
            // Офлайн-движка нет вовсе. Если системный распознаватель есть, голос всё равно
            // работает — «недоступно» показывать нечестно.
            val platform = platformRecognizerAvailable()
            if (platform != state.asrAvailable) emit(state.copy(asrAvailable = platform))
            return
        }
        if (wrapper.isReady) {
            if (!state.asrAvailable) emit(state.copy(asrAvailable = true))
            return
        }
        if (loading) return
        emit(state.copy(notice = WARMING_UP))
        loadEngine(wrapper, warmUp = true)
    }

    /** Закончить реплику и отдать текст в композер. Пустая реплика — «не расслышал». */
    fun stopListening() {
        if (released) return
        if (state.phase != LocalVoicePhase.LISTENING) return
        // Системный распознаватель закрывает реплику сам: текст придёт в `onResults`, поэтому
        // здесь только «заканчивай» и живой статус, а не пустой финал.
        if (platformListening) {
            runCatching { platformAsr?.stopListening() }
            emit(state.copy(notice = TRANSCRIBING))
            return
        }
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
        cancelThinkingHint()
        cancelContinuousRestart()
        // Системный распознаватель отпускает микрофон сразу; сам объект оставляем — он ещё
        // понадобится, а создание сервиса дороже отмены реплики.
        if (platformListening) {
            platformListening = false
            runCatching { platformAsr?.cancel() }
        }
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
        // Новый ход — новые подсказки: «Секунду, думаю» звучит не чаще раза за ход.
        cancelThinkingHint()
        hintSpoken = false
        // «Думаю» голосового хода снимать здесь НЕЛЬЗЯ: хост зовёт этот метод сразу после
        // отправки распознанной реплики, то есть пока модель ещё думает. Иначе орб и кнопка
        // гасли в «Нажмите, чтобы говорить» ровно на время ожидания ответа. Печатный ввод
        // (voiceTurn == false) по-прежнему возвращает кнопку в строй.
        if (state.phase == LocalVoicePhase.THINKING && !voiceTurn) {
            emit(state.copy(phase = LocalVoicePhase.IDLE))
        }
    }

    /**
     * Модель начала отвечать: текст ответа пойдёт дельтами.
     *
     * Зачем это отдельный вызов, а не «начнём при первой дельте»: пока первый кусок текста
     * синтезируется, старый звук (например подсказка «Секунду, думаю») должен быть уже погашен
     * и подтверждён тишиной ([LocalVoiceTts.beginStream]) — иначе первый же кусок нового ответа
     * глушится остатком чужого стопа, и ответ начинается со второго предложения.
     *
     * @return `true` — поток открыт и ответ будет озвучен; `false` — озвучки нет (ход не
     *   голосовой, автоозвучка выключена, объект освобождён).
     */
    fun onAnswerStarted(): Boolean {
        if (released) return false
        if (!speakAnswers || !voiceTurn) return false
        if (state.phase == LocalVoicePhase.LISTENING || capture?.isCapturing == true) return false
        val voice = ensureTts() ?: return false
        if (!voice.available) return false
        cancelThinkingHint()
        streamedTurn = false
        return runCatching { voice.beginStream() }.getOrDefault(false)
    }

    /**
     * Очередная дельта текста ответа: ранний старт речи.
     *
     * Первый кусок уходит в синтез по клаузу (запятая/точка/двоеточие/тире/перевод строки),
     * следующие — пачками ~300 символов по границе слова ([ru.mars.mobile.voice.SpeechChunker]).
     * Ждать готового ответа не нужно: озвучка догоняет текст сама, куски не теряются.
     *
     * @return `true` — кусок принят или встал в очередь; `false` — копим текст (говорить пока
     *   нечего) либо ответ не озвучивается.
     */
    fun onAnswerDelta(delta: String?): Boolean {
        if (released) return false
        if (!speakAnswers || !voiceTurn) return false
        if (delta.isNullOrEmpty()) return false
        val voice = tts ?: return false
        cancelThinkingHint()
        // Первый же принятый кусок отменяет подсказку: текст появился — заглушка не нужна.
        val accepted = runCatching { voice.appendStream(delta) }.getOrDefault(false)
        // Отданный кусок означает, что первые символы уже говорятся — вторую дозу текста
        // (весь ответ в onAnswerCompleted) произносить нельзя, иначе ответ прозвучит дважды.
        // Фазу SPEAKING здесь НЕ ставим: её включает [onTtsSpeakingChanged] по принятому
        // синтезу, иначе индикатор опережал бы звук на самой первой дельте (см. KDoc
        // [LocalVoiceTts] про точность движка).
        if (accepted) streamedTurn = true
        return accepted
    }

    /**
     * Модель начала с рассуждений, а озвучиваемого текста ещё нет.
     *
     * Молчание в этот момент человек читает как «Марс сломался»: локальная модель думает
     * секунды. Поэтому вместо тишины звучит короткая заглушка — РОВНО ОДИН раз за ход и только
     * если текст не появился сам ([THINKING_HINT_DELAY_MS] — тот же промежуток, за который
     * первый кусок успевает прийти у быстрой модели).
     *
     * @return `true` — подсказка поставлена в очередь.
     */
    fun onReasoningStarted(): Boolean {
        if (released) return false
        if (!speakAnswers || !voiceTurn) return false
        if (hintSpoken || hintScheduled) return false
        if (streamedTurn) return false
        // Человек говорит — поверх его речи Марс не вставляет заглушку.
        if (state.phase == LocalVoicePhase.LISTENING || capture?.isCapturing == true) return false
        if (state.phase == LocalVoicePhase.SPEAKING) return false
        hintScheduled = true
        main.postDelayed(thinkingHint, THINKING_HINT_DELAY_MS)
        return true
    }

    /**
     * Ход завершён: озвучить ответ, если он пришёл на голосовую реплику и озвучка доступна.
     * Без звука фаза возвращается в IDLE — кнопка не остаётся в «Говорю» навсегда.
     *
     * Если ответ уже звучал потоком ([onAnswerDelta]), здесь отдаётся только его хвост:
     * повторно весь текст не произносится.
     */
    fun onAnswerCompleted(text: String?) {
        if (released) return
        val body = text?.trim().orEmpty()
        // Флаг расходуется любым исходом: ответ на печатный ввод не озвучивается, а
        // «голосом спросили» не тянется в следующий ход.
        val fromVoice = voiceTurn
        voiceTurn = false
        // Ответ целиком в руках синтеза (или его не будет вовсе): заглушка «Секунду, думаю.»
        // больше не может договорить вместо ответа, и автостарт микрофона разрешён.
        answerPending = false
        cancelThinkingHint()
        // Поток уже говорил этот ответ: дозвучиваем хвост и НЕ читаем текст заново.
        if (streamedTurn) {
            streamedTurn = false
            val tail = tts?.let { runCatching { it.endStream() }.getOrDefault(false) } ?: false
            if (tail && state.phase != LocalVoicePhase.SPEAKING) {
                emit(state.copy(phase = LocalVoicePhase.SPEAKING, partial = "", ttsAvailable = true))
            } else if (!tail) {
                // Хвоста нет — речь кончилась раньше. В непрерывном разговоре микрофон обязан
                // открыться сам, иначе диалог встанет до следующего тапа.
                scheduleContinuousListening()
            }
            return
        }
        if (!speakAnswers || !fromVoice || body.isEmpty()) {
            // Открытый микрофон не трогаем: иначе фаза стала бы IDLE при живом захвате, и
            // следующий тап «закончить» не нашёл бы, что закрывать.
            if (state.phase != LocalVoicePhase.LISTENING) {
                emit(state.copy(phase = LocalVoicePhase.IDLE, partial = "", notice = null))
            }
            if (fromVoice) scheduleContinuousListening()
            return
        }
        // Микрофон уже открыт — человек задаёт следующий вопрос. Ответ прошлого хода читать
        // вслух нельзя: он прозвучал бы поверх его речи и попал в захват.
        if (state.phase == LocalVoicePhase.LISTENING || capture?.isCapturing == true) return
        val voice = ensureTts()
        if (voice == null || !voice.available) {
            emit(state.copy(phase = LocalVoicePhase.IDLE, ttsAvailable = false, notice = TTS_UNAVAILABLE))
            scheduleContinuousListening()
            return
        }
        val accepted = runCatching { voice.speak(body) }.getOrDefault(false)
        if (accepted) {
            emit(state.copy(phase = LocalVoicePhase.SPEAKING, partial = "", ttsAvailable = true, notice = null))
        } else {
            emit(state.copy(phase = LocalVoicePhase.IDLE, notice = TTS_UNAVAILABLE))
            scheduleContinuousListening()
        }
    }

    /**
     * Конец потока ответа при раннем старте озвучки.
     *
     * Зачем отдельно от [onAnswerCompleted]: экран, который уже отдавал дельты, обязан
     * закрыть поток, но НЕ повторять ответ целиком. [onAnswerCompleted] здесь не годится —
     * он же решает, озвучивать ли ответ вообще, и сбрасывает `voiceTurn`; при потоковом
     * чтении этот флаг уже отработал на первом куске.
     *
     * Идемпотентен: повторный вызов после закрытия ничего не делает.
     */
    fun onAnswerEnded() {
        if (released) return
        cancelThinkingHint()
        answerPending = false
        if (!streamedTurn) return
        streamedTurn = false
        val tail = tts?.let { runCatching { it.endStream() }.getOrDefault(false) } ?: false
        if (tail && state.phase != LocalVoicePhase.SPEAKING) {
            emit(state.copy(phase = LocalVoicePhase.SPEAKING, partial = "", ttsAvailable = true))
        } else if (!tail) {
            scheduleContinuousListening()
        }
    }

    /**
     * Применить темп и голос к озвучке.
     *
     * Живёт здесь, а не на экране: экран владеет шторкой, а речь — контроллер. Он же
     * знает, поднят ли движок ([ensureTts]): до первого звука темпа применять некуда,
     * но и терять его нельзя — значение запоминается в [pendingProsody] и применяется
     * при создании движка. Иначе человек, выбравший 1.3× до первого ответа, слушал бы
     * обычный темп и считал настройку сломанной.
     *
     * @param prosody темп и голос; `null` — вернуть умолчания.
     */
    fun setProsody(prosody: VoiceProsody?) {
        val value = prosody ?: VoiceProsody()
        pendingProsody = value
        runCatching { tts?.setProsody(value) }
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
        stopSpeechEnvelope()
        publishLevel(0f)
        if (state.phase == LocalVoicePhase.SPEAKING) emit(state.copy(phase = LocalVoicePhase.IDLE))
    }

    /** Пересчитать доступность: разрешение могли выдать или отозвать в настройках. */
    fun refresh() {
        if (released) return
        val wrapper = ensureEngine()
        emit(
            state.copy(
                micPermissionGranted = hasPermission(),
                // Системный распознаватель — тоже рабочий канал: без него кнопка пряталась бы
                // ровно там, где голос на самом деле работает (офлайн-модель ещё качается).
                asrAvailable = wrapper != null || platformRecognizerAvailable(),
                ttsAvailable = tts?.available ?: ttsPossible(),
            )
        )
    }

    /** Экран вернулся на передний план. */
    fun onResume() {
        if (released) return
        paused = false
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
        paused = true
        // Автостарт следующей реплики в фоне не нужен: микрофон в кармане — это не разговор.
        cancelContinuousRestart()
        stopSpeechEnvelope()
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
        if (activeController === this) activeController = null
        continuousConversation = false
        main.removeCallbacks(watchdogTick)
        main.removeCallbacks(thinkingHint)
        main.removeCallbacks(continuousRestart)
        stopSpeechEnvelope()
        hintScheduled = false
        destroyPlatformRecognizer()
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

    /**
     * Загрузить модель в фоне.
     *
     * @param warmUp `true` — загрузку начал [warmUpAsrInBackground], а не нажатие микрофона:
     *   тогда провал не должен звучать как «распознавание недоступно», если на устройстве есть
     *   системный распознаватель (голос всё равно работает).
     */
    private fun loadEngine(wrapper: StreamingStt, warmUp: Boolean = false) {
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
                        // Офлайн-модель не встала, но системный распознаватель есть: канал
                        // живой, и пугать человеком нечем.
                        if (warmUp && platformRecognizerAvailable()) {
                            emit(state.copy(asrAvailable = true, notice = null))
                        } else {
                            emit(state.copy(asrAvailable = false, notice = ASR_UNAVAILABLE))
                        }
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

    // ---------------------------------------------------------------------------------------
    // Системный распознаватель: мгновенный резерв, пока офлайн-модель качается
    // ---------------------------------------------------------------------------------------

    /**
     * Есть ли на устройстве системный распознаватель речи.
     *
     * Проверка обёрнута: на JVM/без сервиса распознавания вызов бросает, и падать из-за этого
     * контроллер не имеет права — он просто останется на офлайн-движке.
     */
    private fun platformRecognizerAvailable(): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(activity) }.getOrDefault(false)

    /**
     * Можно ли открыть реплику системным распознавателем прямо сейчас.
     *
     * Ровно те два условия, при которых резерв уместен: офлайн-движка нет ИЛИ он ещё не готов,
     * и порт НЕ передан извне. Порт из конструктора (`asrEngine`) — единственный путь для тестов
     * и повторного использования: подменять его системным сервисом значило бы менять поведение
     * проверяемого кода на телефоне и в JVM по-разному.
     */
    private fun platformFallbackAllowed(wrapper: StreamingStt?): Boolean {
        if (asrEngine != null) return false
        if (wrapper != null && wrapper.isReady) return false
        return platformRecognizerAvailable()
    }

    /**
     * Мгновенный резерв: системный `android.speech.SpeechRecognizer` на главном потоке.
     *
     * Зачем: офлайн-модель sherpa-onnx — 23 МиБ с GitHub. Пока она качается, кнопка микрофона
     * молчала 15–20 секунд, и человек считал голосовой ввод сломанным. Системный распознаватель
     * открывает реплику в ту же секунду, а модель догружается в фоне к следующему ходу.
     *
     * @return `true` — слушание уже открыто (фаза LISTENING), ждать загрузку не нужно.
     */
    private fun startPlatformSpeechRecognizer(): Boolean {
        if (released || !hasPermission()) return false
        val recognizer = platformAsr ?: runCatching {
            SpeechRecognizer.createSpeechRecognizer(activity).also { platformAsr = it }
        }.getOrNull() ?: return false
        val started = runCatching {
            recognizer.setRecognitionListener(platformListener)
            recognizer.startListening(platformIntent())
        }.isSuccess
        if (!started) {
            // Сервис распознавания отказал: объект мог остаться в нерабочем состоянии, второй
            // попытки на нём не будет — следующий тап создаст новый.
            destroyPlatformRecognizer()
            return false
        }
        platformListening = true
        // Новая реплика — новый финал: флаги прошлого хода не наследуются (как в [startCapture]).
        handledUtterance = false
        voiceTurn = false
        // Явное нажатие микрофона — это и есть перебивание: ответ прошлого хода замолкает.
        tts?.stopByUser()
        emit(state.copy(phase = LocalVoicePhase.LISTENING, partial = "", notice = null))
        return true
    }

    /** Интент системного распознавания: свободная речь, живой партиал, язык системы. */
    private fun platformIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
            runCatching { Locale.getDefault().toLanguageTag() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        }

    /** Первый (самый вероятный) вариант распознавания из бандла результатов. */
    private fun firstPlatformResult(results: Bundle?): String =
        runCatching {
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        }.getOrNull().orEmpty()

    /** Освободить системный распознаватель: сервис чужой, держать его после экрана нельзя. */
    private fun destroyPlatformRecognizer() {
        platformListening = false
        val current = platformAsr ?: return
        platformAsr = null
        runCatching { current.setRecognitionListener(null) }
        runCatching { current.cancel() }
        runCatching { current.destroy() }
    }

    /**
     * Колбэки системного распознавателя. Все приходят на главный поток, но [post] оставлен
     * намеренно: порядок «результат → финал» не должен зависеть от того, чем занят Looper.
     */
    private val platformListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = post {
            if (platformListening) {
                emit(
                    state.copy(
                        phase = LocalVoicePhase.LISTENING,
                        partial = "",
                        notice = LISTENING_NOTICE,
                    ),
                )
            }
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = post {
            // Реплика закрыта, текст считается: человеку нужно знать, что говорить уже не надо.
            if (platformListening) emit(state.copy(notice = TRANSCRIBING))
        }

        override fun onError(error: Int) = post {
            // Молчащий микрофон хуже честной подсказки: закрываем реплику и говорим «ещё раз».
            // Флаг снимается ВСЕГДА: иначе после ошибки системный распознаватель считался бы
            // «уже слушающим», и следующий тап по микрофону молча ничего не делал бы.
            val wasListening = platformListening
            platformListening = false
            if (wasListening && state.phase == LocalVoicePhase.LISTENING) {
                finishWith("")
            }
        }

        override fun onResults(results: Bundle?) = post {
            if (!platformListening) return@post
            platformListening = false
            finishWith(firstPlatformResult(results))
        }

        override fun onPartialResults(partialResults: Bundle?) = post {
            if (!platformListening) return@post
            val text = firstPlatformResult(partialResults)
            // Живой транскрипт: каждое распознанное слово видно сразу, а не после реплики.
            if (text.isNotBlank()) emit(state.copy(partial = text))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    // ---------------------------------------------------------------------------------------
    // Непрерывный разговор: микрофон открывается сам после ответа
    // ---------------------------------------------------------------------------------------

    /**
     * Поставить автостарт следующей реплики.
     *
     * Пауза [CONTINUOUS_RESTART_DELAY_MS] — не косметика: сразу после последнего сэмпла динамик
     * ещё отдаёт хвост и реверберацию, и открытый в тот же кадр микрофон записал бы собственный
     * ответ Марса как вопрос человека.
     */
    private fun scheduleContinuousListening() {
        if (released || paused || !continuousConversation) return
        // Ответ ещё не отдан в синтез: сейчас заговорит он, а не человек.
        if (answerPending) return
        if (state.phase != LocalVoicePhase.IDLE) return
        if (blockedByBackgroundConversation()) return
        main.removeCallbacks(continuousRestart)
        main.postDelayed(continuousRestart, CONTINUOUS_RESTART_DELAY_MS)
    }

    private fun cancelContinuousRestart() {
        main.removeCallbacks(continuousRestart)
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
    private fun startCapture(wrapper: StreamingStt, restarted: Boolean = false) {
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
        // Дальше громкость придёт из настоящего PCM: синтетическая огибающая больше не нужна.
        stopSpeechEnvelope()
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
                HandsFreeCapture.RawPcmListener { pcm, length ->
                    // Первый же буфер — доказательство, что сторожу микрофона нечего спасать.
                    micWatchdog.onAudioChunk()
                    publishMicLevel(pcm, length)
                    wrapper.acceptPcm(pcm, length)
                },
            )
        }.getOrDefault(false)
        if (!started) {
            runCatching { wrapper.cancelUtterance() }
            emit(state.copy(notice = MIC_UNAVAILABLE))
            return
        }
        capture = fresh
        emit(state.copy(phase = LocalVoicePhase.LISTENING, partial = "", notice = null))
        // Захват открылся — но это ещё не значит, что из него идёт звук (см. [MicCaptureWatchdog]).
        armMicWatchdog(restarted)
    }

    private fun closeCapture() {
        // Сторож живёт только рядом с живым захватом: без этого он «сдавался» бы уже после
        // закрытия микрофона и показывал человеку ложное «микрофон не отвечает».
        main.removeCallbacks(watchdogTick)
        val current = capture ?: return
        capture = null
        runCatching { current.cancel() }
        // Захват закрыт — источник громкости иссяк: орб не должен застыть на последнем слове.
        publishLevel(0f)
    }

    // ---------------------------------------------------------------------------------------
    // Громкость речи для неоновой пульсации орба
    // ---------------------------------------------------------------------------------------

    /**
     * Громкость буфера микрофона → [onAudioLevel].
     *
     * Зовётся с потока чтения (≈50 раз в секунду), поэтому: RMS считается по каждому четвёртому
     * отсчёту (точности хватает, цена копеечная), публикация троттлится до
     * [LEVEL_EMIT_MS], а состояние и View трогаются только через [post].
     */
    private fun publishMicLevel(pcm: ByteArray, length: Int) {
        val valid = minOf(length, pcm.size)
        if (valid < BYTES_PER_LEVEL_SAMPLE) return
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < valid) {
            // PCM16 LE: младший байт первым, старший со знаком.
            val sample = ((pcm[index].toInt() and 0xFF) or (pcm[index + 1].toInt() shl 8))
                .toShort()
                .toInt()
            sum += sample.toDouble() * sample
            count++
            index += LEVEL_SAMPLE_STRIDE
        }
        if (count == 0) return
        val rms = kotlin.math.sqrt(sum / count) / PCM_FULL_SCALE
        // Речь на телефоне даёт RMS 0.03..0.15 — без усиления орб почти не реагировал бы.
        val next = (rms * MIC_LEVEL_GAIN).toFloat().coerceIn(0f, 1f)
        val now = SystemClock.elapsedRealtime()
        if (now - lastLevelAt < LEVEL_EMIT_MS) return
        lastLevelAt = now
        publishLevel(next)
    }

    /** Отдать уровень подписчику (орбу). Всегда на главном потоке. */
    private fun publishLevel(value: Float) {
        val listener = onAudioLevel ?: return
        post { listener(value.coerceIn(0f, 1f)) }
    }

    /**
     * Синтетическая огибающая «речи» Марса 0..1.
     *
     * Три несоизмеримые частоты дают неровный ритм, похожий на фразы с паузами, а не на синус:
     * планета должна вспыхивать на словах и притухать между ними. Детерминированно, без
     * аллокаций и без Random (он не нужен там, где важна плавность).
     */
    private fun envelopeAt(phase: Float): Float {
        val syllables = kotlin.math.abs(kotlin.math.sin(phase * 2.9f))
        val phrases = kotlin.math.abs(kotlin.math.sin(phase * 0.63f))
        val breath = 0.55f + 0.45f * kotlin.math.sin(phase * 1.13f)
        return (ENVELOPE_BASE + ENVELOPE_SPAN * syllables * phrases * breath).coerceIn(0f, 1f)
    }

    private fun startSpeechEnvelope() {
        main.removeCallbacks(speechEnvelope)
        if (onAudioLevel == null) return
        main.postDelayed(speechEnvelope, SPEECH_TICK_MS)
    }

    private fun stopSpeechEnvelope() {
        main.removeCallbacks(speechEnvelope)
    }

    // ---------------------------------------------------------------------------------------
    // Сторож микрофона
    // ---------------------------------------------------------------------------------------

    /**
     * Захват открыт: ждём первый буфер не дольше [MIC_WATCHDOG_MS] и смотрим за часами.
     *
     * @param restarted `true` — это переоткрытие после [MicCaptureWatchdog.Decision.RESTART];
     *   тогда счётчик перезапусков НЕ сбрасывается, и второй провал заканчивается сдачей.
     */
    private fun armMicWatchdog(restarted: Boolean) {
        val now = watchdogClock()
        if (restarted) micWatchdog.onRestartStarted(now) else micWatchdog.onCaptureStarted(now)
        main.removeCallbacks(watchdogTick)
        main.postDelayed(watchdogTick, MicCaptureWatchdog.TICK_INTERVAL_MS)
    }

    /**
     * Тик сторожа. Решения принимает [MicCaptureWatchdog] (чистая машина, проверена тестом),
     * здесь только «что сделать руками»: перезапустить захват или честно сдаться.
     */
    private val watchdogTick = object : Runnable {
        override fun run() {
            if (released || capture?.isCapturing != true) return
            when (micWatchdog.tick(watchdogClock())) {
                MicCaptureWatchdog.Decision.WAIT -> {
                    // Звук уже пошёл — таймер больше не нужен, микрофон здоров.
                    if (!micWatchdog.healthy) main.postDelayed(this, MicCaptureWatchdog.TICK_INTERVAL_MS)
                }

                MicCaptureWatchdog.Decision.RESTART -> restartCapture()
                MicCaptureWatchdog.Decision.GIVE_UP -> failMicWatchdog()
            }
        }
    }

    /**
     * Один автоматический перезапуск захвата: закрыть и переоткрыть микрофон.
     *
     * Пауза [MIC_RESTART_PAUSE_MS] — не перестраховка: на Android поток чтения освобождает
     * ОБЩИЙ `AudioRecord` не мгновенно, и `init()`, сделанный сразу после `stop()`, теряет
     * новый захват (микрофон «открыт», буферов нет — ровно то, от чего сторож и поставлен).
     * Задержка сделана корутиной ([delay]), а не блокирующим сном: сон на главном потоке
     * подвесил бы интерфейс и не дал бы потоку чтения шанса закончить.
     */
    private fun restartCapture() {
        if (released || micRestartPending) return
        val wrapper = stt
        if (wrapper == null) {
            failMicWatchdog()
            return
        }
        micRestartPending = true
        closeCapture()
        scope.launch {
            delay(MIC_RESTART_PAUSE_MS)
            micRestartPending = false
            if (released || capture?.isCapturing == true) return@launch
            // Пока ждали, человек мог закончить реплику или экран уйти в фон — тогда не
            // переоткрываем: иначе микрофон ожил бы после явного «стоп».
            if (state.phase != LocalVoicePhase.LISTENING) return@launch
            if (blockedByBackgroundConversation()) return@launch
            startCapture(wrapper, restarted = true)
        }
    }

    /**
     * Второй провал: молчать нельзя. Захват закрывается, фаза возвращается в IDLE, а человек
     * видит причину — «Микрофон не отвечает — голосовой режим остановлен», а не пустой
     * индикатор «Слушаю».
     */
    private fun failMicWatchdog() {
        micWatchdog.reset()
        main.removeCallbacks(watchdogTick)
        closeCapture()
        runCatching { stt?.cancelUtterance() }
        handledUtterance = true
        emit(
            state.copy(
                phase = LocalVoicePhase.IDLE,
                partial = "",
                notice = MIC_NOT_RESPONDING,
            ),
        )
    }

    /** Реплика закрыта: микрофон освобождён, текст отдан один раз. */
    private fun finishWith(text: String) {
        if (handledUtterance) return
        handledUtterance = true
        platformListening = false
        closeCapture()
        runCatching { stt?.cancelUtterance() }
        val clean = text.trim()
        if (clean.isEmpty()) {
            emit(state.copy(phase = LocalVoicePhase.IDLE, partial = "", notice = NOT_RECOGNIZED))
            return
        }
        // Ход начат голосом: его ответ — единственный кандидат на озвучку ([onAnswerCompleted]).
        voiceTurn = true
        // Ответ ещё впереди: заглушка «Секунду, думаю.» может зазвучать, но микрофон до ответа
        // открывать нельзя ([scheduleContinuousListening]).
        answerPending = true
        // Новая голосовая реплика — новый ход: заглушка снова разрешена, старое состояние
        // потоковой озвучки не наследуется.
        hintSpoken = false
        hintScheduled = false
        streamedTurn = false
        // Фаза — THINKING, а НЕ IDLE: реплика уже ушла в чат, ответа ещё нет, и «Нажмите,
        // чтобы говорить» в этот момент прямо врало — человек ждал ответ, а не приглашение
        // начать заново. Кнопка при этом остаётся живой ([LocalVoiceButton.render]), поэтому
        // перебить ожидание новым вопросом по-прежнему можно одним тапом.
        emit(state.copy(phase = LocalVoicePhase.THINKING, partial = clean, notice = null))
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

        // Темп, выбранный ДО первого звука: применяется сразу, как только движок
        // появился. Без этого настройка терялась бы до второго запуска экрана.
        pendingProsody?.let { saved -> runCatching { created.setProsody(saved) } }
        tts = created
        emit(state.copy(ttsAvailable = created.available))
        return created
    }

    private fun onTtsSpeakingChanged(speaking: Boolean) {
        if (released) return
        if (speaking) {
            // Заговорил снова (вторая половина ответа после паузы) — отложенный автостарт
            // микрофона отменяется: иначе он открылся бы посреди речи Марса.
            cancelContinuousRestart()
            // Микрофон закрыт на время озвучки, поэтому громкость «речи» синтетическая:
            // орб должен дышать вместе с голосом, а не застывать.
            startSpeechEnvelope()
            // Микрофон уже открыт — человек говорит; фазу не перебиваем, иначе следующий тап
            // «закончить» ушёл бы в [stopSpeaking] и реплика потерялась бы.
            if (state.phase != LocalVoicePhase.SPEAKING && state.phase != LocalVoicePhase.LISTENING) {
                emit(state.copy(phase = LocalVoicePhase.SPEAKING, ttsAvailable = true))
            }
        } else {
            stopSpeechEnvelope()
            if (state.phase == LocalVoicePhase.SPEAKING) {
                emit(state.copy(phase = LocalVoicePhase.IDLE))
                // Непрерывный разговор: Марс договорил — микрофон открывается сам.
                scheduleContinuousListening()
            }
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
    // Подсказка вместо тишины на размышлениях
    // ---------------------------------------------------------------------------------------

    /**
     * Одноразовая заглушка «Секунду, думаю.».
     *
     * Почему не отдельный канал сигналов: заглушка — обычная речь, и она обязана уважать то же,
     * что ответ (маршрут вывода, AEC, отказ движка). Иначе она звучала бы из «правильного»
     * динамика с выключенным эхоподавлением и попадала бы в захват как голос человека.
     */
    private val thinkingHint = Runnable {
        hintScheduled = false
        if (released || !voiceTurn || hintSpoken) return@Runnable
        // Текст уже пошёл (или человек говорит) — заглушка не нужна и мешала бы.
        if (state.phase == LocalVoicePhase.SPEAKING || state.phase == LocalVoicePhase.LISTENING) {
            return@Runnable
        }
        val voice = ensureTts() ?: return@Runnable
        if (!voice.available) return@Runnable
        if (runCatching { voice.speak(THINKING_HINT_TEXT) }.getOrDefault(false)) hintSpoken = true
    }

    /** Снять поставленную, но ещё не прозвучавшую заглушку: текст появился первым. */
    private fun cancelThinkingHint() {
        if (!hintScheduled) return
        hintScheduled = false
        main.removeCallbacks(thinkingHint)
    }

    // ---------------------------------------------------------------------------------------
    // Мелочи
    // ---------------------------------------------------------------------------------------

    /** Часы сторожа: монотонные, не зависят от перевода системного времени. */
    private fun watchdogClock(): Long = SystemClock.elapsedRealtime()

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
        /**
         * Канал, который сейчас ведёт голосовой разговор на экране.
         *
         * Зачем глобальная точка, а не параметр: полноэкранный слой ([VoiceModeOverlay]) создаётся
         * хостом без ссылки на контроллер и обязан включать/выключать непрерывный разговор при
         * показе/скрытии. Ссылка на последний созданный канал — единственный способ сделать это
         * без правки хоста и без второго владельца микрофона.
         */
        @Volatile
        private var activeController: LocalVoiceController? = null

        /** Активный голосовой канал (последний созданный и не освобождённый); `null` — нет. */
        fun active(): LocalVoiceController? = activeController

        /** requestCode рантайм-запроса `RECORD_AUDIO` для локального чата. */
        const val REQUEST_RECORD_AUDIO: Int = 4711

        const val NOT_RECOGNIZED: String = "Не расслышал — попробуйте ещё раз"
        const val MIC_UNAVAILABLE: String = "Микрофон недоступен"
        const val ASR_UNAVAILABLE: String = "Распознавание речи недоступно на этом устройстве"
        const val PREPARING: String = "Готовлю распознавание…"
        const val PERMISSION_ASKED: String = "Разрешите доступ к микрофону"
        const val PERMISSION_DENIED: String = "Без доступа к микрофону голосовой ввод не работает"
        const val TTS_UNAVAILABLE: String = "Озвучка недоступна на этом устройстве"

        /** Прогрев офлайн-движка: человек видит, что идёт подготовка, а не что кнопка мертва. */
        const val WARMING_UP: String = "Готовлю офлайн-распознавание речи…"

        /** Системный распознаватель услышал конец фразы и считает текст. */
        const val TRANSCRIBING: String = "Распознаю сказанное…"

        /** Микрофон системного распознавателя открыт: тот же словарь, что у кнопки композера. */
        const val LISTENING_NOTICE: String = "Слушаю — говорите…"

        /** Микрофон держит фоновый сервис: второй захват отобрал бы у него звук. */
        const val BACKGROUND_LISTENING: String = "Слушаю в фоне — говорите после „Марс, слушай“"

        /**
         * Захват открылся, но за [MIC_WATCHDOG_MS] из него не пришло ни одного буфера —
         * одна попытка перезапуска, потом [MIC_NOT_RESPONDING]. Порог именно три секунды:
         * человек уже сказал бы первое слово, а ждать дольше значит держать его в «Слушаю».
         */
        const val MIC_WATCHDOG_MS: Long = 3_000L

        /** Сообщение человеку, когда микрофон молчит и после перезапуска. */
        const val MIC_NOT_RESPONDING: String = "Микрофон не отвечает — голосовой режим остановлен"

        /**
         * Пауза между освобождением и переоткрытием общего `AudioRecord`. Короче — новый
         * захват теряется, длиннее — слышимая дыра в реплике.
         */
        const val MIC_RESTART_PAUSE_MS: Long = 150L

        /**
         * Пауза перед автостартом следующей реплики в непрерывном разговоре.
         *
         * 400 мс — не «чтобы было плавно»: динамик после последнего сэмпла ещё отдаёт хвост и
         * реверберацию комнаты, и микрофон, открытый в тот же кадр, записал бы собственный ответ
         * Марса как вопрос человека.
         */
        const val CONTINUOUS_RESTART_DELAY_MS: Long = 400L

        /**
         * Троттлинг уровня громкости: поток чтения микрофона даёт ~50 буферов в секунду, а орбу
         * хватает 14 обновлений — остальное сглаживается на кадре.
         */
        private const val LEVEL_EMIT_MS = 70L

        /** Каждый восьмой байт: одно 16-битное отсчёта из четырёх — точности RMS хватает. */
        private const val LEVEL_SAMPLE_STRIDE = 8
        private const val BYTES_PER_LEVEL_SAMPLE = 8

        /** Полная шкала PCM16 и усиление: речь даёт RMS 0.03..0.15, без усиления орб молчит. */
        private const val PCM_FULL_SCALE = 32768.0
        private const val MIC_LEVEL_GAIN = 5.5f

        /** Шаг синтетической огибающей речи: 90 мс — «слог», а не дрожание. */
        private const val SPEECH_TICK_MS = 90L
        private const val SPEECH_TICK_PHASE = 0.085f

        /** Нижняя граница и размах огибающей: даже в паузе планета тлеет, а не гаснет. */
        private const val ENVELOPE_BASE = 0.28f
        private const val ENVELOPE_SPAN = 0.72f

        /** Заглушка на размышлениях модели: одна короткая фраза вместо тишины. */
        const val THINKING_HINT_TEXT: String = "Секунду, думаю."

        private const val THREAD_ASR_LOAD = "mars-local-voice-asr-load"

        /**
         * Задержка перед заглушкой: быстрая модель успевает выдать первый кусок, и подсказка
         * не звучит вовсе — «не произносить, если текст появился сразу».
         */
        private const val THINKING_HINT_DELAY_MS = 700L
    }
}
