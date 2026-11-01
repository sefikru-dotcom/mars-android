package ru.mars.mobile

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import ru.mars.mobile.tts.OfflineTtsPort

/**
 * Озвучка ответа локального чата: серверный синтез, если он дан, и системный TTS как
 * обязательный резерв. Перебивание решает существующий [BargeInGate], маршрут вывода —
 * существующие [AudioRoutePolicy]/[AndroidAudioRouteMonitor], захват — [AudioCapturePolicy].
 *
 * ## Что здесь переиспользовано, а что нового
 * Нового — только проводка. Синтез и воспроизведение уже есть:
 * - [ServerTtsSpeechEngine] — серверный путь с прозрачным фолбэком на системный TTS;
 * - [AndroidServerTtsPlayback] — проигрывание серверных байтов (Media3, `USAGE_ASSISTANT`);
 * - [AndroidSpeechEngine] — платформенный `TextToSpeech` (и он же фолбэк);
 * - [VoiceOutputController] — политика «что и когда говорить» + [ru.mars.mobile.domain.util.SpeechContentFilter]
 *   (markdown, код и ссылки не читаются вслух);
 * - [BargeInGate] — единственная политика «человек действительно перебил, а не мы услышали
 *   собственный динамик».
 *
 * ## Почему серверный клиент необязателен
 * Локальный чат ходит к провайдеру напрямую и не имеет device-credential сервера Марса,
 * поэтому [serverTts] по умолчанию `null`: тогда работает только системный TTS. Если
 * координатор даст [ServerTtsClient] (например, `RepositoryServerTtsClient` с базовым URL и
 * провайдером credential), серверный канал включается сам — правок в этом классе не нужно.
 *
 * ## Перебивание
 * Два разных пути, и они не смешиваются:
 * - **явное действие человека** — нажатие микрофона во время озвучки: доказательств не
 *   требуется, [stopByUser] глушит звук и сбрасывает политику;
 * - **акустика** — пока микрофон открыт и ответ звучит, поток речи идёт в [offerSpeech]:
 *   длительность речи и partial ASR проверяет [BargeInGate], и только его `Trigger`
 *   останавливает озвучку. На динамике без AEC одного текста мало, на наушниках хватает
 *   длительности — это уже внутри гейта, здесь дублировать нельзя.
 *
 * @param context любой контекст (хранится `applicationContext`);
 * @param scope область для фонового серверного синтеза; `null` — своя, освобождается в [release];
 * @param serverTts источник серверного аудио; `null` — только системный TTS;
 * @param route текущий маршрут вывода (наушники/динамик) — нужен гейту, чтобы выбрать порог;
 * @param aecActive реально ли включён аппаратный AEC на текущей сессии захвата;
 * @param nowMs часы (мс) — подменяются в тестах;
 * @param gate политика перебивания (можно передать со своей конфигурацией);
 * @param onSpeakingChanged «звук пошёл / звук кончился» для хоста (фаза SPEAKING);
 * @param onAvailableChanged озвучка стала недоступна (или снова доступна) — хост гасит кнопку;
 * @param onBargeIn подтверждённое перебивание (диагностика/логика хоста), необязательно.
 */
class LocalVoiceTts(
    context: Context,
    scope: CoroutineScope? = null,
    private val serverTts: ServerTtsClient? = null,
    /** Офлайн-движок (Piper): если дан и готов, говорит он — а не робот Google TTS. */
    private val offline: OfflineTtsPort? = null,
    private val route: () -> AudioRoute = { AudioRoute.UNKNOWN },
    private val aecActive: () -> Boolean = { false },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val gate: BargeInGate = BargeInGate(),
    private val onSpeakingChanged: (Boolean) -> Unit = {},
    private val onAvailableChanged: (Boolean) -> Unit = {},
    private val onBargeIn: ((BargeInDecision.Trigger) -> Unit)? = null,
) {

    private val appContext: Context = context.applicationContext

    /** Своя область только если её не дали: чужую (lifecycle) освобождать нельзя. */
    private val ownedScope: CoroutineScope? =
        if (scope == null) CoroutineScope(SupervisorJob() + Dispatchers.Main) else null

    private val workScope: CoroutineScope = scope ?: ownedScope!!

    @Volatile
    private var released: Boolean = false

    @Volatile
    private var speaking: Boolean = false

    /** Последнее объявленное хосту «есть чем говорить»: колбэк не дёргаем без изменения. */
    private var lastAvailable: Boolean = true

    /**
     * Системный движок. Объявлен ДО [output], потому что [AndroidSpeechEngine] может позвать
     * `onInitFinished` синхронно из конструктора (нет TTS-сервиса на устройстве) — колбэк
     * обязан видеть уже инициализированные поля и не трогать ещё не созданный [output].
     */
    private val system: AndroidSpeechEngine = AndroidSpeechEngine(
        context = appContext,
        onInitFinished = { available ->
            // Без серверного канала отказ системного TTS = озвучки нет вовсе.
            if (serverTts == null) publishAvailable(available)
        },
        onSpeechFinished = { finishSpeaking() },
    )

    /** Проигрывание серверных байтов: только когда серверный клиент реально дан. */
    private val playback: AndroidServerTtsPlayback? =
        if (serverTts == null) {
            null
        } else {
            AndroidServerTtsPlayback(appContext, onFinished = { finishSpeaking() })
        }

    /** Один движок для контроллера: офлайн (Piper) → серверный → системный. */
    private val engine: VoiceOutputController.SpeechEngine = run {
        val client = serverTts
        val player = playback
        val fallback = if (client != null && player != null) {
            ServerTtsSpeechEngine(
                fallback = system,
                client = client,
                playback = player,
                scope = workScope,
                // Серверное аудио зазвучало: канал рабочий, кнопку можно вернуть в строй.
                onServerAudioAvailable = { publishAvailable(true) },
            )
        } else {
            system
        }
        // Офлайн-Piper первым: голос владельца жаловался именно на робота.
        val piper = offline?.let { port ->
            OfflineTtsSpeechEngine(
                context = appContext,
                port = port,
                onSpeakingChanged = { speaking: Boolean ->
                    if (speaking) setSpeaking(true) else finishSpeaking()
                },
            )
        }
        if (piper != null) {
            PreferOfflineEngine(offline = piper, fallback = fallback)
        } else {
            fallback
        }
    }

    /**
     * Политика «что говорить»: фильтрация markdown, запрет озвучки в фоне, отказ движка.
     * Автопоказ включён — локальный чат зовёт [speak] только по явному событию (ответ на
     * голосовую реплику или пункт «Озвучить»), промежуточных дельт сюда не приходит.
     */
    private val output: VoiceOutputController =
        VoiceOutputController(engine = engine, autoSpeakInitially = true)

    /** Звук идёт прямо сейчас. */
    val isSpeaking: Boolean get() = speaking

    /** Есть ли чем озвучивать: системный движок жив или серверный канал ещё не отказал. */
    val available: Boolean get() = lastAvailable && output.engineAvailable

    /**
     * Озвучить [text] (целиком, одной репликой).
     *
     * @return `true` — реплика принята (серверная попытка запущена либо её принял системный
     *   движок); `false` — звука не будет: пустой текст, нечего читать после фильтрации или
     *   движки уже мертвы.
     */
    fun speak(text: String?): Boolean {
        if (released) return false
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) return false
        // Эталон эха — весь ответ сразу: локальный чат говорит одной репликой, а не фразами,
        // поэтому окно произносимого текста в гейте равно ответу.
        gate.onAnswerStarted(nowMs(), route(), aecActive(), body)
        return when (output.speakNow(body)) {
            VoiceOutputController.SpeakOutcome.SPOKEN -> {
                setSpeaking(true)
                true
            }

            VoiceOutputController.SpeakOutcome.NOTHING_TO_SPEAK -> {
                gate.onAnswerFinished()
                false
            }

            VoiceOutputController.SpeakOutcome.ENGINE_UNAVAILABLE -> {
                gate.onAnswerFinished()
                publishAvailable(false)
                false
            }
        }
    }

    /** Немедленная тишина (новый ход, уход с экрана, конец диалога). */
    fun stop() {
        runCatching { output.stopSpeaking() }
        finishSpeaking()
    }

    /**
     * Перебивание явным действием человека (нажал микрофон). Доказательств речи не нужно —
     * жест и есть доказательство, поэтому политика сбрасывается, а не опрашивается.
     */
    fun stopByUser() {
        gate.reset()
        stop()
    }

    /**
     * Акустический кандидат в перебивание: тик длительности речи и/или partial ASR.
     *
     * Зовётся с потока, где эти данные появились (у локального чата — с главного, после
     * перекладки колбэков захвата). Ничего не делает, если ответ не звучит.
     *
     * @param voicedMs сколько речи накоплено в текущей фразе (0 — только текст);
     * @param partial живой транскрипт; `null` — текстового доказательства нет.
     * @return решение политики; `Trigger` означает, что озвучка уже остановлена.
     */
    fun offerSpeech(voicedMs: Long, partial: String? = null): BargeInDecision {
        if (released || !speaking || !gate.isActive) return BargeInDecision.Ignored
        val now = nowMs()
        var decision: BargeInDecision = BargeInDecision.Pending(BargeInReason.NO_SPEECH)
        if (!partial.isNullOrBlank()) {
            decision = gate.onPartial(partial, now)
            if (decision is BargeInDecision.Trigger) return interrupt(decision)
        }
        if (voicedMs > 0L) {
            val byDuration = gate.onVoiced(voicedMs, now)
            if (byDuration is BargeInDecision.Trigger) return interrupt(byDuration)
            decision = byDuration
        }
        return decision
    }

    /** Освободить оба движка и временные файлы. Повторный вызов безопасен. */
    fun release() {
        if (released) return
        released = true
        speaking = false
        runCatching { output.onDestroy() }
        gate.reset()
        ownedScope?.cancel()
    }

    // --- внутреннее ---------------------------------------------------------------------------

    private fun interrupt(decision: BargeInDecision.Trigger): BargeInDecision.Trigger {
        // Счётчик перебиваний ведёт гейт: сначала фиксируем принятое, потом глушим звук
        // (стоп закрывает попытку и не должен потерять счётчик).
        gate.onAccepted()
        stop()
        runCatching { onBargeIn?.invoke(decision) }
        return decision
    }

    private fun setSpeaking(value: Boolean) {
        if (speaking == value) return
        speaking = value
        runCatching { onSpeakingChanged(value) }
    }

    /** Звук закончился (дочитал, упал или его остановили): политика гейта закрывается. */
    private fun finishSpeaking() {
        if (!speaking) return
        speaking = false
        gate.onAnswerFinished()
        runCatching { onSpeakingChanged(false) }
    }

    private fun publishAvailable(value: Boolean) {
        if (released) return
        if (lastAvailable == value) return
        lastAvailable = value
        runCatching { onAvailableChanged(value) }
    }
}
