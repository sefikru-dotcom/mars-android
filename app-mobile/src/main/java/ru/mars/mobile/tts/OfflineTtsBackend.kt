package ru.mars.mobile.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.mars.mobile.PcmWavWriter
import ru.mars.mobile.VoiceNormalizer
import ru.mars.mobile.domain.util.SpeechContentFilter
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Офлайн-синтез русской речи на sherpa-onnx (Piper/VITS `ru_RU-irina-medium` float32, 22 050 Гц,
 * 1 спикер). Сеть нужна ровно один раз — чтобы скачать модель ([OfflineTtsModel]); сам синтез
 * после установки работает в авиарежиме.
 *
 * ## Почему кусками, а не целиком
 * `OfflineTts.generate()` возвращает весь ответ одним `GeneratedAudio` (float[] на всю
 * реплику): для длинного ответа это десятки секунд PCM в куче и полная тишина, пока
 * синтез не закончится. Поэтому используется `generateWithCallback`: native отдаёт
 * float[]-куски (по предложению), каждый кусок конвертируется в PCM16 и уходит в
 * `AudioTrack` — речь начинается с первого предложения, а память ограничена размером куска.
 * Возврат `0` из колбэка = «продолжай», ненулевой = «остановись» — это и есть путь отмены.
 *
 * ## Буфер упреждения (почему трек больше не голодает)
 * Раньше колбэк писал кусок прямо в трек и ВОЗВРАЩАЛСЯ только после того, как трек его
 * принял: пока native синтезирует следующее предложение, трек доигрывает буфер и
 * останавливается — в логе `disabled due to previous underrun`, на слух «дребезг».
 * Теперь колбэк только КЛАДЁТ кусок в очередь ([PREFETCH_CHUNKS] штук вперёд) и сразу
 * возвращается; отдельный поток-фидер раскладывает очередь в трек неблокирующим
 * `write(NON_BLOCKING)` и спит по `getPlaybackHeadPosition`, пока в буфере трека есть место.
 * Итог: native никогда не ждёт драйвер, а драйвер почти всегда имеет запас звука.
 * Пока очередь заполнена, колбэк блокируется на `put` — это и есть backpressure, не дающий
 * раздувать память на длинной реплике.
 *
 * ## Отмена
 * [stop] обязан прерывать и синтез, и воспроизведение, поэтому отмена двухступенчатая:
 * 1. у реплики поднимается флаг `cancelled`, опустошается очередь, её `AudioTrack` ставится
 *    на паузу и `flush`-ится — и блокирующий `put` в колбэке, и `write` в фидере
 *    разблокируются сразу (короткое ожидание — не больше [POLL_MS]), звук обрывается;
 * 2. колбэк видит флаг и возвращает `1` — native прекращает генерацию, не досинтезировав
 *    остаток текста.
 * Очередь НЕ доигрывается после `stop`: остаток выбрасывается, а не проигрывается (иначе
 * «стоп» звучал бы ещё пару секунд).
 *
 * ## Потоки
 * Генерация идёт на ОДНОМ выделенном потоке: `OfflineTts` не потокобезопасен, а колбэк
 * приходит на тот же поток, что вызвал `generateWithCallback`. Рядом на реплику живёт один
 * поток-фидер — он единственный трогает `AudioTrack`. [speak]/[stop]/[isReady] можно звать
 * с любого потока; [speak] не блокирует — вторая реплика перебивает первую, а не встаёт
 * в очередь.
 *
 * ## ГРАБЛИ, заложенные в код
 * 1. `lexicon` у этой модели НЕТ: при непустой строке native ищет файл лексикона и не
 *    находит его. Пустая строка — обязательна. `dataDir` — распакованный `espeak-ng-data`,
 *    без него espeak-ng читает русский текст по буквам.
 * 2. Конструктор `OfflineTts` бросает `IllegalArgumentException`, если native вернул
 *    нулевой указатель (битые файлы модели, нет ABI). Но часть ошибок конфига native
 *    завершает процесс (`SHERPA_ONNX_EXIT`), без исключения — поэтому конфиг собирается
 *    строго из манифеста, а не из пользовательских настроек.
 * 3. `AudioTrack` строится по фактической частоте движка (`sampleRate()`), а не по
 *    константе: у этой модели она 22 050, но доверять константе при смене модели нельзя —
 *    расхождение даёт «бурундук» без ошибок.
 * 4. `AudioTrack.stop()` в `MODE_STREAM` доигрывает уже записанное; `release()` сразу
 *    после `stop()` обрезал бы хвост фразы. Поэтому перед освобождением ждём, пока
 *    `playbackHeadPosition` дойдёт до записанного объёма (с бюджетом по длительности).
 * 5. `size` у `AudioTrack` — это буфер ДРАЙВЕРА плюс наш: 4× минимум означает ~0,4 с запаса
 *    при 22 050 Гц. Меньше не годится: при 1× (≈0,1 с) любая пауза планировщика даёт underrun.
 */
class OfflineTtsBackend(
    context: Context,
    private val model: OfflineTtsModel = OfflineTtsModel.forApp(context),
    /** Темп: 1.0 — как в модели, >1 быстрее. Уходит в `lengthScale` конфига VITS. */
    private val initialSpeed: Float = 1.0f,

    /** Индекс голоса. У irina он один (0); при `numSpeakers() == 1` значение игнорируется. */
    private val speakerId: Int = 0,
    /** «Звук пошёл / звук кончился» для хоста (сервис W2 переключает состояние уведомления). */
    private val onSpeakingChanged: (Boolean) -> Unit = {},
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) : OfflineTtsPort {

    private val lock = Any()

    /**
     * Чистка текста перед синтезом: [SpeechContentFilter] снимает markdown/отступы/
     * псевдографику, [VoiceNormalizer] читает числа, проценты и единицы словами.
     *
     * Оба класса без состояния — по экземпляру на бэкенд, чтобы не собирать Regex-наборы на
     * каждую реплику.
     */
    private val filter = SpeechContentFilter()

    private val normalizer = VoiceNormalizer()

    /**
     * Текущий темп. Изменяемое поле, а не только параметр конструктора: человек меняет
     * скорость посреди диалога, а движок создаётся ОДИН раз на сессию (подготовка
     * модели — секунды). Пересоздавать его ради «1.15×» значило бы заново читать
     * 60 МБ с диска.
     *
     * `@Volatile`: пишется с главного потока, читается из потока синтеза. Значение
     * атомарно, но видимость обязана быть.
     */
    @Volatile
    private var speed: Float = initialSpeed

    /** Подготовка сериализуется: два параллельных `prepare` не должны качать модель дважды. */
    private val prepareMutex = Mutex()

    /** Нативный движок. `null` — модель ещё не поднята (или уже освобождена). */
    private var engine: OfflineTts? = null

    /** Текущая реплика: новая заменяет предыдущую (перебивание). */
    private var active: Utterance? = null

    @Volatile
    private var released: Boolean = false

    /** Фактическая частота движка: по ней строится `AudioTrack`. */
    @Volatile
    private var sampleRate: Int = OfflineTtsManifest.SAMPLE_RATE

    /**
     * Один поток на всё: генерация + запись в трек. Daemon — не держит процесс, если хост
     * забыл [release] (сервис убит системой).
     */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD_NAME).apply { isDaemon = true }
    }

    override suspend fun prepare(): Boolean {
        synchronized(lock) {
            if (released) return false
            if (engine != null) return true
        }
        return prepareMutex.withLock {
            synchronized(lock) {
                if (released) return@withLock false
                if (engine != null) return@withLock true
            }
            withContext(Dispatchers.IO) {
                if (!model.ensure()) {
                    log("tts model is not installed: ${model.modelDir.absolutePath}")
                    return@withContext false
                }
                val created = try {
                    createEngine(model.modelDir)
                } catch (t: Throwable) {
                    // native отвечает IllegalArgumentException на невалидный конфиг.
                    log("failed to create OfflineTts: ${t.message}")
                    null
                } ?: return@withContext false

                val rate = runCatching { created.sampleRate() }.getOrDefault(0)
                val speakers = runCatching { created.numSpeakers() }.getOrDefault(0)
                if (speakers > 0 && speakerId !in 0 until speakers) {
                    log("speaker $speakerId out of range (speakers=$speakers), using 0")
                }
                synchronized(lock) {
                    if (released) {
                        runCatching { created.release() }
                        false
                    } else {
                        engine = created
                        sampleRate = if (rate > 0) rate else OfflineTtsManifest.SAMPLE_RATE
                        log("offline tts ready: ${sampleRate} Hz, speakers=$speakers")
                        true
                    }
                }
            }
        }
    }

    override fun speak(text: String): Boolean {
        val body = spokenBody(text)
        if (body.isEmpty()) return false
        val current = synchronized(lock) {
            if (released) return false
            engine ?: return false
        }
        val utterance = Utterance(body)
        val previous = synchronized(lock) {
            if (released) return false
            active.also { active = utterance }
        }
        // Перебивание: прежняя реплика глушится до постановки новой, иначе два голоса
        // накладываются на одном устройстве вывода.
        previous?.cancel()
        return try {
            worker.execute { runUtterance(current, utterance) }
            true
        } catch (e: RejectedExecutionException) {
            // Бэкенд уже освобождён — звука не будет, и врать хосту нельзя.
            synchronized(lock) { if (active === utterance) active = null }
            false
        }
    }

    override fun stop() {
        val utterance = synchronized(lock) { active.also { active = null } }
        utterance?.cancel()
    }

    override fun isReady(): Boolean = synchronized(lock) { !released && engine != null }

    /**
     * Реплика ещё звучит: принята, синтезируется, играет или доигрывает хвост в `AudioTrack`.
     *
     * Зачем отдельный сигнал, если есть [isReady]. [isReady] отвечает на вопрос «движок поднят и
     * может синтезировать» — и остаётся `true` ВСЁ время речи. Хост, который принимал его за
     * «договорил», решал, что ответ кончился через 100 мс после первого слова: открывался
     * микрофон, и Марс слышал сам себя (ровно этот баг и был в `OfflineTtsSpeechEngine`).
     *
     * `active != null` — точная граница реплики: [speak] ставит её ДО старта потока синтеза (то
     * есть «ещё даже не начал» тоже считается занятостью), а [finish] снимает её ПОСЛЕ
     * `drainAndRelease`, то есть после того, как последний сэмпл доигран и трек освобождён.
     * [stop] и [release] снимают её сразу — остановленная реплика «звучит» ровно ноль.
     */
    fun isSpeaking(): Boolean = synchronized(lock) { !released && active != null }

    /**
     * Сменить темп для последующих реплик.
     *
     * Два пути, и выбор между ними не косметический:
     *  - движок уже поднят → меняем только поле [speed]. Нативный объект берёт темп
     *    аргументом КАЖДОГО `generateWithCallback`, поэтому следующая фраза зазвучит
     *    с новым темпом без пересоздания модели;
     *  - движок ещё не поднят → значение сгорит в конфиге при [prepare] (`lengthScale`
     *    считается из него). Это не двойная запись, а единственный способ не потерять
     *    настройку, выставленную до первого синтеза.
     *
     * Текущая реплика НЕ обрывается: человек менял настройку, а не просил тишины.
     * Значение вне границ зажимается — native на `lengthScale <= 0` даёт мусор, а не ошибку.
     */
    fun setSpeed(value: Float) {
        if (value.isNaN()) return
        speed = value.coerceIn(MIN_SPEED, MAX_SPEED)
    }

    /**
     * Синтез одной фразы в готовый WAV — резерв потоковой озвучки серверного чата.
     *
     * ## Почему на том же потоке, что и [speak]
     * `OfflineTts` не потокобезопасен: два `generate*` одновременно — падение в native, а не
     * исключение. Поэтому работа ставится в ТОТ ЖЕ однопоточный [worker], что и реплики
     * [speak]: фразы идут строго одна за другой, без мьютексов и без окна, где два синтеза
     * конкурируют за один нативный объект.
     *
     * ## Отмена
     * Отмена корутины-вызывающего не обрывает native (у `generate` нет внешнего прерывания),
     * поэтому она двухступенчатая: `invokeOnCancellation` поднимает флаг, а колбэк синтеза
     * видит его и возвращает [STOP] — native прекращает генерацию на ближайшем предложении.
     * Плюс к этому результат уже никому не нужен: `isActive` не даёт возобновить мёртвую
     * корутину. Без флага отменённая фраза занимала бы поток синтеза до конца, и следующая
     * реплика ждала бы звук, который выбрасывается.
     *
     * ## Отказы
     * Наружу не бросает: движок не поднят, пустой произносимый текст, исключение native —
     * всё это `null`. Исключение — [kotlinx.coroutines.CancellationException], она
     * пробрасывается: это отмена вызывающего, а не отказ синтеза.
     *
     * @return WAV (PCM16 моно, частота модели) либо `null`, если звука не будет.
     */
    override suspend fun synthesizeToWav(text: String): ByteArray? {
        val body = spokenBody(text)
        if (body.isEmpty()) return null
        val current = synchronized(lock) {
            if (released) return null
            engine
        } ?: return null
        val cancelled = AtomicBoolean(false)
        return suspendCancellableCoroutine<ByteArray?> { continuation ->
            continuation.invokeOnCancellation { cancelled.set(true) }
            val task = Runnable {
                // Освобождение могло встать в очередь раньше нас: трогать нативный объект
                // после `release()` нельзя (это падение процесса, а не исключение).
                val alive = synchronized(lock) { !released && engine === current }
                val wav = if (!alive) {
                    null
                } else {
                    try {
                        generateWav(current, body, cancelled)
                    } catch (t: Throwable) {
                        log("offline wav synthesis failed: ${t.message}")
                        null
                    }
                }
                if (continuation.isActive) continuation.resume(wav)
            }
            try {
                worker.execute(task)
            } catch (rejected: RejectedExecutionException) {
                // Бэкенд уже освобождён: потока синтеза нет, звука не будет.
                log("offline wav synthesis skipped: worker is gone")
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    /**
     * Сгенерировать WAV целиком в памяти. Вызывается ТОЛЬКО с потока [worker].
     *
     * `generateWithCallback` (а не `generate`) выбран ради отмены: колбэк получает кусок
     * после каждого предложения и может вернуть [STOP], прекратив генерацию остатка.
     * `maxNumSentences = 1` в конфиге значит «кусок на предложение» — пауз в речи это не
     * добавляет, зато даёт точки, где отмена срабатывает быстро.
     */
    private fun generateWav(engine: OfflineTts, body: String, cancelled: AtomicBoolean): ByteArray? {
        val pcm = ByteArrayOutputStream()
        var samples = 0
        engine.generateWithCallback(
            body,
            resolveSpeaker(engine),
            speed,
            SynthesisCallback(
                object : SynthesisCallback.Handler {
                    override fun onSamples(chunk: FloatArray): Int {
                        if (cancelled.get()) return STOP
                        if (chunk.isEmpty()) return CONTINUE
                        val encoded = toPcm16(chunk)
                        pcm.write(encoded, 0, encoded.size)
                        samples += chunk.size
                        return CONTINUE
                    }
                },
            ),
        )
        if (cancelled.get() || samples == 0) return null
        val rate = if (sampleRate > 0) sampleRate else OfflineTtsManifest.SAMPLE_RATE
        return PcmWavWriter.encode(pcm.toByteArray(), rate, 1)
    }

    override fun release() {
        val utterance: Utterance?
        val engineToRelease: OfflineTts?
        synchronized(lock) {
            if (released) return
            released = true
            utterance = active
            active = null
            engineToRelease = engine
            engine = null
        }
        utterance?.cancel()
        if (engineToRelease != null) {
            // Освобождение — на тот же поток, что и синтез: `release()` не должен выполняться
            // одновременно с `generateWithCallback` в native (это падение, а не исключение).
            try {
                worker.execute { runCatching { engineToRelease.release() } }
            } catch (e: RejectedExecutionException) {
                log("release skipped: worker is gone")
            }
        }
        // shutdown, а не shutdownNow: уже поставленное освобождение обязано выполниться.
        worker.shutdown()
    }

    // --- внутреннее -----------------------------------------------------------------------

    /** Собрать движок по каталогу модели. Ошибки native приходят исключением (см. KDoc). */
    private fun createEngine(modelDir: File): OfflineTts {
        val vits = OfflineTtsVitsModelConfig(
            model = File(modelDir, OfflineTtsManifest.MODEL_FILE).absolutePath,
            // Лексикона у модели нет: непустая строка заставит native искать файл.
            lexicon = "",
            tokens = File(modelDir, OfflineTtsManifest.TOKENS_FILE).absolutePath,
            dataDir = File(modelDir, OfflineTtsManifest.ESPEAK_DIR).absolutePath,
            dictDir = "",
            noiseScale = OfflineTtsManifest.NOISE_SCALE,
            noiseScaleW = OfflineTtsManifest.NOISE_SCALE_W,
            // Темп — параметр конструктора: он же «скорость речи» для пользователя.
            lengthScale = OfflineTtsManifest.LENGTH_SCALE / speed.coerceIn(MIN_SPEED, MAX_SPEED),
        )
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = vits,
                numThreads = NUM_THREADS,
                debug = false,
                provider = "cpu",
            ),
            ruleFsts = "",
            ruleFars = "",
            // По предложению за раз: колбэк получает кусок сразу после каждого предложения,
            // и речь начинается, не дожидаясь синтеза всего ответа.
            maxNumSentences = 1,
            silenceScale = SILENCE_SCALE,
        )
        // assetManager не передаём: модель лежит на диске (newFromFile), а не в assets.
        return OfflineTts(config = config)
    }

    /**
     * Текст, который реально услышит человек: espeak-ng фронтенд piper не прощает разметки.
     *
     * Зачем чистить ЗДЕСЬ, а не только в вызывающем коде: у бэкенда несколько входов
     * ([ru.mars.mobile.LocalVoiceTts], сервис уведомлений, прямые вызовы порта), и каждый
     * новый однажды забудет фильтр. Тогда `**важно**` читается как «звёздочка звёздочка»,
     * `│` даёт щелчок, а `—` — либо слово «тире», либо рваную паузу посреди фразы.
     *
     * Порядок: сначала markdown и структура ([SpeechContentFilter.stripForSpeech]), потом
     * числа словами ([VoiceNormalizer.normalize]) — иначе «1. пункт» превратился бы в
     * «один точка пункт» ещё до снятия маркера списка.
     *
     * @return произносимая строка; пустая — говорить нечего (только разметка/эмодзи).
     */
    private fun spokenBody(text: String): String {
        val stripped = runCatching { filter.stripForSpeech(text) }.getOrDefault(text)
        if (stripped.isBlank()) return ""
        val normalized = runCatching { normalizer.normalize(stripped) }.getOrDefault(stripped)
        var body = if (normalized.isBlank()) stripped else normalized
        // Страховка для текста, пришедшего в обход фильтра. Тире — в запятую-паузу: espeak-ng
        // читает «—» словом «тире» либо проглатывает его вместе с паузой.
        body = PIPER_DASH.replace(body, ", ")
        body = PIPER_NOISE.replace(body, " ")
        body = INNER_SPACES.replace(body, " ")
        body = SPACE_BEFORE_PUNCT.replace(body, "$1")
        return body.trim()
    }

    /** Тело реплики: синтез кусками в очередь + отдельный поток-фидер в `AudioTrack`. */
    private fun runUtterance(engine: OfflineTts, utterance: Utterance) {
        if (utterance.cancelled.get()) {
            finish(utterance)
            return
        }
        // Локальная non-null ссылка: внутри колбэка захваченная `var` не умный каст.
        val track = try {
            createTrack(sampleRate)
        } catch (t: Throwable) {
            log("AudioTrack is not available: ${t.message}")
            finish(utterance)
            return
        }
        var feeder: Thread? = null
        try {
            utterance.track = track
            // Отмена могла прийти между созданием трека и подпиской: тогда не начинаем звук.
            if (utterance.cancelled.get()) return
            track.play()
            // Фидер стартует ДО генерации: первый кусок ляжет в трек, как только появится,
            // а колбэк синтеза его не ждёт.
            feeder = Thread({ feed(utterance, track) }, FEEDER_THREAD_NAME).apply {
                isDaemon = true
            }
            feeder.start()
            engine.generateWithCallback(
                utterance.text,
                resolveSpeaker(engine),
                speed,
                // Java-класс с объявленным возвратом Integer: JNI ищет именно
                // invoke([F)Ljava/lang/Integer;, а Kotlin-лямбда под R8 этот тип теряет
                // и native убивает процесс через abort(). См. SynthesisCallback.java.
                SynthesisCallback(
                    object : SynthesisCallback.Handler {
                        override fun onSamples(samples: FloatArray): Int = when {
                            utterance.cancelled.get() -> STOP
                            samples.isEmpty() -> CONTINUE
                            // Кладём в очередь и сразу возвращаемся: драйвер наполняет фидер.
                            !enqueueChunk(utterance, samples) -> STOP
                            else -> CONTINUE
                        }
                    },
                ),
            )
        } catch (t: Throwable) {
            // Сюда приходят только «мягкие» отказы (трек, конвертация). Native-аборт
            // процесса ловится не здесь — см. KDoc, грабли 2.
            log("tts synthesis failed: ${t.message}")
        } finally {
            // Синтез закончился: даём фидеру дописать остаток и сами гасим трек.
            utterance.synthesisDone = true
            if (feeder != null) {
                // Ждём фидер, а не drain-and-release параллельно: два потока не должны
                // одновременно трогать `AudioTrack`. Бюджет — по длительности + запас,
                // как в старом drain; прерывается отменой (фидер тогда встаёт сразу).
                drainAndRelease(utterance, track, feeder)
            } else {
                runCatching { track.release() }
            }
            finish(utterance)
        }
    }

    /** Индекс голоса, существующий у модели: у irina он один. */
    private fun resolveSpeaker(engine: OfflineTts): Int {
        val speakers = runCatching { engine.numSpeakers() }.getOrDefault(0)
        return if (speakers <= 0) 0 else speakerId.coerceIn(0, speakers - 1)
    }

    /**
     * Положить PCM16-кусок в очередь упреждения (вызывается из колбэка native).
     *
     * Блокируется, только если очередь полна ([PREFETCH_CHUNKS] кусков) — это backpressure,
     * чтобы длинная реплика не копила звук в памяти. Пока очередь не полна, возврат
     * мгновенный: native синтезирует следующее предложение, не дожидаясь драйвера.
     *
     * @return `false` — класть больше нельзя (отмена, фидер умер, отказ драйвера).
     */
    private fun enqueueChunk(utterance: Utterance, samples: FloatArray): Boolean {
        val pcm = toPcm16(samples)
        val deadline = System.currentTimeMillis() + HOLD_BUDGET_MS
        while (System.currentTimeMillis() < deadline) {
            if (utterance.cancelled.get() || utterance.failed.get()) return false
            // Ждём с таймаутом, а не `put`: отмена обязана поднимать колбэк сразу.
            if (runCatching { utterance.queue.offer(pcm, HOLD_POLL_MS, TimeUnit.MILLISECONDS) }
                    .getOrDefault(false)
            ) {
                return true
            }
        }
        // Очередь не освободилась за бюджет: драйвер не берёт звук — дальше синтезировать
        // бессмысленно.
        log("prefetch queue stuck, stopping synthesis")
        return false
    }

    /**
     * Поток-фидер: единственный, кто пишет в `AudioTrack`.
     *
     * Неблокирующий `write`: пока в буфере трека есть место — пишет; места нет — спит
     * [POLL_MS] и смотрит на `playbackHeadPosition`. Так драйвер никогда не голодает
     * (мы не ждём генерацию), а мы не ждём драйвер на блокирующем вызове.
     */
    private fun feed(utterance: Utterance, track: AudioTrack) {
        var head: Long = 0
        try {
            while (true) {
                if (utterance.cancelled.get()) return
                // `null` от poll = таймаут; реплика закончилась и очередь пуста — выходим.
                val pcm = utterance.queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
                    ?: if (utterance.synthesisDone) return else continue
                var offset = 0
                while (offset < pcm.size) {
                    if (utterance.cancelled.get()) return
                    val written = try {
                        track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                    } catch (e: IllegalStateException) {
                        // Трек освобождён параллельным stop()/release().
                        return
                    }
                    if (written < 0) {
                        log("AudioTrack.write failed: $written")
                        utterance.failed.set(true)
                        return
                    }
                    if (written == 0) {
                        // Буфер полон: спим долю тишины, что ещё есть в треке, и повторяем.
                        // 0 попыток подряд (мёртвый трек) отсекаются по флагу отмены и
                        // общему бюджету реплики в drainAndRelease.
                        Thread.sleep(POLL_MS)
                        continue
                    }
                    offset += written
                    head += written / 2
                    utterance.framesWritten = head
                    if (!utterance.started) {
                        utterance.started = true
                        runCatching { onSpeakingChanged(true) }
                    }
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            log("tts feeder failed: ${t.message}")
            utterance.failed.set(true)
        }
    }

    /**
     * Дождаться фидера, дождаться, пока записанное доиграет, и освободить трек.
     *
     * `stop()` в `MODE_STREAM` доигрывает буфер, но `release()` сразу после него обрезает
     * хвост фразы — поэтому ждём по `playbackHeadPosition` с бюджетом «длительность + запас».
     * Отмена (или отказ драйвера) обрывает ожидание: трек гасится немедленно, поэтому
     * `stop()` не звучит «ещё пару секунд».
     */
    private fun drainAndRelease(utterance: Utterance, track: AudioTrack, feeder: Thread) {
        try {
            val deadline = System.currentTimeMillis() + MAX_DRAIN_MS
            // Сначала даём фидеру дописать очередь в трек (он выходит сам, когда
            // synthesisDone и очередь пуста). Отменённая реплика — не ждём вовсе.
            if (!utterance.cancelled.get() && !utterance.failed.get()) {
                val waitMs = drainBudgetMs(utterance.framesWritten)
                runCatching { feeder.join(waitMs) }
            }
            val total = utterance.framesWritten
            if (total > 0 && !utterance.cancelled.get() && !utterance.failed.get()) {
                while (System.currentTimeMillis() < deadline && !utterance.cancelled.get()) {
                    val head = runCatching { track.playbackHeadPosition.toLong() }
                        .getOrDefault(total)
                    if (head >= total) break
                    Thread.sleep(DRAIN_POLL_MS)
                }
            }
            runCatching { track.stop() }
        } finally {
            // Фидер обязан встать до release: иначе он тронет уже освобождённый трек.
            runCatching { feeder.join(POLL_MS * 2) }
            runCatching { track.flush() }
            runCatching { track.release() }
            utterance.track = null
        }
    }

    /** Бюджет ожидания: сколько звучит записанное + запас на дрожание планировщика. */
    private fun drainBudgetMs(frames: Long): Long {
        val rate = if (sampleRate > 0) sampleRate else OfflineTtsManifest.SAMPLE_RATE
        val durationMs = frames * 1000L / rate
        return (durationMs + DRAIN_SLACK_MS).coerceAtMost(MAX_DRAIN_MS)
    }

    /** Реплика закончилась (доиграла, упала или её остановили). */
    private fun finish(utterance: Utterance) {
        synchronized(lock) { if (active === utterance) active = null }
        if (utterance.started) {
            utterance.started = false
            runCatching { onSpeakingChanged(false) }
        }
    }

    /**
     * Стриминговый трек под фактическую частоту модели.
     *
     * Вывод помечен как речь ассистента (`USAGE_ASSISTANT` + `CONTENT_TYPE_SPEECH`), как и
     * серверная озвучка: платформа должна считать звук «своим», иначе микрофонный AEC не
     * гасит его и голосовой цикл слышит собственный динамик.
     */
    private fun createTrack(rate: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = if (minBuffer > 0) minBuffer * BUFFER_MULTIPLIER else DEFAULT_BUFFER_BYTES
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** float32 -1..1 -> PCM16 little-endian (то, что ждёт `AudioTrack`). */
    private fun toPcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var i = 0
        for (sample in samples) {
            // 32767, а не 32768: положительное переполнение int16 отсекается клипом.
            val value = (sample.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt()
            out[i++] = (value and 0xFF).toByte()
            out[i++] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    /**
     * Одна реплика: свой флаг отмены, своя очередь упреждения и свой трек, чтобы stop()
     * не задел следующую.
     */
    private class Utterance(val text: String) {
        val cancelled = AtomicBoolean(false)

        /** Драйвер отказал (write < 0) или фидер умер: продолжать бессмысленно. */
        val failed = AtomicBoolean(false)

        /**
         * Очередь упреждения: колбэк синтеза кладёт, поток-фидер забирает. Ограничена
         * [PREFETCH_CHUNKS] — длинная реплика не копит звук в памяти.
         */
        val queue: ArrayBlockingQueue<ByteArray> = ArrayBlockingQueue(PREFETCH_CHUNKS)

        /** Синтез закончился: фидеру больше нечего ждать новых кусков. */
        @Volatile
        var synthesisDone: Boolean = false

        @Volatile
        var track: AudioTrack? = null

        @Volatile
        var started: Boolean = false

        /** Кадров (сэмплов) уже отдано в трек — по нему считается бюджет доигрывания. */
        @Volatile
        var framesWritten: Long = 0L

        /**
         * Прервать: пауза + flush опустошают буфер, поэтому и `offer` в колбэке, и
         * `write` в фидере видят флаг не позже чем через [POLL_MS] и выходят.
         * `stop()` — чтобы трек не остался «играющим» до освобождения.
         */
        fun cancel() {
            cancelled.set(true)
            queue.clear()
            val current = track ?: return
            runCatching { current.pause() }
            runCatching { current.flush() }
            runCatching { current.stop() }
        }
    }

    companion object {
        private const val TAG = "MarsOfflineTts"

        private const val THREAD_NAME = "mars-offline-tts"

        /** 2 потока: синтез VITS на 1 потоке не успевает за реальным временем на слабых SoC. */
        private const val NUM_THREADS = 2

        /**
         * Пауза между предложениями в синтезе (доля от естественной).
         *
         * Волна 20: было 0.2 — пауза сжималась в ПЯТЬ раз, и Ирина тараторила без дыхания,
         * сваливая предложения в кашу («звучит максимально убого»). 0.65 — почти
         * естественная человеческая пауза: между предложениями слышно вдох, но ответ не
         * растягивается. Управляет только тишиной ВНУТРИ реплики; между кусками нарезки
         * паузу задаёт [ru.mars.mobile.voice.SpeechChunker].
         */
        private const val SILENCE_SCALE = 0.65f

        /** Границы темпа: ниже 0.5 речь тянется неразборчиво, выше 2.0 — «бурундук». */
        private const val MIN_SPEED = 0.5f
        private const val MAX_SPEED = 2.0f

        /** Запас буфера трека к минимальному: колбэк приходит рывками по предложению. */
        private const val BUFFER_MULTIPLIER = 4

        /**
         * Сколько кусков (по предложению) держим в очереди упреждения. 3 = ~1,5–3 с звука:
         * пока трек играет один, native синтезирует два следующих. Меньше 2 — трек снова
         * начнёт голодать на медленном SoC (синтез предложения дольше его длительности).
         */
        private const val PREFETCH_CHUNKS = 3

        /** Поток-фидер `AudioTrack` (единственный, кто пишет в трек). */
        private const val FEEDER_THREAD_NAME = "mars-offline-tts-sink"

        /** Тишина опроса очереди/драйвера: 10 мс — цена late-stop и задержки дозаписи. */
        private const val POLL_MS = 10L

        /** Шаг ожидания места в очереди: отмена поднимает колбэк не позже этого. */
        private const val HOLD_POLL_MS = 20L

        /**
         * Бюджет ожидания места в очереди на ОДИН кусок. Дольше — драйвер не берёт звук
         * (отказ железа), синтез останавливается, а не висит.
         */
        private const val HOLD_BUDGET_MS = 15_000L

        /** Если `getMinBufferSize` вернул ошибку: ~0.5 с при 22 050 Гц моно 16 бит. */
        private const val DEFAULT_BUFFER_BYTES = 22_050 * 2 / 2

        private const val DRAIN_POLL_MS = 20L
        private const val DRAIN_SLACK_MS = 2_000L
        private const val MAX_DRAIN_MS = 15_000L

        /** Коды возврата колбэка `generateWithCallback`: 0 — продолжать, иначе — стоп. */
        private const val CONTINUE = 0
        private const val STOP = 1

        /**
         * Тире (длинное и среднее) — в запятую-паузу. espeak-ng читает `—` словом «тире»
         * либо проглатывает вместе с паузой; запятая — та же пауза без произносимого символа.
         */
        private val PIPER_DASH = Regex("""\s*[—–]\s*""")

        /**
         * Символы, которые espeak-ng произносит по имени или на которых «затыкается»:
         * markdown-разметка, кавычки, скобки, обратный слэш. К этому шагу текст уже
         * нормализован, поэтому такие символы — только мусор от разметки.
         */
        private val PIPER_NOISE = Regex("""[<>\[\]{}()«»"„“”*_`#>|~\\]+""")

        /** Пробелы/табы/неразрывные пробелы — в один пробел. */
        private val INNER_SPACES = Regex("""[ \t\u00A0]+""")

        /** «слово ,» — пробел перед знаком препинания не нужен. */
        private val SPACE_BEFORE_PUNCT = Regex("""\s+([,.;:!?…])""")
    }
}
