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
import ru.mars.mobile.asr.ModelDownloader
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
 * ## Два способа перебить ответ — и они не смешиваются
 * - **жест**: тап по микрофону или орбу зовёт [stopSpeaking] — звук гаснет немедленно,
 *   доказательств не требуется. Намерение «молчи» ([AnswerSpeechGate]) держится до конца ЭТОГО
 *   ответа: иначе следующая дельта потоковой озвучки включала бы звук обратно, и кнопка «Стоп»
 *   выглядела бы сломанной;
 * - **голос**: пока звучит ОТВЕТ, микрофон открывается/остаётся открытым ([armBargeInMic]), а
 *   подтверждает перебивание [BargeInGate] по длительности речи (и по тексту, если он есть).
 *
 * ## Где акустического перебивания нет и почему
 * Дуплекса на динамике не будет: микрофон слышит собственную озвучку, и «человек говорит 900 мс»
 * там ничего не доказывает. Поэтому [BargeInMicPolicy] требует одновременно трёх вещей:
 * - **доказанный** маршрут в наушники ([ActiveOutput]), а не «в списке доступных есть гарнитура»:
 *   воткнутая гарнитура не значит, что звук идёт в неё. Нет доказательства — автоперебивание
 *   выключено, и это штатное состояние, а не деградация;
 * - что звучит именно ОТВЕТ ([SpeechPurpose]): заглушка «Секунду, думаю.» и предпросмотр
 *   («Озвучить» у сообщения) микрофон не открывают — перебивать там нечего, а захваченное ими
 *   уходило бы в чат новым вопросом;
 * - что экран на переднем плане и микрофон не занят фоновым сервисом.
 * Захват, открытый ради предбуфера ([MicMode.PRE_ROLL]), микрофоном перебивания не считается: он
 * копит звук для реплики человека, и ответ Марса, записанный в него, стал бы её началом. Сторож
 * микрофона ([MicCaptureWatchdog]) к захвату перебивания не применяется вовсе: тишина там — норма
 * (человек слушает), а не мёртвый `AudioRecord`.
 *
 * ## Два канала распознавания
 * Основной — офлайн-движок sherpa-onnx ([StreamingAsrEnginePort]): приватно, без сети, с живым
 * партиалом. Системный `android.speech.SpeechRecognizer` ([startPlatformSpeechRecognizer])
 * остаётся резервом ровно для одного случая: офлайн-модели НЕТ на диске. Если файлы модели уже
 * скачаны, резерв выключен даже при неготовом движке — биндинг к сервису Google стоит 600–900 мс
 * и съедает первое слово, тогда как загрузка ONNX с прогретым предбуфером микрофона не теряет
 * ничего. Если порт движка передан в конструктор ([asrEngine]), резерв выключен тоже: путь ровно
 * один, и он проверяемый.
 *
 * ## Первое слово
 * Две причины, по которым оно раньше пропадало, закрыты в двух местах:
 * - холодное окно фич нативной модели — подушка тишины в [ru.mars.mobile.asr.SherpaAsrBackend];
 * - задержка загрузки движка — [PreRollAudioBuffer]: микрофон открывается СРАЗУ, PCM копится в
 *   кольцевом предбуфере, а в момент готовности движка уходит в стрим перед живыми байтами
 *   ([startCapture]).
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
    /**
     * Акустическое перебивание: микрофон остаётся/открывается, пока Марс говорит.
     *
     * Решение «можно ли» принимает [BargeInMicPolicy], и одного этого флага мало: нужен ещё
     * ДОКАЗАННЫЙ активный маршрут в наушники ([ActiveOutput]), речь именно ответа ([SpeechPurpose])
     * и передний план экрана. Поэтому `true` по умолчанию безопасно: оно ничего не включает там,
     * где перебивание нельзя доказать, и не включает дуплекс на динамике.
     */
    private val bargeInDuringSpeech: Boolean = true,
    /**
     * Реплику произнёс не выбранный голос, а резерв ([VoiceSubstitution]).
     *
     * Событие уходит хосту, а не глотается здесь: подмена голоса — не ошибка, но и не
     * секрет. Без этой проводки человек, выбравший «Алису», слышал робота Android и не знал,
     * почему (подтверждённый дефект: колбэк был объявлен в [LocalVoiceTts], но хост его не
     * передавал). Экран показывает текст из [VoiceSubstitutionNotice].
     */
    private val onVoiceSubstituted: (VoiceSubstitution) -> Unit = {},
    /**
     * Источник громкости ИГРАЮЩЕГО аудио ([SpeechLevelSource]): пока Марс говорит, орб пульсирует
     * РЕАЛЬНЫМ звуком, а не синтетической огибающей.
     *
     * `null` по умолчанию — поведение прежнее (огибающая), и существующие сборки контроллера без
     * Android продолжают работать. Источник включается ТОЛЬКО на время фазы SPEAKING и только если
     * [SpeechLevelSource.start] вернул `true`: молчаливый отказ эффекта (занят другим приложением)
     * не имеет права оставить орб без движения.
     */
    private val levelSource: SpeechLevelSource? = null,
    /**
     * Сессия играющего аудио на текущий момент (её даёт хост из плеера/Piper).
     *
     * Лямбда, а не число: сессия выдаётся платформой при старте конкретной реплики и меняется от
     * фразы к фразе, а контроллер узнаёт о речи раньше, чем у него появляется сессия. `null` или
     * `0` в ответ означают «мерить нечего» — остаётся резервная огибающая.
     */
    private val audioSessionProvider: (() -> Int?)? = null,
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

    /**
     * Кольцевой предбуфер микрофона: PCM, записанный ПОКА движок грузил модель.
     *
     * Зачем. `StreamingStt` принимает звук только внутри открытой реплики, а реплику нельзя
     * открыть до готовности офлайн-модели (23 МиБ ONNX читаются 0.3–1.5 с). Раньше эти
     * миллисекунды просто пропадали — вместе с первым словом человека, который начал говорить
     * сразу, как увидел орб. Теперь микрофон открывается сразу, а накопленное уходит в стрим
     * перед живыми байтами.
     */
    private var preRoll: PreRollAudioBuffer? = null

    /**
     * Захват уже отдаёт PCM напрямую в движок (реплика открыта). `false` — микрофон открыт
     * только ради предбуфера, пока грузится модель.
     *
     * `@Volatile`, потому что это единственный признак, отличающий рабочий захват от предбуфера:
     * по нему [micMode] решает, можно ли считать открытый микрофон микрофоном перебивания.
     */
    @Volatile
    private var streamingToEngine = false

    /**
     * Замок перехода «предбуфер → движок».
     *
     * Держится и вокруг записи в предбуфер, и вокруг самого перехода: чанк, пришедший ровно
     * в момент старта реплики, обязан встать в стрим ПОСЛЕ накопленного, иначе первые слова
     * прозвучали бы в обратном порядке и распознавание получило бы кашу.
     */
    private val pcmLock = Any()

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

    /**
     * Намерение человека «молчи» на ТЕКУЩИЙ ответ (см. [AnswerSpeechGate]).
     *
     * Отдельно от [stopSpeaking]: стоп звука зовут и внутренние пути (новый ход, уход экрана,
     * закрытие режима), и они НЕ означают «человек попросил тишины». Слить эти два случая в
     * один флаг значило бы глушить озвучку каждого следующего ответа после любого `onPause`.
     */
    private val speechGate = AnswerSpeechGate()

    /**
     * Захват открыт НАМЕРЕННО во время озвучки — для перебивания голосом.
     *
     * Отличается от «микрофон открыт» принципиально: такой захват нельзя закрывать в
     * [closeMicForAssistantSpeech] (там закрытие защищает от эха, а на наушниках эха нет и
     * закрытие убивало бы ровно ту реплику, которой человек перебивает).
     */
    private var bargeInMicOpen = false

    /**
     * Захват открыт ИМЕННО ради перебивания, а не человеком ради реплики.
     *
     * Нужен на конце озвучки: микрофон, который подняли только для доказательств перебивания, а
     * человек так и не заговорил, обязан закрыться. Иначе после ответа он оставался бы слушать —
     * в обычном чате это нарушает «одна реплика на одно нажатие», а первая же случайная фраза
     * уходила бы в чат вопросом.
     */
    private var bargeInOwnedCapture = false

    /**
     * Политика перебивания текущего ответа ([BargeInGate]) — та же, что решает судьбу озвучки.
     *
     * Контроллер держит ссылку не ради дублирования решений: [BargeInGate.acceptedCount] — это
     * единственный признак «озвучка замолчала потому, что человек перебил», доступный ВНУТРИ
     * колбэка «звук кончился». Счётчик растёт до остановки звука, а результат `offerSpeech`
     * возвращается уже после этого колбэка, поэтому по нему решение принималось бы на шаг позже —
     * и захват перебивания закрывался бы вместе с речью человека.
     */
    private val bargeInGate = BargeInGate()

    /**
     * Что именно звучит сейчас: ответ, заглушка или предпросмотр ([SpeechPurpose]).
     *
     * Ставится ровно перед тем вызовом, который запускает речь, и снимается на её конце. Без него
     * [armBargeInMic] не отличал ответ от «Секунду, думаю.» и от пункта «Озвучить» — и открывал
     * микрофон там, где перебивать нечего, а записанное уходило в чат новым вопросом.
     */
    private var speechPurpose: SpeechPurpose = SpeechPurpose.NONE

    /**
     * Второй наблюдатель состояния — для полноэкранного слоя ([VoiceModeOverlay]).
     *
     * Зачем отдельный канал, если есть `onState` в конструкторе. `onState` принадлежит хосту
     * (кнопка композера + строка статуса) и не перерисовывает слой: слой обновлялся только из
     * `LocalChatActivity.render`, то есть на изменение ЛЕНТЫ. Нажатие «Стоп» ленту не меняет —
     * орб оставался в «Говорю…» с живой кнопкой «Стоп», хотя звук уже заглушён. Это и выглядело
     * как «кнопка стоп не работает».
     */
    var onStateObserver: ((LocalVoiceState) -> Unit)? = null

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
     * Когда отзвучал последний сэмпл речи Марса (монотонные часы, [watchdogClock]).
     *
     * Нужен, чтобы пауза [CONTINUOUS_RESTART_DELAY_MS] отсчитывалась от РЕАЛЬНОГО конца звука, а не
     * от момента, когда о конце сообщил движок: между ними хвост аудиобуфера `AudioTrack` и
     * реверберация комнаты, и открытый в этот кадр микрофон записал бы собственный ответ Марса как
     * вопрос человека. `0` — речи в этой сессии ещё не было, ждать нечего.
     */
    private var lastSpeechEndedAtMs: Long = 0L

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
    private val continuousRestart: Runnable = object : Runnable {
        override fun run() {
            // Все условия ещё раз: пока тикал таймер, ответ мог зазвучать, а человек — начать реплику.
            if (!canRestartContinuous()) return
            // Хвост речи ещё не затих: добираем остаток паузы, а не открываем микрофон раньше срока.
            val remaining = ContinuousListenPolicy.remainingQuietMs(
                lastSpeechEndedAtMs = lastSpeechEndedAtMs,
                nowMs = watchdogClock(),
                delayMs = CONTINUOUS_RESTART_DELAY_MS,
            )
            if (remaining > 0L) {
                main.removeCallbacks(this)
                main.postDelayed(this, remaining)
                return
            }
            if (blockedByBackgroundConversation()) return
            startListening()
        }
    }

    /**
     * Живая громкость речи 0..1: сырой RMS микрофона во время реплики человека и громкость
     * ИГРАЮЩЕГО звука во время ответа Марса ([SpeechLevelSource]); синтетическая огибающая — только
     * резерв, если измерить вывод не удалось.
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
     * Чей уровень громкости рисует орб во время речи Марса: реальный (Visualizer) или резервный
     * (синтетическая огибающая).
     *
     * Политика вынесена в чистый [SpeechLevelRouter], потому что цена ошибки здесь — «планета
     * застыла» либо «планета дёргается на битом значении». На JVM это проверяется фейком источника,
     * без `Visualizer` и без `Handler`.
     */
    private val speechLevels = SpeechLevelRouter(
        source = levelSource,
        sessionId = { audioSessionProvider?.invoke() },
        onLevel = { level -> publishLevel(level) },
        // Те же монотонные часы, что у сторожа: повторы источника считаются по ним.
        nowMs = { watchdogClock() },
    )

    /**
     * Огибающая «речи» Марса, пока звучит ответ.
     *
     * Резерв, а не основной путь. Реальную громкость даёт [SpeechLevelSource] (см.
     * [startSpeechLevel]); огибающая включается только если источник недоступен: эффект занят
     * другим приложением, сессия ещё не выдана, `Visualizer` запрещён политикой устройства. Тогда
     * лучше синтетический ритм, чем мёртвая планета.
     *
     * Объект, а не лямбда: `main.postDelayed(this, …)` обязан получить сам Runnable, а в лямбде
     * `this` указывает на контроллер.
     */
    private val speechEnvelope = object : Runnable {
        override fun run() {
            if (released || state.phase != LocalVoicePhase.SPEAKING) return
            // Источник мог опоздать: SPEAKING включается на ПРИНЯТОМ синтезе, а сессию платформа
            // выдаёт позже — когда `ExoPlayer` реально начнёт играть. Пока тикает резерв, политика
            // изредка пробует источник снова и, если он ожил, синтетика прекращается совсем.
            if (speechLevels.retry()) return
            // `null` — источник жив: считать синтетику больше не нужно.
            val level = speechLevels.envelopeLevel {
                speechPhase += SPEECH_TICK_PHASE
                envelopeAt(speechPhase)
            } ?: return
            onAudioLevel?.invoke(level)
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
            // Модель (23 МиБ) может докачиваться. Микрофон открываем СРАЗУ и копим PCM в
            // предбуфере: человек начинает говорить в первую же секунду, и это первое слово
            // раньше пропадало целиком (см. [preRoll]).
            pendingStart = true
            emit(state.copy(notice = PREPARING))
            loadEngine(wrapper)
            startPreRollCapture()
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
        // Новый ход снимает «молчи»: стоп относился к прошлому ответу, а не к диалогу.
        speechGate.onNewTurn()
        // Внутренняя тишина, а не [stopSpeaking]: новый ход — не просьба человека замолчать,
        // иначе первый же `send()` глушил бы озвучку своего же ответа.
        silenceSpeech()
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
        if (!speechGate.canSpeak(speakAnswers, voiceTurn)) return false
        val voice = ensureTts() ?: return false
        if (!voice.available) return false
        // Ответ будет озвучен — значит микрофон обязан быть закрыт ДО первого слова. Захват,
        // оставшийся открытым, услышал бы этот ответ и отправил его новым вопросом.
        closeMicForAssistantSpeech()
        cancelThinkingHint()
        streamedTurn = false
        // Что звучит — ответ, а не заглушка и не предпросмотр: только на это [armBargeInMic]
        // имеет право открыть микрофон для перебивания.
        speechPurpose = SpeechPurpose.ANSWER
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
        if (!speechGate.canSpeak(speakAnswers, voiceTurn)) return false
        if (delta.isNullOrEmpty()) return false
        val voice = tts ?: return false
        // Первый же кусок означает «сейчас зазвучит речь»: открытый микрофон надо закрыть до него,
        // иначе ранний старт озвучки сам себя запишет.
        closeMicForAssistantSpeech()
        cancelThinkingHint()
        // Дельты приходят только на ответ — но помечаем явно: от этой метки зависит, можно ли
        // держать микрофон открытым во время речи.
        speechPurpose = SpeechPurpose.ANSWER
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
        // Человек заглушил этот ответ кнопкой «Стоп». Читать его целиком сейчас нельзя — это и
        // есть дефект «нажал стоп, а он всё равно заговорил»: ответ дочитывался с начала.
        // Намерение живёт один ответ, поэтому здесь же снимается.
        if (speechGate.mutedByUser) {
            speechGate.onNewTurn()
            streamedTurn = false
            returnToRest()
            return
        }
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
        if (!speechGate.canSpeak(speakAnswers, fromVoice) || body.isEmpty()) {
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
        // Это ответ текущего голосового хода — единственная речь, которую можно перебить голосом.
        speechPurpose = SpeechPurpose.ANSWER
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
        // Ответ был заглушён человеком: хвоста нет и читать его заново нельзя. Но диалог обязан
        // продолжиться — иначе после «Стоп» микрофон не открылся бы до следующего тапа, и стоп
        // превращался бы в «выключить разговор».
        if (speechGate.mutedByUser) {
            speechGate.onNewTurn()
            streamedTurn = false
            returnToRest()
            return
        }
        if (!streamedTurn) return
        streamedTurn = false
        // Хвост ответа — тоже ответ: перебивать его голосом можно на тех же условиях.
        speechPurpose = SpeechPurpose.ANSWER
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

    /**
     * Сессия звука, который играет сейчас, или `null` — для [SpeechLevelSource].
     *
     * Не создаёт движок: если озвучка ещё не поднята, значит и звука нет, и поднимать её ради
     * вопроса «какая у тебя сессия» значило бы заводить `AudioTrack` на пустом месте.
     */
    fun currentAudioSessionId(): Int? =
        runCatching { tts?.currentAudioSessionId() }.getOrNull()

    /** Озвучить произвольный текст (пункт «Озвучить» у сообщения). */
    fun speakNow(text: String?) {
        if (released) return
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) return
        // Явная просьба «прочитай» сильнее прошлого «молчи»: человек сам нажал кнопку.
        speechGate.onNewTurn()
        val voice = ensureTts()
        if (voice == null || !voice.available) {
            emit(state.copy(ttsAvailable = false, notice = TTS_UNAVAILABLE))
            return
        }
        // Речь с динамика в открытый микрофон попадать не должна — ни при каком пути к озвучке.
        closeMicForAssistantSpeech()
        // Это ПРЕДПРОСМОТР сообщения, а не ответ на вопрос: перебивать его голосом нечего, и
        // микрофон под него не открывается ([BargeInMicPolicy]).
        speechPurpose = SpeechPurpose.PREVIEW
        if (runCatching { voice.speak(body) }.getOrDefault(false)) {
            emit(state.copy(phase = LocalVoicePhase.SPEAKING, ttsAvailable = true, notice = null))
        }
    }

    /**
     * Немедленная тишина по воле человека: кнопка «Стоп» на орбе, тап по микрофону во время
     * ответа, кнопка композера.
     *
     * Отличие от [silenceSpeech] — намерение. Здесь человек просит тишины, и она держится до
     * конца ЭТОГО ответа: иначе следующий же кусок потоковой озвучки включал бы звук обратно, и
     * кнопка выглядела бы сломанной (звук пропадал на полсекунды и возвращался).
     *
     * Микрофон при этом не закрывается, если он уже открыт: «стоп» — это «замолчи и слушай
     * меня», а не «выключи разговор».
     */
    fun stopSpeaking() {
        speechGate.onUserStop()
        silenceSpeech()
        returnToRest()
    }

    /**
     * Тишина без изменения намерения: новый ход, уход экрана в фон, закрытие режима.
     *
     * Не трогает [AnswerSpeechGate]: эти пути не значат «человек попросил молчания», и если бы
     * они взводили флаг, озвучка пропала бы до конца жизни контроллера.
     */
    private fun silenceSpeech() {
        val wasSpeaking = tts?.isSpeaking == true || state.phase == LocalVoicePhase.SPEAKING
        // Речь гасится своими руками — «что звучало» больше не актуально; следующий путь к
        // озвучке выставит метку сам.
        speechPurpose = SpeechPurpose.NONE
        tts?.stopByUser()
        stopSpeechEnvelope()
        publishLevel(0f)
        // Заглушили своими руками — это тоже конец звука: пауза перед автостартом микрофона
        // отсчитывается от него, а не от последнего слова.
        if (wasSpeaking) lastSpeechEndedAtMs = watchdogClock()
        if (state.phase == LocalVoicePhase.SPEAKING) emit(state.copy(phase = LocalVoicePhase.IDLE))
    }

    /**
     * Вернуть канал в «готов слушать»: открытый захват остаётся слушать, закрытый — открывается
     * сам в непрерывном разговоре.
     *
     * Нужен там, где звук кончился не по концу речи (стоп человека, конец заглушённого ответа,
     * конец озвучки): фаза обязана отражать ФАКТИЧЕСКОЕ состояние микрофона, иначе орб пишет
     * «Нажмите, чтобы говорить», пока микрофон уже слушает.
     */
    private fun returnToRest() {
        if (released) return
        if (capture?.isCapturing == true) {
            if (state.phase != LocalVoicePhase.LISTENING) {
                emit(state.copy(phase = LocalVoicePhase.LISTENING, partial = "", notice = null))
            }
            return
        }
        if (state.phase == LocalVoicePhase.SPEAKING) emit(state.copy(phase = LocalVoicePhase.IDLE))
        scheduleContinuousListening()
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
        // Гасим захват в ЛЮБОЙ фазе: при перебивании он открыт и в SPEAKING, а `onPause` —
        // это «экран ушёл в фон», где микрофон не должен работать ни под какой подписью.
        cancelListening()
        // Микрофон мог быть открыт ради предбуфера (модель ещё грузится): в фоне он не нужен,
        // а накопленное всё равно уже не «начало фразы».
        abandonPreRoll()
        if (MarsVoiceService.conversationActive) return
        // Уход экрана — не просьба молчать: флаг «стоп» здесь не взводится, иначе вернувшись
        // человек не услышал бы ни одного ответа до перезапуска экрана.
        silenceSpeech()
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
        // Наблюдатель слоя держит ссылку на View-дерево экрана: после освобождения контроллера
        // он не должен вызываться ни разу.
        onStateObserver = null
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
                        // Микрофон мог быть открыт ради предбуфера — движка нет, значит и речи
                        // не будет: закрываем, а не держим открытым «в никуда».
                        abandonPreRoll()
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
                            // Экран ушёл в фон, пока грузилась модель: микрофон в кармане — не
                            // разговор, предбуфер выбрасываем.
                            if (paused) {
                                abandonPreRoll()
                            } else if (hasPermission()) {
                                startCapture(wrapper)
                            } else {
                                startListening()
                            }
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
     * Ровно те условия, при которых резерв уместен: офлайн-движка нет ИЛИ он ещё не готов,
     * офлайн-модели на диске НЕТ, и порт НЕ передан извне. Порт из конструктора (`asrEngine`) —
     * единственный путь для тестов и повторного использования: подменять его системным сервисом
     * значило бы менять поведение проверяемого кода на телефоне и в JVM по-разному.
     *
     * Проверка «модель уже на диске» здесь ключевая. Системный `SpeechRecognizer` биндится к
     * сервису Google 600–900 мс и начинает отдавать звук только после `onReadyForSpeech` — за
     * это время человек уже сказал первое слово, и оно не возвращается. Если модель скачана,
     * ждать её загрузки (с предбуфером микрофона!) дешевле, чем терять начало фразы.
     */
    private fun platformFallbackAllowed(wrapper: StreamingStt?): Boolean {
        if (asrEngine != null) return false
        if (wrapper != null && wrapper.isReady) return false
        if (asrModelInstalled()) return false
        return platformRecognizerAvailable()
    }

    /**
     * Офлайн-модель ASR уже лежит на диске (проверка пяти файлов, без сети).
     *
     * Именно «на диске», а не «движок готов»: движок поднимается секунды, а файлы — это факт,
     * который решает, стоит ли уходить на медленный системный распознаватель.
     */
    private fun asrModelInstalled(): Boolean =
        runCatching { ModelDownloader.forApp(activity.applicationContext).isAvailable() }
            .getOrDefault(false)

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
     * ответ Марса как вопрос человека. Поэтому мало «подождать таймер» — надо ещё, чтобы к моменту
     * старта ОТВЕТ ДЕЙСТВИТЕЛЬНО КОНЧИЛСЯ: ответ, ещё не отданный в синтез, звучащую речь и
     * незакрытый поток дельт отсекает [canRestartContinuous], а остаток паузы добирает
     * [continuousRestart].
     */
    private fun scheduleContinuousListening() {
        if (!canRestartContinuous()) return
        if (blockedByBackgroundConversation()) return
        main.removeCallbacks(continuousRestart)
        main.postDelayed(continuousRestart, CONTINUOUS_RESTART_DELAY_MS)
    }

    /**
     * Можно ли открывать микрофон сам, без нажатия.
     *
     * Чистое решение вынесено в [ContinuousListenPolicy] — оно проверяется юнит-тестом, а не только
     * повторной приёмкой на телефоне. Здесь только сбор фактов: «идёт ход», «идёт потоковая
     * озвучка» и «движок реально говорит» — три разные причины молчать, и каждая из них раньше
     * открывала микрофон посреди речи Марса.
     */
    private fun canRestartContinuous(): Boolean = ContinuousListenPolicy.canRestart(
        released = released,
        paused = paused,
        continuous = continuousConversation,
        answerPending = answerPending,
        streamedTurn = streamedTurn,
        ttsSpeaking = tts?.isSpeaking == true,
        phase = state.phase,
    )

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

    /**
     * Поток чтения микрофона: пока движок грузится — в предбуфер, после — прямо в распознаватель.
     *
     * Решение «куда» принимается под [pcmLock] вместе с переходом ([startCapture]): иначе чанк,
     * пришедший ровно в момент старта реплики, попал бы в стрим раньше накопленного предбуфера,
     * и первые слова встали бы в обратном порядке.
     */
    private val rawPcmListener = HandsFreeCapture.RawPcmListener { pcm, length ->
        // Первый же буфер — доказательство, что сторожу микрофона нечего спасать.
        micWatchdog.onAudioChunk()
        publishMicLevel(pcm, length)
        synchronized(pcmLock) {
            val buffer = preRoll
            if (!streamingToEngine && buffer != null) {
                buffer.append(pcm, length)
            } else {
                stt?.acceptPcm(pcm, length)
            }
        }
    }

    /**
     * Открыть микрофон ДО готовности движка и копить PCM в предбуфере.
     *
     * Зачем не ждать загрузки: человек, увидев орб, говорит сразу, а ONNX читаются 0.3–1.5 с.
     * Раньше эта речь пропадала целиком — вместе с первым словом. Теперь микрофон уже открыт, и
     * в момент готовности движка накопленное уйдёт в стрим первым.
     *
     * Фаза LISTENING здесь НЕ ставится: распознавателя ещё нет, и «Слушаю» было бы ложью.
     * Человек видит [PREPARING], а нажатие микрофона в это время ничего не ломает —
     * [startListening] видит живой захват и не открывает второй.
     */
    private fun startPreRollCapture() {
        if (released) return
        if (capture?.isCapturing == true) return
        if (blockedByBackgroundConversation()) return
        tts?.stopByUser()
        stopSpeechEnvelope()
        handledUtterance = false
        voiceTurn = false
        val buffer = preRoll ?: PreRollAudioBuffer().also { preRoll = it }
        buffer.clear()
        streamingToEngine = false
        val fresh = HandsFreeCapture()
        val started = runCatching { fresh.start(captureListener, rawPcmListener) }.getOrDefault(false)
        if (!started) {
            // Микрофон не открылся: движок всё равно грузится, и [startCapture] попробует снова
            // (тогда провал будет честно показан как [MIC_UNAVAILABLE]).
            preRoll = null
            return
        }
        capture = fresh
        armMicWatchdog(false)
    }

    /**
     * Выбросить предбуфер и закрыть микрофон, если он был открыт только ради него.
     *
     * Нужно там, где реплика отменяется до готовности движка: уход с экрана, провал загрузки
     * модели. Открытый «в никуда» микрофон — это зелёный индикатор, который врёт.
     */
    private fun abandonPreRoll() {
        val buffer = preRoll ?: return
        buffer.clear()
        if (!streamingToEngine) closeCapture()
    }

    /**
     * Микрофон + сырой PCM в движок. Маршрут захвата — дефолт [AndroidAudioSourceFactory].
     *
     * Порядок здесь не косметический:
     * 1. `startUtterance()` ДО первого байта с микрофона — иначе [StreamingStt] выбросит
     *    буферы, пришедшие вне реплики, а это и есть начало фразы;
     * 2. предбуфер отдаётся ДО живых байтов (под [pcmLock]) — иначе первое слово встало бы в
     *    очередь стрима после второй фразы;
     * 3. если захват уже открыт ради предбуфера, он НЕ переоткрывается: новый `AudioRecord`
     *    потерял бы всё, что человек успел сказать, пока грузилась модель.
     */
    private fun startCapture(wrapper: StreamingStt, restarted: Boolean = false) {
        if (released) return
        // Отложенный автостарт после загрузки модели идёт мимо [startListening] — проверяем и здесь,
        // иначе сервис, поднявшийся за время загрузки, потерял бы микрофон.
        if (blockedByBackgroundConversation()) {
            abandonPreRoll()
            return
        }
        // Предбуфер или рабочий захват — решает [micMode], а не «захват открыт»: различие между
        // ними и есть то место, где терялось начало фразы.
        val buffering = micMode() == MicMode.PRE_ROLL
        // Живой захват не переоткрываем (второй `AudioRecord` отобрал бы звук у первого), но и
        // не выходим молча: перед новым захватом прошлый ОБЯЗАН быть освобождён — иначе тап
        // выглядит мёртвым, а `handledUtterance` не сбрасывается.
        if (capture?.isCapturing == true && !buffering) closeCapture()
        // Явное нажатие микрофона — это и есть перебивание: доказательств речи не требуется.
        tts?.stopByUser()
        // Дальше громкость придёт из настоящего PCM: синтетическая огибающая больше не нужна.
        stopSpeechEnvelope()
        /*
         * Микрофон открывается ДО реплики, а не после.
         *
         * `AudioRecord` (с AEC/NS/AGC — их включает [AudioCapturePolicy]) стартует 100–300 мс:
         * `create()` + `startRecording()` на аппаратном тракте не мгновенные. Пока порядок был
         * «startUtterance → открыть микрофон», всё, что человек сказал в это окно, физически не
         * записывалось — терялось начало фразы (жалоба «пропускает первое слово»). Теперь
         * запись стартует первой, а звук этого окна копится в [PreRollAudioBuffer] и уходит в
         * стрим ПЕРЕД живыми байтами: порядок держит [pcmLock], поэтому первый чанк не может
         * обогнать накопленное.
         */
        if (!buffering) {
            val buffer = preRoll ?: PreRollAudioBuffer().also { preRoll = it }
            // Предбуфер принадлежит НОВОЙ реплике: мусор прошлой (в том числе хвост эха) не
            // должен стать её началом.
            buffer.clear()
            synchronized(pcmLock) { streamingToEngine = false }
            val fresh = HandsFreeCapture()
            val started = runCatching { fresh.start(captureListener, rawPcmListener) }.getOrDefault(false)
            if (!started) {
                // Микрофон не открылся: снимаем всё, что успели подготовить, иначе следующий
                // `startCapture` увидит «полуоткрытое» состояние и промолчит.
                closeCapture()
                runCatching { wrapper.cancelUtterance() }
                emit(state.copy(notice = MIC_UNAVAILABLE))
                return
            }
            capture = fresh
        }
        // Здесь же снимаются флаги прошлого цикла: новая реплика — новый `Final`, и ответ
        // прошлого хода уже не озвучивается.
        handledUtterance = false
        voiceTurn = false
        // Реплика открывается под тем же замком, что и переход на прямой путь: буферы, пришедшие
        // за время старта микрофона, встают в стрим первыми, а живые — сразу за ними.
        synchronized(pcmLock) {
            wrapper.startUtterance()
            val buffered = preRoll?.drain()
            if (buffered != null && buffered.isNotEmpty()) wrapper.acceptPcm(buffered, buffered.size)
            streamingToEngine = true
        }
        emit(state.copy(phase = LocalVoicePhase.LISTENING, partial = "", notice = null))
        // Захват открылся — но это ещё не значит, что из него идёт звук (см. [MicCaptureWatchdog]).
        armMicWatchdog(restarted)
    }

    /**
     * Что сейчас делает микрофон — одна точка правды вместо `capture?.isCapturing` в каждом месте.
     *
     * Различие [MicMode.PRE_ROLL] и [MicMode.STREAMING] — не косметика. Предбуфер копит звук для
     * реплики человека и ещё не отдаёт его движку; приняв его за рабочий захват, [armBargeInMic]
     * объявлял микрофоном перебивания микрофон, который записывал ответ Марса в НАЧАЛО будущей
     * фразы человека — и эта фраза уходила в чат вместе с озвучкой.
     */
    private fun micMode(): MicMode = when {
        capture?.isCapturing != true -> MicMode.CLOSED
        streamingToEngine -> MicMode.STREAMING
        else -> MicMode.PRE_ROLL
    }

    private fun closeCapture() {
        // Сторож живёт только рядом с живым захватом: без этого он «сдавался» бы уже после
        // закрытия микрофона и показывал человеку ложное «микрофон не отвечает».
        main.removeCallbacks(watchdogTick)
        // Захват закрыт — перебивания больше нет: следующий ответ снова закроет микрофон
        // штатным путём (иначе флаг навсегда отключал бы защиту от эха).
        bargeInMicOpen = false
        // Захвата нет — нет и его владельца: флаг относится ровно к этому захвату, а не к ответу.
        bargeInOwnedCapture = false
        synchronized(pcmLock) {
            // Предбуфер живёт только вместе с захватом: после закрытия его содержимое — уже
            // не «начало фразы», а мусор прошлой реплики.
            preRoll?.clear()
            streamingToEngine = false
        }
        val current = capture ?: return
        capture = null
        runCatching { current.cancel() }
        // Захват закрыт — источник громкости иссяк: орб не должен застыть на последнем слове.
        publishLevel(0f)
    }

    /**
     * Закрыть захват, поднятый ради перебивания, вместе с недописанной репликой.
     *
     * Отдельно от [closeCapture], потому что здесь реплика в движке НЕ должна дожить до `Final`:
     * человек ничего не сказал (иначе перебивание подтвердилось бы), а случайный `Final` из шума
     * ушёл бы в чат новым вопросом. `handledUtterance` закрывает и поздний ответ движка.
     */
    private fun closeBargeInCapture() {
        closeCapture()
        runCatching { stt?.cancelUtterance() }
        handledUtterance = true
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

    /**
     * Отдать уровень подписчику (орбу). Всегда на главном потоке.
     *
     * `NaN`/`±∞` сюда доезжают из ЧУЖИХ измерений (Visualizer, `AudioTrack`), а `.coerceIn` их не
     * зажимает: `NaN` не больше и не меньше границы. Один такой кадр у [VoiceModeOrbView] — и
     * планета гаснет до следующего обновления, поэтому значение санируется, а не «зажимается».
     */
    private fun publishLevel(value: Float) {
        val listener = onAudioLevel ?: return
        post { listener(SpeechLevelMath.sanitize(value)) }
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

    /**
     * Громкость для орба на время речи Марса: РЕАЛЬНАЯ, если её можно измерить.
     *
     * Порядок решений и почему он такой:
     * 1. источник пробуем первым — он и есть цель волны: планета обязана захлёбываться на громком
     *    слове и притухать на паузе, как орб ChatGPT, а не идти ровной синусоидой;
     * 2. `false` от источника (эффект занят другим приложением, сессии ещё нет, нет разрешения) —
     *    резерв: синтетическая огибающая. Она хуже, но живая планета лучше мёртвой;
     * 3. при живом источнике снимается ТОЛЬКО тик огибающей. Через [stopSpeechEnvelope] этого
     *    делать нельзя: он отпускает источник целиком, то есть погасил бы только что включённый
     *    `Visualizer` и вернул орб к синусоиде.
     *
     * Сессию берём на КАЖДЫЙ старт речи, а не один раз за сессию разговора: у серверной озвучки и
     * Piper разные треки, и номер сессии меняется от фразы к фразе.
     */
    private fun startSpeechLevel() {
        // Орб не подписан (обычный чат без полноэкранного слоя) — мерить не для кого: эффект
        // поднимать незачем, а `Visualizer` занимает сессию в аудио-сервисе.
        if (onAudioLevel == null) return
        if (speechLevels.begin()) {
            main.removeCallbacks(speechEnvelope)
            return
        }
        startSpeechEnvelope()
    }

    private fun startSpeechEnvelope() {
        main.removeCallbacks(speechEnvelope)
        if (onAudioLevel == null) return
        main.postDelayed(speechEnvelope, SPEECH_TICK_MS)
    }

    /**
     * Погасить уровень речи целиком: и огибающую, и источник.
     *
     * Одна точка, а не две. `Visualizer` живёт в аудио-сервисе системы и держит сессию: забытый
     * эффект продолжает отдавать процессу чужой звук и не даёт следующему приложению включить
     * свой. Поэтому «снять огибающую» и «отпустить источник» — это одно и то же событие: речь
     * кончилась (смена фазы, стоп человека, уход экрана, `release`).
     */
    private fun stopSpeechEnvelope() {
        main.removeCallbacks(speechEnvelope)
        speechLevels.stop()
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
            // Захват перебивания сторожем не управляется: тишина в нём — норма, а не мёртвый
            // `AudioRecord`. Взведённый ранее сторож (например, от предбуфера) не имеет права
            // трогать захват, который сейчас доказывает перебивание.
            if (bargeInMicOpen) return
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
        // Захват перебивания не перезапускается: он существует ради доказательств речи, а его
        // переоткрытие посреди ответа означало бы закрытый микрофон и потерянную попытку перебить.
        if (bargeInMicOpen) return
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
        // Сторож не имеет права гасить фазу озвучки. «Из захвата не пришло буферов» во время
        // ответа означает не мёртвый микрофон, а тишину в комнате; прежнее «сдался» переводило
        // SPEAKING в IDLE с сообщением «Микрофон не отвечает» прямо под звучащий ответ — орб
        // показывал «Нажмите, чтобы говорить», а кнопка «Стоп» исчезала.
        val speaking = bargeInMicOpen || state.phase == LocalVoicePhase.SPEAKING
        closeCapture()
        if (speaking) return
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
        // Новая реплика — новый ход: «молчи» от прошлого ответа здесь уже не действует, иначе
        // после одного «Стоп» человек не услышал бы ни одного следующего ответа.
        speechGate.onNewTurn()
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
        // Перебивание голосом: реплика человека уже распознана — звучащий ответ пора заглушить,
        // иначе Марс продолжит говорить поверх нового вопроса. Порядок важен: сначала новая фаза,
        // потом тишина, иначе орб мигнул бы IDLE перед THINKING. Жест-перебивание делает то же
        // через [stopSpeaking], но там взводится и «молчи».
        if (tts?.isSpeaking == true) silenceSpeech()
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
            // Перебивание: живой текст — доказательство речи человека, и [BargeInGate] умеет
            // отличать его от эха по тексту ответа. Отдаём ТОЛЬКО когда микрофон открыт
            // намеренно во время озвучки: в остальных фазах эти слова и так станут репликой.
            if (bargeInMicOpen && text.isNotBlank()) tts?.offerSpeech(0L, text)
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
     * ASR уходят в [LocalVoiceTts.offerSpeech], где решение принимает [BargeInGate].
     *
     * Микрофон во время ответа закрыт на динамике (эхо) и открыт на наушниках
     * ([BargeInMicPolicy], [armBargeInMic]) — то есть тик приходит только там, где перебивание
     * можно доказать. Жест-перебивание (тап по микрофону/орбу) работает независимо от этого и
     * не требует доказательств вовсе.
     */
    private val captureListener = object : HandsFreeCapture.Listener {
        override fun onSpeechStarted() = Unit

        override fun onSpeechProgress(voicedMs: Long) = post {
            // Маршрут мог смениться посреди ответа (вынули наушники) или перестать быть
            // доказанным: тогда захват больше не безопасен — закрываем его, и перебивание в этом
            // ответе снова становится жестом. Лучше потерять перебивание, чем услышать
            // собственную озвучку и отправить её в чат новым вопросом.
            if (bargeInMicOpen) {
                val active = routes?.activeOutput() ?: ActiveOutput.UNKNOWN
                if (!active.isConfirmedHeadphones) {
                    closeBargeInCapture()
                    return@post
                }
            }
            tts?.offerSpeech(voicedMs)
        }

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
        // Решение о маршруте принимает существующий [AudioRoutePolicy] внутри монитора, а здесь
        // берётся ДОКАЗАННЫЙ активный выход ([ActiveOutput]): тот же маршрут, на котором
        // [armBargeInMic] решает «открывать ли микрофон», обязан видеть и гейт.
        val monitor = routes ?: runCatching {
            AndroidAudioRouteMonitor(activity.applicationContext).also { it.start() }
        }.getOrNull()
        if (monitor != null) routes = monitor
        val created = LocalVoiceTts(
            context = activity.applicationContext,
            scope = scope,
            serverTts = serverTts,
            offline = offlineTts?.invoke(),
            // Гейт оценивает пороги по тому же маршруту, по которому решается судьба микрофона.
            route = { monitor?.activeOutput()?.route ?: AudioRoute.UNKNOWN },
            // Свой гейт, а не умолчание: контроллер читает его счётчик, когда озвучка замолчала
            // (см. [bargeInGate]) — иначе захват перебивания закрывался бы вместе с речью человека.
            gate = bargeInGate,
            aecActive = { capture?.echoCanceled == true },
            onSpeakingChanged = { speaking -> post { onTtsSpeakingChanged(speaking) } },
            onAvailableChanged = { available -> post { emit(state.copy(ttsAvailable = available)) } },
            // Подмена голоса обязана дойти до человека: раньше событие умирало здесь.
            onVoiceSubstituted = { substitution -> post { onVoiceSubstituted(substitution) } },
            // Порт офлайн-синтеза принадлежит процессу (OfflineVoice): экран его не освобождает.
            ownsOfflinePort = false,
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
            // Микрофон: на доказанных наушниках — открываем для перебивания, на динамике, на
            // недоказанном маршруте, на заглушке и на предпросмотре — закрываем. Решение целиком
            // в [BargeInMicPolicy]; здесь только последствия.
            if (!armBargeInMic()) {
                closeMicForAssistantSpeech()
                // Микрофон закрыт на время озвучки — громкость берём из тракта ВЫВОДА
                // ([SpeechLevelSource]), а синтетическая огибающая остаётся резервом.
                startSpeechLevel()
            } else {
                // Захват открыт: уровень придёт из настоящего PCM микрофона, синтетическая
                // огибающая только спорила бы с ним.
                stopSpeechEnvelope()
            }
            // Фаза — SPEAKING, даже если до этого было LISTENING: следующий тап обязан глушить
            // звук, а не искать несуществующую реплику.
            if (state.phase != LocalVoicePhase.SPEAKING) {
                emit(state.copy(phase = LocalVoicePhase.SPEAKING, ttsAvailable = true))
            }
        } else {
            // Речь кончилась — метка «что звучало» больше не действует.
            speechPurpose = SpeechPurpose.NONE
            stopSpeechEnvelope()
            // Точка отсчёта паузы перед автостартом микрофона: хвост `AudioTrack` и реверберация
            // комнаты уже позади, но им нужно ещё [CONTINUOUS_RESTART_DELAY_MS].
            lastSpeechEndedAtMs = watchdogClock()
            // Захват, поднятый ТОЛЬКО ради перебивания, закрывается здесь: человек так и не
            // заговорил, и микрофон не имеет права остаться слушать после ответа. В обычном чате
            // это нарушало бы «одна реплика на одно нажатие», а первая же случайная фраза уходила
            // бы в чат вопросом. Не закрывается он в двух случаях: человек уже перебил (его речь
            // ещё не кончилась) и человек нажал «Стоп» (это «замолчи и слушай меня»).
            if (BargeInMicPolicy.closeOwnedCaptureOnSpeechEnd(
                    captureOpen = bargeInMicOpen,
                    owned = bargeInOwnedCapture,
                    // Счётчик гейта, а не результат `offerSpeech`: к этому моменту он уже верен,
                    // а результат ещё не возвращён (см. [bargeInGate]).
                    accepted = bargeInGate.acceptedCount > 0,
                    userStopped = speechGate.mutedByUser,
                )
            ) {
                closeBargeInCapture()
            }
            // Конец озвучки: если захват уже открыт (перебивание), фаза обязана стать LISTENING,
            // иначе орб показывал бы «Нажмите, чтобы говорить» над работающим микрофоном.
            returnToRest()
        }
    }

    /**
     * Закрыть микрофон перед речью Марса (и на её старте).
     *
     * Зачем отдельный метод, а не два `if` в каждом месте: путей к озвучке четыре — событие
     * движка «звук пошёл», старт потока ответа, первая дельта и пункт «Озвучить» у сообщения, — и
     * в каждом из них забытый захват означал одно и то же: динамик попадал в микрофон, ASR
     * распознавал ответ, и он уходил в чат новым вопросом.
     *
     * Закрывается и `HandsFreeCapture`, и системный распознаватель: это два разных владельца
     * микрофона, и «полузакрытый» любой из них всё равно держит `AudioRecord`.
     *
     * @return `true` — микрофон был открыт и закрыт сейчас (фаза LISTENING снята).
     */
    private fun closeMicForAssistantSpeech(): Boolean {
        // Захват открыт НАМЕРЕННО для перебивания: на наушниках эха нет, а закрытие здесь
        // убивало бы ровно ту реплику, которой человек перебивает (её же закрывает
        // `onAnswerDelta` на каждой дельте). См. [BargeInMicPolicy].
        if (bargeInMicOpen) return false
        val wasListening = platformListening || capture?.isCapturing == true
        if (platformListening) {
            platformListening = false
            runCatching { platformAsr?.cancel() }
        }
        if (capture != null) closeCapture()
        if (!wasListening) return false
        // Недописанная реплика выбрасывается: её звук — это голос Марса из динамика, а не вопрос.
        // `handledUtterance` закрывает путь позднему `Final`, который иначе отправил бы эхо в чат.
        runCatching { stt?.cancelUtterance() }
        handledUtterance = true
        if (state.phase == LocalVoicePhase.LISTENING) {
            emit(state.copy(phase = LocalVoicePhase.IDLE, partial = "", notice = null))
        }
        return true
    }

    /**
     * Открыть микрофон НА время озвучки — чтобы человек мог перебить голосом.
     *
     * ## Зачем отдельный метод, а не [startCapture]
     * [startCapture] — это новый ХОД: он снимает `voiceTurn`, сбрасывает `handledUtterance`,
     * глушит синтез (`tts.stopByUser()`) и ставит фазу LISTENING. Здесь нужно обратное: ответ
     * уже звучит и принадлежит текущему ходу, а микрофон открывается только как источник
     * доказательств перебивания. Поэтому флаги хода не трогаются, фаза остаётся SPEAKING, а
     * синтез продолжает говорить — до [BargeInGate.Trigger] или до кнопки «Стоп».
     *
     * ## Что даёт открытый захват
     * [HandsFreeCapture.Listener.onSpeechProgress] начинает тикать, а partial'ы ASR уходят в
     * [LocalVoiceTts.offerSpeech]. Политика [BargeInGate] на наушниках принимает по
     * длительности речи, поэтому перебивание срабатывает через ~200 мс, а не в конце фразы.
     *
     * ## Чего этот метод не обещает
     * Это НЕ дуплекс. На динамике и на любом НЕДОКАЗАННОМ маршруте он не открывает ничего
     * ([BargeInMicPolicy]): если платформа не назвала активный выход ([ActiveOutput]), автоперебивание
     * выключено, а не «включено наполовину». На доказанных наушниках гарантия «Марс не услышит сам
     * себя» держится на том, что звук уходит в наушники. Живой приёмки на устройстве у этого пути
     * ещё не было.
     *
     * Микрофон открывается только под РЕЧЬ ОТВЕТА ([SpeechPurpose.ANSWER]): заглушка и предпросмотр
     * сообщения захват не поднимают.
     *
     * @return `true` — захват открыт (или уже был открыт) для перебивания.
     */
    private fun armBargeInMic(): Boolean {
        if (released) return false
        // Маршрут — ДОКАЗАННЫЙ активный выход, а не список доступных устройств: воткнутая
        // гарнитура ещё не значит, что ответ звучит в неё.
        val active = routes?.activeOutput() ?: ActiveOutput.UNKNOWN
        val mode = micMode()
        val allowed = BargeInMicPolicy.keepMicDuringSpeech(
            enabled = bargeInDuringSpeech,
            route = active.route,
            routeConfirmed = active.confirmed,
            purpose = speechPurpose,
            paused = paused,
            released = released,
            backgroundServiceListening = MarsVoiceService.conversationActive,
            micMode = mode,
        )
        if (!allowed) {
            // Захват, поднятый ради перебивания, обязан закрыться здесь же: иначе флаг «микрофон
            // открыт намеренно» запретил бы [closeMicForAssistantSpeech] убрать его, и ответ Марса
            // попал бы в захват. Флаги снимаются ДО возврата — решение о закрытии принимает
            // вызывающий, для которого этот ответ теперь обычная озвучка.
            bargeInMicOpen = false
            bargeInOwnedCapture = false
            return false
        }
        // Захват уже отдаёт звук в движок (человек заговорил до старта озвучки или перебивание
        // вооружено раньше) — второй `AudioRecord` отобрал бы у него звук. Владельца не меняем:
        // повторный вход (пауза между фразами) не должен «забыть», что захват наш.
        if (mode == MicMode.STREAMING) {
            bargeInMicOpen = true
            return true
        }
        if (platformListening) return false
        val wrapper = stt ?: return false
        // Движок не готов — открывать микрофон некуда: PCM всё равно некуда отдавать, а
        // «Слушаю…» без распознавания было бы ложью. Перебивание остаётся жестом.
        if (!wrapper.isReady) return false
        val fresh = HandsFreeCapture()
        val buffer = preRoll ?: PreRollAudioBuffer().also { preRoll = it }
        buffer.clear()
        synchronized(pcmLock) { streamingToEngine = false }
        if (!runCatching { fresh.start(captureListener, rawPcmListener) }.getOrDefault(false)) {
            preRoll = null
            return false
        }
        capture = fresh
        // Помечаем ДО создания реплики: partial, пришедший из предбуфера, уже должен считаться
        // доказательством перебивания, а не эхом.
        bargeInMicOpen = true
        bargeInOwnedCapture = true
        // Порядок тот же, что в [startCapture]: реплика открывается под замком, накопленное
        // уходит в стрим первым, и только потом поток чтения переключается на прямой путь.
        synchronized(pcmLock) {
            wrapper.startUtterance()
            val buffered = buffer.drain()
            if (buffered.isNotEmpty()) wrapper.acceptPcm(buffered, buffered.size)
            streamingToEngine = true
        }
        handledUtterance = false
        // Сторож микрофона здесь НЕ взводится, и это не упущение. Тишина в захвате перебивания —
        // норма: человек слушает ответ. Прежний сторож через три секунды объявлял микрофон
        // мёртвым, перезапускал захват (закрывая его посреди ответа) и в итоге переводил SPEAKING
        // в IDLE с сообщением «Микрофон не отвечает» — прямо под звучащий ответ, убирая кнопку
        // «Стоп». Отсутствие перебивания дешевле, чем разрушенная фаза озвучки.
        return true
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
        // Заглушка — не ответ: микрофон под неё не открывается, иначе ASR получал бы в реплику
        // саму подсказку, а «Секунду, думаю.» стала бы вопросом человека.
        speechPurpose = SpeechPurpose.HINT
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
        // Уход из SPEAKING — единственный надёжный признак «речь кончилась»: именно по нему
        // отпускается источник громкости. Проверка здесь, а не только в `onTtsSpeakingChanged`,
        // потому что фазу снимают и другие пути (стоп человека, уход экрана, провал движка), и
        // забытый `Visualizer` на сессии отыгравшего плеера — это утечка эффекта и чужой звук.
        if (state.phase == LocalVoicePhase.SPEAKING && next.phase != LocalVoicePhase.SPEAKING) {
            stopSpeechEnvelope()
        }
        state = next
        runCatching { onState(next) }
        // Второй наблюдатель — полноэкранный слой. Отдельный вызов, а не часть `onState`:
        // хост рисует по снимку свою строку статуса и не обязан знать про слой, а слой обязан
        // узнать о смене фазы, даже если лента не менялась (нажатие «Стоп»).
        runCatching { onStateObserver?.invoke(next) }
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
         * 650 мс — не «чтобы было плавно»: динамик после последнего сэмпла ещё отдаёт хвост
         * аудиобуфера `AudioTrack` и реверберацию комнаты (на маленькой кухне/в ванной — сотни
         * миллисекунд), и микрофон, открытый в тот же кадр, записал бы собственный ответ Марса как
         * вопрос человека. 400 мс хвост не покрывали: эхо попадало в ASR и уходило новым ходом.
         */
        const val CONTINUOUS_RESTART_DELAY_MS: Long = 650L

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

/**
 * Чистое решение «можно ли снова открыть микрофон после ответа Марса».
 *
 * Зачем отдельный объект. Автостарт микрофона в непрерывном разговоре — единственное место, где
 * захват открывается БЕЗ человека, и ошибка здесь стоит дороже всего: микрофон, открытый пока
 * Марс говорит, записывает его же ответ, ASR превращает его в текст, и Марс отвечает сам себе.
 * Поэтому решение собрано в одну функцию без `Handler`, без Android и без полей состояния — его
 * можно проверить юнит-тестом в каждом из шести условий, а не только повторной приёмкой на
 * телефоне.
 *
 * Все условия обязательны одновременно, и каждое закрывает свой наблюдавшийся сценарий:
 * - [released]/[paused] — экран закрыт или ушёл в фон (микрофон в кармане — не разговор);
 * - [continuous] — обычный чат живёт по правилу «одна реплика на одно нажатие»;
 * - [answerPending] — заглушка «Секунду, думаю.» договорила, а ответ ещё впереди;
 * - [streamedTurn] — ответ ещё докатывается дельтами в синтез;
 * - [ttsSpeaking] — движок реально произносит реплику (включая хвост `AudioTrack`);
 * - [phase] — не IDLE значит, что канал уже чем-то занят (слушает, думает, говорит).
 */
internal object ContinuousListenPolicy {

    /** @return `true` — автостарт разрешён; `false` — микрофон открывать нельзя ни в коем случае. */
    fun canRestart(
        released: Boolean,
        paused: Boolean,
        continuous: Boolean,
        answerPending: Boolean,
        streamedTurn: Boolean,
        ttsSpeaking: Boolean,
        phase: LocalVoicePhase,
    ): Boolean {
        if (released || paused || !continuous) return false
        if (answerPending || streamedTurn) return false
        if (ttsSpeaking) return false
        return phase == LocalVoicePhase.IDLE
    }

    /**
     * Сколько ещё ждать, прежде чем открывать микрофон.
     *
     * Пауза отсчитывается от РЕАЛЬНОГО конца звука ([lastSpeechEndedAtMs]), а не от момента
     * планирования таймера: между этими моментами может пройти целый ответ, и таймер, поставленный
     * заранее, открыл бы микрофон посреди речи.
     *
     * @param lastSpeechEndedAtMs монотонная метка конца речи; `<= 0` — речи ещё не было.
     * @return остаток паузы в миллисекундах; `0` — можно открывать сейчас.
     */
    fun remainingQuietMs(lastSpeechEndedAtMs: Long, nowMs: Long, delayMs: Long): Long {
        if (lastSpeechEndedAtMs <= 0L || delayMs <= 0L) return 0L
        val elapsed = nowMs - lastSpeechEndedAtMs
        // Часы ушли назад (не должно быть на монотонных часах) — ждём полную паузу, а не ноль.
        return (delayMs - elapsed).coerceIn(0L, delayMs)
    }
}

/**
 * Разрешение озвучивать текущий ответ — и «залипший стоп», и «залипшая тишина» одинаково
 * ломают диалог.
 *
 * ## Зачем отдельный объект
 * Раньше стоп был одноразовым: `stopSpeaking()` глушил звук и переводил фазу в IDLE, но нигде
 * не запоминалось, что человек ПОПРОСИЛ тишины. Потоковая озвучка продолжает кормить контроллер
 * дельтами (`onAnswerDelta` → `appendStream` → `pump` → `speakPieceNow`), поэтому через полсекунды
 * после нажатия «Стоп» Марс начинал говорить снова — со следующего куска. Снаружи это выглядело
 * как «кнопка стоп не работает»: звук на мгновение пропадал и возвращался.
 *
 * Поэтому намерение человека живёт отдельным флагом, и он переживает дельты, но НЕ переживает
 * ход: новый ход ([onNewTurn]) снова разрешает озвучку. Правило «флаг живёт один ответ» —
 * единственное, что не даёт стоп-кнопке замолчать диалог навсегда.
 *
 * Объект чистый (JVM, без Android и без состояния движка), поэтому проверяется юнит-тестом:
 * ровно здесь ошибка стоит дороже всего, а на телефоне воспроизводится секундами.
 */
internal class AnswerSpeechGate {

    /**
     * Человек заглушил ТЕКУЩИЙ ответ и не хочет слышать его остаток.
     *
     * Читается снаружи только для ветки «ответ кончился, а звука не было»: там нужно понять,
     * почему хвост не читается, и вернуть микрофон в строй.
     */
    var mutedByUser: Boolean = false
        private set

    /** Человек нажал «Стоп» (орб, микрофон, кнопка композера). */
    fun onUserStop() {
        mutedByUser = true
    }

    /** Начался новый ход или пришла новая реплика: прошлое намерение больше не действует. */
    fun onNewTurn() {
        mutedByUser = false
    }

    /**
     * Можно ли отдавать текст в синтез.
     *
     * @param speakAnswers озвучка включена вообще (контракт контроллера);
     * @param voiceTurn ответ идёт на ГОЛОСОВУЮ реплику (ответ на печатный ввод не читается);
     */
    fun canSpeak(speakAnswers: Boolean, voiceTurn: Boolean): Boolean =
        speakAnswers && voiceTurn && !mutedByUser
}

/**
 * Что именно звучит из динамика/наушников.
 *
 * Нужен ровно для одного решения: открывать ли микрофон ради акустического перебивания. Перебивать
 * можно только ОТВЕТ на вопрос — заглушка «Секунду, думаю.» и предпросмотр сообщения («Озвучить»)
 * ответом не являются, и микрофон под них поднимать нельзя: записанное уходило бы в чат новым
 * вопросом, а сама подсказка становилась бы «репликой человека».
 */
internal enum class SpeechPurpose {
    /** Ничего не звучит (или речь уже кончилась). */
    NONE,

    /** Ответ на голосовую реплику — единственная речь, которую имеет смысл перебивать. */
    ANSWER,

    /** Заглушка на размышлениях модели. */
    HINT,

    /** Предпросмотр сообщения по пункту «Озвучить». */
    PREVIEW,
}

/**
 * Что сейчас делает микрофон.
 *
 * Отдельный тип вместо `capture?.isCapturing`: предбуфер и рабочий захват — РАЗНЫЕ вещи, и
 * путаница между ними стоила начала фразы (ответ Марса, записанный в предбуфер, уходил в движок
 * как первые слова человека).
 */
internal enum class MicMode {
    /** Микрофона нет. */
    CLOSED,

    /** Микрофон открыт только ради предбуфера: движок грузится, реплика ещё не начата. */
    PRE_ROLL,

    /** Микрофон отдаёт звук в движок — реплика человека или уже открытое перебивание. */
    STREAMING,
}

/**
 * Можно ли держать микрофон открытым, пока Марс говорит.
 *
 * Это единственное условие, при котором в локальном чате вообще возможно акустическое
 * перебивание: пока захват закрыт, [HandsFreeCapture.Listener.onSpeechProgress] не тикает,
 * `LocalVoiceTts.offerSpeech` не вызывается, и [BargeInGate] нечего оценивать — человек может
 * перебить только жестом.
 *
 * ## Почему наушники, и только доказанные
 * На встроенном динамике микрофон слышит собственную озвучку: длительность речи там ничего не
 * доказывает (TTS говорит ровно так же долго), а `AcousticEchoCanceler` ослабляет эхо, но не
 * убирает его — на части устройств он включается «формально». Поэтому на динамике микрофон во
 * время озвучки закрыт, как и раньше: лучше «не перебил», чем «Марс перебил сам себя и отправил
 * свой ответ новым вопросом».
 *
 * Дальше — главное. Мало того, что маршрут «наушники»: он обязан быть ДОКАЗАН ([routeConfirmed]).
 * Список доступных выходов содержит воткнутую гарнитуру, даже когда ответ читает динамик, поэтому
 * прежняя проверка «в системе есть наушники» открывала микрофон на динамике. Если платформа не
 * назвала активный выход ([ActiveOutput]), автоперебивание выключено — это штатное состояние, а
 * не поломка, и дуплекс на динамике не обещается ни в каком случае.
 *
 * ## Остальные условия — тоже не формальность
 * - [purpose] — заглушка и предпросмотр микрофон не открывают (перебивать там нечего);
 * - [paused]/[released] — экран в фоне или уже уничтожен: микрофон в кармане не разговор;
 * - [backgroundServiceListening] — микрофон держит фоновый [MarsVoiceService], второй `AudioRecord`
 *   отобрал бы у него звук;
 * - [micMode] — предбуфер ([MicMode.PRE_ROLL]) микрофоном перебивания не считается.
 *
 * @param enabled разрешено ли перебивание настройкой/хостом;
 * @param route маршрут вывода на момент старта озвучки ([AudioRoute.UNKNOWN] — эхо-опасен);
 * @param routeConfirmed доказан ли этот маршрут как АКТИВНЫЙ выход;
 * @param purpose что звучит сейчас;
 * @param paused экран в фоне;
 * @param released контроллер освобождён;
 * @param backgroundServiceListening микрофон занят фоновым разговором;
 * @param micMode что делает микрофон прямо сейчас.
 */
internal object BargeInMicPolicy {

    /** @return `true` — захват можно открыть (или оставить открытым) во время речи Марса. */
    fun keepMicDuringSpeech(
        enabled: Boolean,
        route: AudioRoute,
        routeConfirmed: Boolean = true,
        purpose: SpeechPurpose = SpeechPurpose.ANSWER,
        paused: Boolean = false,
        released: Boolean = false,
        backgroundServiceListening: Boolean = false,
        micMode: MicMode = MicMode.CLOSED,
    ): Boolean {
        if (released || paused) return false
        if (!enabled) return false
        // Перебивают ответ, а не заглушку и не предпросмотр сообщения.
        if (purpose != SpeechPurpose.ANSWER) return false
        // Маршрут должен быть доказанно активным И вести в наушники. «В системе есть наушники» —
        // не доказательство: ответ мог звучать из динамика.
        if (!routeConfirmed) return false
        if (route != AudioRoute.HEADPHONES) return false
        if (backgroundServiceListening) return false
        // Предбуфер копит звук для реплики человека: ответ Марса, записанный туда, стал бы её
        // началом и ушёл бы в чат.
        if (micMode == MicMode.PRE_ROLL) return false
        return true
    }

    /**
     * Пора ли закрыть захват, который поднимали ТОЛЬКО ради перебивания.
     *
     * Речь Марса кончилась, а человек так и не заговорил — микрофон обязан закрыться: в обычном
     * чате он нарушал бы «одна реплика на одно нажатие», а первая же случайная фраза уходила бы в
     * чат вопросом.
     *
     * @param captureOpen захват помечен как микрофон перебивания;
     * @param owned захват открыт этим контроллером ради перебивания (а не человеком ради реплики);
     * @param accepted перебивание подтвердилось — речь человека ещё не кончилась, закрывать нельзя;
     * @param userStopped человек нажал «Стоп»: это «замолчи и слушай меня», микрофон остаётся.
     */
    fun closeOwnedCaptureOnSpeechEnd(
        captureOpen: Boolean,
        owned: Boolean,
        accepted: Boolean,
        userStopped: Boolean,
    ): Boolean = captureOpen && owned && !accepted && !userStopped
}

/**
 * Чей уровень громкости рисует орб, пока Марс говорит: реальный звук вывода или резервная
 * синтетическая огибающая.
 *
 * ## Зачем отдельный объект
 * Решение «мерить или синтезировать» — единственное место волны, где ошибка видна человеку сразу:
 * неверный выбор даёт либо мёртвую планету (источник не ответил, а огибающую не включили), либо
 * драку двух источников (огибающая продолжает тикать поверх реального уровня). Ни то, ни другое
 * нельзя проверить юнит-тестом контроллера: он требует `Handler` главного `Looper` и `Activity`.
 * Поэтому политика собрана здесь, без Android, и проверяется фейком [SpeechLevelSource]
 * ([SpeechLevelTapTest]).
 *
 * ## Почему одной попытки мало ([retry])
 * Фаза SPEAKING включается на ПРИНЯТОМ синтезе (`LocalVoiceTts`), то есть РАНЬШЕ первого сэмпла:
 * `ExoPlayer` в этот момент ещё пишет файл, `AudioTrack` у Piper ещё не создан, и сессии у
 * платформы нет вовсе. Одиночная попытка в этот момент вернула бы `false` ВСЕГДА, и реальная
 * громкость не появилась бы никогда — ровно тот дефект, который волна и закрывает. Поэтому пока
 * тикает резервная огибающая, источник пробуется ещё несколько раз с шагом [RETRY_INTERVAL_MS].
 *
 * ## Правила, которые объект держит
 * - [begin] спрашивает источник только при живой сессии;
 * - `true` от источника — [envelopeLevel] возвращает `null`, то есть синтетика не считается вовсе
 *   (а не «считается и не публикуется»: лишняя тригонометрия в кадре 10 раз в секунду никому не
 *   нужна);
 * - `false` — огибающая включается, и [fromSource] остаётся `false`: фаза SPEAKING от этого не
 *   залипает;
 * - повторы ОГРАНИЧЕНЫ ([MAX_RETRIES]): эффект, который не даётся, нельзя дёргать каждые 90 мс —
 *   создание и освобождение `Visualizer` дороже, чем польза от попытки;
 * - любой уровень снаружи проходит через [SpeechLevelMath.sanitize]: `NaN`/`±∞`/выход за границы
 *   не ломают кадр;
 * - [stop] отпускает источник, только если он был запущен, и запрещает повторы до следующей речи.
 *
 * @param source сам измеритель (`null` — мерить нечем, всегда огибающая);
 * @param sessionId сессия играющего аудио на текущий момент;
 * @param onLevel куда отдавать уровень из источника (контроллер публикует его орбу);
 * @param nowMs монотонные часы: подменяются в тесте, чтобы проверить шаг повторов.
 */
internal class SpeechLevelRouter(
    private val source: SpeechLevelSource?,
    private val sessionId: () -> Int?,
    private val onLevel: (Float) -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    /** `true` — уровень идёт из источника; `false` — из синтетической огибающей. */
    var fromSource: Boolean = false
        private set

    /** Сколько попыток ещё разрешено в текущей реплике (0 — источник больше не трогаем). */
    private var attemptsLeft: Int = 0

    private var nextAttemptAt: Long = 0L

    /**
     * Первая попытка — на старте речи.
     *
     * @return `true` — источник ожил, огибающая не нужна; `false` — остаёмся на резерве.
     */
    fun begin(): Boolean {
        attemptsLeft = MAX_RETRIES
        nextAttemptAt = nowMs() + RETRY_INTERVAL_MS
        if (fromSource) return true
        return attempt()
    }

    /**
     * Повтор посреди реплики: сессия у платформы появляется с задержкой (см. KDoc класса).
     *
     * @return `true` — источник только что ожил (синтетику пора прекращать); `false` — ещё нет.
     */
    fun retry(): Boolean {
        if (fromSource) return true
        if (attemptsLeft <= 0) return false
        val now = nowMs()
        if (now < nextAttemptAt) return false
        attemptsLeft--
        nextAttemptAt = now + RETRY_INTERVAL_MS
        return attempt()
    }

    /**
     * Кадр огибающей: `null` — источник жив, считать синтетику не нужно.
     *
     * @param compute само значение огибающей; зовётся только на резервном пути.
     */
    fun envelopeLevel(compute: () -> Float): Float? {
        if (fromSource) return null
        return SpeechLevelMath.sanitize(compute())
    }

    /** Речь кончилась (или контроллер освобождён): источник отпускается, повторы запрещаются. */
    fun stop() {
        attemptsLeft = 0
        nextAttemptAt = 0L
        if (!fromSource) return
        fromSource = false
        runCatching { source?.stop() }
    }

    /** Одна попытка подписки. Провайдер чужой и может бросить — разговор важнее диагностики. */
    private fun attempt(): Boolean {
        val session = runCatching { sessionId() }.getOrNull() ?: return false
        // `source` необязателен: без него подписки нет вовсе, и это не ошибка, а «источника
        // не дали». `?: false` обязателен — `source?.start(...)` даёт `Boolean?`, и без него
        // компилятор справедливо отказывается присваивать результат полю `Boolean`.
        val started = runCatching {
            source?.start(session) { level -> onLevel(SpeechLevelMath.sanitize(level)) } ?: false
        }.getOrDefault(false)
        fromSource = started
        return started
    }

    private companion object {
        /**
         * Шаг повторов. Кадр огибающей идёт каждые 90 мс, а сессия у ExoPlayer появляется через
         * 150–600 мс после старта речи: 250 мс — компромисс между «поймали быстро» и «не дёргаем
         * эффект на каждом кадре».
         */
        const val RETRY_INTERVAL_MS = 250L

        /**
         * Потолок повторов на реплику: 12 × 250 мс ≈ 3 с. Если за три секунды речи сессии так и не
         * появилось, дело не в опоздании, а в отказе — дальше только огибающая.
         */
        const val MAX_RETRIES = 12
    }
}
