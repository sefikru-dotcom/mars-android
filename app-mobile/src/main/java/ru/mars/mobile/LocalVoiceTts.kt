package ru.mars.mobile

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.mars.mobile.tts.OfflineTtsPort
import ru.mars.mobile.voice.SpeechChunker

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
 * ## Ранний старт речи ([beginStream]/[appendStream]/[endStream])
 * [speak] читает ответ ОДНОЙ репликой — так локальный чат и живёт: хост отдаёт готовый текст.
 * Чтобы первое слово звучало на секунды раньше, есть потоковый вход: хост кормит дельты, а
 * [ru.mars.mobile.voice.SpeechChunker] режет первый кусок по клаузу (запятая/точка/двоеточие/
 * тире/перевод строки), следующие — пачками ~300 символов по границе слова. Куски уходят в
 * синтез по очереди: пока движок говорит текущий, следующий ждёт в очереди, а фаза SPEAKING
 * между кусками НЕ гаснет (иначе кнопка мигала бы «Говорю → Голос» на каждой пачке).
 * Эталон эха в гейте растёт вместе с ответом ([BargeInGate.onAssistantTextUpdated]), поэтому
 * перебивание продолжает отличать наш динамик от человека и на потоковой озвучке.
 *
 * ## Фаза «говорит»: точность ограничена движком
 * [onSpeakingChanged] включается на ПРИНЯТОМ синтезе, а не на первом аудиочанке: у
 * [VoiceOutputController.SpeechEngine] нет события начала воспроизведения —
 * [AndroidSpeechEngine] объявляет `UtteranceProgressListener.onStart` пустым, у офлайн-порта
 * ([OfflineTtsPort]) такого колбэка нет вовсе, [AndroidServerTtsPlayback] сообщает только
 * конец. Поэтому индикатор «Говорю» может опередить звук на время прогрева движка (у Piper —
 * до нескольких сотен миллисекунд). Выдумывать несуществующее событие нельзя: это дало бы ещё
 * более врущее состояние. Когда колбэк старта появится в движке — переключать здесь, в
 * [speakPieceNow], и только в этом месте.
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

    /**
     * Серверный клиент — типизированный, только ради темпа.
     *
     * Поле объявлено как [RepositoryServerTtsClient], а не как общий [ServerTtsClient],
     * потому что темп живёт у него (`setProsody`), а не в порту: порт принимает только
     * текст, и требовать от любого его реализации метод темпа значило бы ломать фейки.
     * `as?` не нужен: боевой путь собирает именно этот класс, а тестовый фейк просто не
     * попадёт в поле — он передаётся в параметр [serverTts].
     */
    private val serverClientProsody: RepositoryServerTtsClient? =
        serverTts as? RepositoryServerTtsClient

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
        onSpeechFinished = { onEngineSilent() },
    )

    /** Проигрывание серверных байтов: только когда серверный клиент реально дан. */
    private val playback: AndroidServerTtsPlayback? =
        if (serverTts == null) {
            null
        } else {
            AndroidServerTtsPlayback(appContext, onFinished = { onEngineSilent() })
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
                    if (speaking) setSpeaking(true) else onEngineSilent()
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

    /**
     * Звук идёт прямо сейчас.
     *
     * Точность этого флага — не косметика: контроллер голосового режима по нему решает, можно ли
     * открывать микрофон ([LocalVoiceController] → `canRestartContinuous`). `true` держится от
     * принятого синтеза до РЕАЛЬНОГО конца последнего сэмпла: движок сообщает тишину только после
     * того, как доигран `AudioTrack` (у офлайн-порта — по [ru.mars.mobile.tts.OfflineTtsBackend.isSpeaking],
     * а не по `isReady()`, который истинен и во время речи). Между кусками потокового ответа флаг
     * тоже не гаснет, иначе микрофон открывался бы в паузах посреди фразы.
     */
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
    /**
     * Применить темп и голос к движку.
     *
     * Живёт здесь, а не на экране, по одной причине: экран знает только шторку, а тем
     * надо доехать до ТОГО движка, который реально говорит — Piper, серверный или
     * системный. Цепочка выбирается в конструкторе, и только этот класс знает, какой
     * из трёх сейчас в работе.
     *
     * Вызов безопасен в любой момент: движки применяют значение к СЛЕДУЮЩЕЙ реплике и
     * не обрывают текущую (человек двигал настройку, а не просил тишины).
     *
     * `null` — вернуть темп по умолчанию (сброс настройки).
     */
    fun setProsody(prosody: VoiceProsody?) {
        val value = prosody ?: VoiceProsody()
        runCatching { engine.setProsody(value) }
        // Серверный движок держит темп у себя в запросе: он получает значение отдельно,
        // потому что обёртка [PreferOfflineEngine] не обязана знать про `api/tts`.
        runCatching { serverClientProsody?.setProsody(value) }
    }

    fun speak(text: String?): Boolean {
        if (released) return false
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) return false
        // Новая реплика не наследует нарезку предыдущей: буфер и очередь прошлого ответа в мусор.
        resetStream()
        // Старый звук убирается СТРОГО до нового и с подтверждением тишины (см. [cutCurrent]):
        // иначе стоп-флаг движка глушит первое слово новой реплики.
        cutCurrent()
        pending.addLast(Piece(body, first = true))
        return pump()
    }

    /**
     * Начать ответ в потоковом режиме: текста ещё нет, но всё прежнее должно замолчать.
     *
     * Зачем отдельно от [speak]: первый кусок ответа ещё не пришёл, а старую озвучку (например
     * подсказку «Секунду, думаю») надо погасить заранее — тогда первая же дельта уходит в синтез,
     * не тратя время на отмену.
     *
     * @return `false` — объект освобождён.
     */
    fun beginStream(): Boolean {
        if (released) return false
        resetStream()
        streamOpen = true
        cutCurrent()
        return true
    }

    /**
     * Отдать очередную дельту текста ответа.
     *
     * Нарезка ([ru.mars.mobile.voice.SpeechChunker]) решает сама: первый кусок — по клаузу,
     * следующие — пачками ~300 символов по границе слова. Куски не теряются: пока движок
     * говорит, они ждут в очереди и стартуют сразу после конца предыдущего.
     *
     * @return `true` — в синтез ушёл (или встал в очередь) хотя бы один кусок; `false` —
     *   накопили, но говорить пока нечего, либо поток не начат.
     */
    fun appendStream(delta: String?): Boolean {
        if (released || !streamOpen) return false
        if (delta.isNullOrEmpty()) return false
        chunkBuffer += delta
        var emitted = false
        while (true) {
            val chunk = SpeechChunker.nextChunk(chunkBuffer, first = chunksSaid == 0) ?: break
            chunkBuffer = chunk.rest
            chunksSaid++
            pending.addLast(Piece(chunk.text, first = chunksSaid == 1))
            emitted = true
        }
        if (!emitted) return false
        pump()
        return true
    }

    /**
     * Текст ответа кончился: отдать остаток буфера и закрыть поток.
     *
     * Остаток короче пачки уходит целиком — это хвост ответа, ждать больше нечего. Фаза
     * SPEAKING погаснет сама, когда отзвучит последний кусок.
     *
     * @return `true` — остаток (или очередь) принят; `false` — поток не был начат.
     */
    fun endStream(): Boolean {
        if (released || !streamOpen) return false
        val tail = SpeechChunker.flush(chunkBuffer)
        chunkBuffer = ""
        streamOpen = false
        tail.forEach { text ->
            chunksSaid++
            pending.addLast(Piece(text, first = chunksSaid == 1))
        }
        return pump() || pending.isNotEmpty() || engineBusy
    }

    /** Немедленная тишина (новый ход, уход с экрана, конец диалога). */
    fun stop() {
        // Очередь и буфер выбрасываются ДО стопа: колбэк движка «кончилось» иначе запустил бы
        // следующий кусок уже отменённого ответа.
        pending.clear()
        resetStream()
        val waiter = silenceWaiter
        silenceWaiter = null
        stopping = true
        runCatching { output.stopSpeaking() }
        stopping = false
        engineBusy = false
        // Ожидавший старт больше не нужен: его кусок выброшен вместе с очередью.
        waiter?.complete(Unit)
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

    // --- очередь кусков и стоп старого звука -------------------------------------------------

    /**
     * Куски речи по порядку. Очередь, а не «сказать всё сразу», потому что движок синтезирует
     * одну реплику за раз; фаза SPEAKING при этом не гаснет между кусками (см. [onEngineSilent]).
     */
    private val pending = ArrayDeque<Piece>()

    /** Накопленный, но ещё не отданный в синтез текст текущего потока. */
    private var chunkBuffer: String = ""

    /** Сколько кусков текущего ответа уже отдано: по нему решается «это первый кусок?». */
    private var chunksSaid: Int = 0

    /** Поток ответа открыт (ждём ещё текст): между кусками «говорит» не выключаем. */
    private var streamOpen: Boolean = false

    /** Движок занят нашей репликой: новый кусок либо ждёт, либо требует отмены (см. [cutCurrent]). */
    private var engineBusy: Boolean = false

    /** Мы сами гасим звук: колбэк движка в этот момент не означает «можно говорить дальше». */
    private var stopping: Boolean = false

    /** Кто ждёт подтверждённой тишины перед стартом — резолвится в [onEngineSilent]. */
    private var silenceWaiter: CompletableDeferred<Unit>? = null

    /** Один кусок речи и признак «первый в ответе» (первый задаёт эталон эха в гейте). */
    private data class Piece(val text: String, val first: Boolean)

    /** Сбросить состояние нарезки: буфер, счётчик кусков, открытость потока, очередь. */
    private fun resetStream() {
        chunkBuffer = ""
        chunksSaid = 0
        streamOpen = false
        pending.clear()
    }

    /**
     * Отдать в синтез всё, что можно отдать прямо сейчас.
     *
     * @return `true` — кусок принят движком или ждёт своей очереди; `false` — говорить нечего.
     */
    private fun pump(): Boolean {
        if (released) return false
        // Движок занят: кусок остаётся в очереди и стартует из [onEngineSilent].
        if (engineBusy) return true
        val piece = pending.removeFirstOrNull() ?: return false
        return speakPieceNow(piece)
    }

    /**
     * Сказать один кусок НЕМЕДЛЕННО (движок свободен).
     *
     * Фаза «говорит» включается здесь, на принятом синтезе. Точнее нельзя: события «первый
     * аудиочанк» у [VoiceOutputController.SpeechEngine] нет — это ограничение движка, а не
     * недосмотр (см. KDoc класса).
     */
    private fun speakPieceNow(piece: Piece): Boolean {
        if (piece.first) {
            // Эталон эха — произносимый кусок; дальше он растёт вместе с ответом.
            gate.onAnswerStarted(nowMs(), route(), aecActive(), piece.text)
        } else {
            gate.onAssistantTextUpdated(piece.text)
        }
        return when (output.speakNow(piece.text)) {
            VoiceOutputController.SpeakOutcome.SPOKEN -> {
                engineBusy = true
                setSpeaking(true)
                true
            }

            VoiceOutputController.SpeakOutcome.NOTHING_TO_SPEAK -> {
                // Читать нечего (markdown, код, ссылки). Среди чужих кусков это норма: кусок
                // пропускаем и идём дальше — иначе один блок кода обрывал бы весь ответ.
                engineBusy = false
                if (pending.isNotEmpty()) return pump()
                if (streamOpen) return true
                resetStream()
                gate.onAnswerFinished()
                finishSpeaking()
                false
            }

            VoiceOutputController.SpeakOutcome.ENGINE_UNAVAILABLE -> {
                pending.clear()
                resetStream()
                gate.onAnswerFinished()
                finishSpeaking()
                publishAvailable(false)
                false
            }
        }
    }

    /**
     * Убрать звучащий кусок и ДОЖДАТЬСЯ подтверждения тишины перед новым.
     *
     * Почему нельзя просто `stop(); speak()`: стоп-флаг движка живёт ещё мгновение после
     * `stop()`. На Android офлайн-порт (Piper) освобождает `AudioTrack` в своём потоке записи, и
     * реплика, отданная сразу, попадает под тот же стоп — первое предложение нового ответа
     * пропадало. Ждём именно колбэк «звук кончился» ([onEngineSilent]) с жёстким таймаутом-
     * страховкой, а не паузу-сон: сон и короче реальной отмены (слово всё равно терялось), и
     * дольше неё (лишняя тишина в каждом ответе).
     */
    private fun cutCurrent() {
        if (!engineBusy) return
        val waiter = CompletableDeferred<Unit>()
        silenceWaiter = waiter
        stopping = true
        runCatching { output.stopSpeaking() }
        stopping = false
        if (!engineBusy) {
            // Движок подтвердил отмену синхронно (системный TTS, офлайн-порт) — ждать нечего,
            // и никакой задержки в этом, самом частом, случае не добавляется.
            if (silenceWaiter === waiter) silenceWaiter = null
            return
        }
        workScope.launch {
            val confirmed = withTimeoutOrNull(SILENCE_WAIT_MS) { waiter.await() }
            if (silenceWaiter === waiter) silenceWaiter = null
            if (released) return@launch
            // Пока ждали, колбэк мог уже отдать очередь дальше: тогда движок занят новым
            // куском и трогать его состояние нельзя.
            // Колбэка не было вовсе — идём дальше своими силами, иначе новый ответ не
            // зазвучал бы никогда.
            if (confirmed == null) engineBusy = false
            pump()
        }
    }

    /**
     * Движок сообщил «реплика кончилась»: дочитал, упал или её остановили мы.
     *
     * Здесь же решается, кончился ли ОТВЕТ: пока в очереди есть куски или поток ещё открыт,
     * фаза SPEAKING не гаснет — иначе кнопка мигала бы на каждой пачке.
     */
    private fun onEngineSilent() {
        engineBusy = false
        val waiter = silenceWaiter
        silenceWaiter = null
        // Подтверждённая тишина: ожидавший старт может отдавать свой кусок.
        waiter?.complete(Unit)
        // Отменяли мы сами: следующий кусок отдаст вызывающий (cutCurrent → pump), а не колбэк.
        if (stopping) return
        if (pending.isNotEmpty()) {
            pump()
            return
        }
        // Поток жив, а текста пока нет: ответ ещё не кончился, ждём следующей дельты.
        if (streamOpen) return
        finishSpeaking()
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
        pending.clear()
        resetStream()
        // Ожидавший старт не должен пережить освобождение: его куска уже нет.
        silenceWaiter?.complete(Unit)
        silenceWaiter = null
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
        engineBusy = false
        resetStream()
        gate.onAnswerFinished()
        runCatching { onSpeakingChanged(false) }
    }

    private fun publishAvailable(value: Boolean) {
        if (released) return
        if (lastAvailable == value) return
        lastAvailable = value
        runCatching { onAvailableChanged(value) }
    }

    private companion object {
        /**
         * Сколько ждём подтверждения отмены перед стартом нового куска. Это страховка от
         * «движок не прислал колбэк», а не пауза тишины: обычно подтверждение приходит
         * синхронно/за миллисекунды, и ожидание заканчивается сразу.
         */
        const val SILENCE_WAIT_MS = 400L
    }
}
