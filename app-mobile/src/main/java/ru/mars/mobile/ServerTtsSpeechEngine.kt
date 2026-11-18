package ru.mars.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.EdgeNeuralTtsClient
import ru.mars.network.repository.OpenAiSpeechTtsClient
import ru.mars.network.repository.PaidTtsClient
import ru.mars.network.repository.PlusVibeMediaTtsClient
import ru.mars.network.repository.ServerTtsContract
import ru.mars.network.repository.ServerTtsPrefetchKeys
import ru.mars.network.repository.ServerTtsRepository
import ru.mars.network.repository.ServerTtsResult
import ru.mars.network.repository.ServerTtsSpeechRequest
import ru.mars.network.repository.ServerTtsVoiceProfile
import ru.mars.network.repository.ServerTtsVoices
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Порты серверного синтеза — единственное, что нужно движку озвучки, чтобы не знать
 * ничего о сети, файлах и Android. Благодаря им [ServerTtsSpeechEngine] проверяется
 * юнит-тестами на чистой JVM.
 */

/**
 * Транспорт синтеза: базовый URL + credential + текст → байты аудио или причина
 * недоступности. Реализация НИКОГДА не бросает наружу: отказ сервера — это
 * [ServerTtsResult.Unavailable], то есть приглашение к фолбэку.
 */
fun interface ServerTtsTransport {
    suspend fun synthesize(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        request: ServerTtsSpeechRequest,
    ): ServerTtsResult
}

/** Источник байтов синтеза для движка озвучки. */
interface ServerTtsClient {
    /**
     * Синтезирует [text] на сервере.
     *
     * @return [ServerTtsResult.Audio] при готовом аудио, иначе
     *   [ServerTtsResult.Unavailable] — приглашение к фолбэку на системный TTS.
     */
    suspend fun synthesize(text: String): ServerTtsResult

    /**
     * Применить темп и голос к СЛЕДУЮЩИМ репликам.
     *
     * Зачем метод в ПОРТЕ, а не приведение типа у вызывающего. Раньше выбор голоса доезжал
     * до клиента только через `serverTts as? RepositoryServerTtsClient` в
     * [LocalVoiceTts] — то есть через знание конкретного класса. Любая другая сборка
     * (`ChatInitSection`, локальный чат) и любой фейк в тестах этого приведения не имеют,
     * и вызов молча пропадал: шторка показывала новый голос, а синтез продолжал идти
     * прежним. Метод в порте делает зависимость явной и типизированной.
     *
     * Тело по умолчанию НАРОЧНО: порт принимает только текст, и реализация без темпа
     * (фейки, офлайн-клиенты) обязана компилироваться без правок. Молчаливый `Unit`
     * честнее исключения — голос важнее темпа.
     */
    fun setProsody(prosody: VoiceProsody) = Unit
}

/** Проигрывание готового аудио. Реализация владеет аудиодвижком и временным файлом. */
interface ServerTtsPlayback {
    /**
     * Начинает воспроизведение [bytes].
     *
     * @param onFailed вызывается ровно один раз, если звук не пошёл: движок не смог
     *   стартовать асинхронно (битый/неподдерживаемый поток) — вызывающий обязан
     *   уйти в фолбэк на системный TTS.
     * @return `true` — воспроизведение ПРИНЯТО (синхронно стартовать удалось);
     *   `false` — движок не смог даже начать (нет кодека/диска/контекста).
     */
    fun play(bytes: ByteArray, contentType: String, onFailed: () -> Unit): Boolean

    /**
     * Играет [bytes] и сообщает о ЕСТЕСТВЕННОМ конце через [onFinished].
     *
     * Нужно очереди фраз ([SpeechQueue]): только по этому сигналу она запускает следующую
     * фразу, поэтому порядок держится на факте «доиграло», а не на таймере. Базовая
     * реализация завершения не сообщает и возвращает `false` — плеер без такого сигнала
     * для очереди не годится, и она честно пропустит фразу, а не зависнет навсегда.
     *
     * @return `true` — воспроизведение принято; `false` — движок не смог начать.
     */
    fun playToEnd(
        bytes: ByteArray,
        contentType: String,
        onFinished: () -> Unit,
        onFailed: () -> Unit,
    ): Boolean = false

    /** Немедленная тишина. Безопасно при простое. */
    fun stop()

    /** Освободить движок и временные файлы (хост уничтожен). */
    fun release()
}

/**
 * Голосовой вывод с серверным синтезом и ПРОЗРАЧНЫМ фолбэком на РЕЗЕРВНЫЙ движок.
 *
 * Реализует контракт [VoiceOutputController.SpeechEngine], оборачивая резервный движок
 * ([fallback] — обычно локальную модель Piper, см. [LocalVoiceTts]), поэтому
 * [VoiceOutputController] и весь существующий голосовой путь не меняются: контроллер видит
 * один движок и по-прежнему получает только «принято / не принято».
 *
 * Резерв — это политика вызывающего, а не догадка движка: раньше здесь стоял платформенный
 * `TextToSpeech`, и любой отказ облака (401, 429, таймаут, битый поток) молча подменял
 * выбранный «нейро»-голос роботом Android, хотя живая модель лежала на устройстве. Теперь
 * цель деградации задаётся снаружи, а её канал сообщается хосту ([fallbackChannel] →
 * [onVoiceSubstituted]), поэтому подмена всегда видна.
 *
 * Порядок решения для одной реплики:
 * 1. текст длиннее [ServerTtsContract.MAX_TEXT_LENGTH] — на сервер НЕ отправляем
 *    (иначе гарантированный `400 text too long`), сразу резерв;
 * 2. иначе запускается серверный синтез; [speak] возвращает `true` немедленно
 *    (fire-and-forget, как у платформенного движка) и не блокирует UI-поток;
 * 3. пришло аудио и оно стартовало — говорим им; резерв не задействован;
 * 4. любой другой исход (401/404/429/5xx, невалидный ответ, транспортный сбой,
 *    не запустившееся воспроизведение, асинхронная ошибка декодера) — ТОТ ЖЕ текст
 *    уходит в [fallback], и о подмене канала сообщается хосту. Пользователю не показывается
 *    ни ошибки, ни тоста, наружу ничего не бросается.
 *
 * Серверный маршрут открыт: `/api/tts` входит в `NATIVE_BEARER_EXACT_PATHS`
 * (`api/auth.py`), device-Bearer получает 200 и MP3 — ветка 4 ниже остаётся
 * честной деградацией при реальном отказе сервера, а не постоянным путём
 * исполнения (прежнее описание «всегда 401 из-за cookie-проверки» устарело).
 *
 * Реплики не накладываются: каждый новый [speak] отменяет предыдущую незавершённую
 * попытку (семантика `QUEUE_FLUSH` платформенного движка).
 *
 * Предсинтез ([prefetch]): следующая фраза синтезируется, пока звучит текущая, поэтому
 * [speak] для неё не ждёт сеть. Без этого TTFB синтеза (0.4–2 с) складывался с концом
 * предыдущей фразы в паузу посреди ответа — «дошёл до запятой и остановился на 5–10 с».
 *
 * @param fallback резервный движок — цель деградации при отказе облака. В голосовом режиме
 *   это локальная модель Piper; платформенный TTS остаётся им только тогда, когда модели на
 *   устройстве нет вовсе.
 * @param client источник серверного аудио.
 * @param playback проигрывание серверного аудио.
 * @param scope область для фонового синтеза (в приложении — `lifecycleScope`).
 * @param onServerAudioAvailable вызывается один раз при первом реально зазвучавшем
 *   серверном аудио: хост может вернуть вывод в строй, если платформенный TTS на
 *   устройстве отсутствовал (серверный канал от него не зависит).
 * @param fallbackChannel каким каналом является [fallback] — это знает только вызывающий,
 *   движок видит интерфейс. Нужно, чтобы сообщить хосту, ЧЕМ именно заменён выбранный голос.
 * @param onVoiceSubstituted реплику произнёс резерв вместо облачного голоса. Подмена —
 *   разрешённое поведение, но молчаливая подмена запрещена: хост показывает её человеку.
 * @param onSilent ни облако, ни резерв не дали звука. Событие обязательно: [speak] отвечает
 *   `true` оптимистично (синтез асинхронный), и без этого сигнала хост остался бы в фазе
 *   «говорю» навсегда — с закрытым микрофоном и висящей кнопкой.
 */
class ServerTtsSpeechEngine(
    private val fallback: VoiceOutputController.SpeechEngine,
    private val client: ServerTtsClient,
    private val playback: ServerTtsPlayback,
    private val scope: CoroutineScope,
    private val onServerAudioAvailable: () -> Unit = {},
    private val fallbackChannel: VoiceChannel = VoiceChannel.SYSTEM,
    /**
     * Последний резерв ПОСЛЕ [fallback] — платформенный `TextToSpeech`.
     *
     * Зачем отдельный параметр. В серверном чате [fallback] — локальная модель Piper, но
     * она физически не может звучать, пока модель не скачана (первый запуск, 21 МиБ).
     * Без этого резерва такая фраза пропадала бы молча: облако отказало, Piper не готов, и
     * человек не слышал ничего. Теперь порядок честный и полный:
     * облако → Piper → платформа, и каждый шаг объявляется своим каналом.
     *
     * `null` (значение по умолчанию) — прежнее поведение: два канала, второй отказ означает
     * тишину (так живут тесты и сценарии без офлайн-модели).
     */
    private val lastResort: VoiceOutputController.SpeechEngine? = null,
    private val onVoiceSubstituted: (VoiceSubstitution) -> Unit = {},
    private val onSilent: () -> Unit = {},
) : VoiceOutputController.SpeechEngine {

    /** Незавершённая серверная попытка; отменяется новым [speak]/[stop]/[shutdown]. */
    private var attempt: Job? = null

    /**
     * Поколение реплики: [stop]/[shutdown]/новый [speak] его поднимают.
     *
     * Зачем отдельно от [attempt]: корутина попытки может уже выйти из ожидания (аудио
     * получено, `play` принял байты) и продолжить выполнение ПОСЛЕ того, как человек нажал
     * микрофон. Без счётчика отменённая реплика «догоняла» бы его резервным голосом — уже
     * после тишины и уже в новом ходе.
     */
    @Volatile
    private var generation: Long = 0L

    /**
     * Предсинтез следующих фраз: текст → уже запущенный синтез.
     *
     * Зачем: синтез занимает 0.4–2 с (сеть, сервер), а очередь отдаёт следующую фразу
     * ТОЛЬКО после того, как доиграла текущая. Без предсинтеза эти секунды складывались в
     * паузу посреди ответа — ту самую «дошёл до запятой и остановился на 5–10 секунд».
     * С предсинтезом фраза N+1 синтезируется, пока звучит фраза N, и [speak] забирает
     * готовое аудио без ожидания сети.
     *
     * Ключ — ГОЛОС + текст ([ServerTtsPrefetchKeys]). Пока ключом был один текст, кэш не
     * знал, каким голосом синтезирована фраза: смена голоса посреди ответа оставляла
     * остаток реплики прежним голосом, хотя шторка уже показывала новый выбор. Текст в
     * ключе сохраняет прежнюю пользу (очередь отдаёт куски дословно, и повтор того же
     * текста получает готовый результат), голос — запрещает отдавать чужое аудио.
     */
    private val prefetched = ConcurrentHashMap<String, Deferred<ServerTtsResult>>()

    /**
     * Последний присланный хостом темп и голос.
     *
     * Хранится, а не только переливается в [fallback], потому что по нему решаются ДВЕ вещи,
     * которые движок больше никак не узнает:
     * - какой голос указать в ключе предсинтеза ([prefetchKey]);
     * - что выбор изменился и кэш прежнего голоса больше нельзя отдавать человеку.
     *
     * Раньше значения не было вовсе: [setProsody] отдавал его резерву и забывал, поэтому
     * клиент синтеза узнавал о голосе только через приведение типа в [LocalVoiceTts].
     *
     * `@Volatile`: ставится с главного потока из шторки, читается из корутины синтеза.
     */
    @Volatile
    private var prosody: VoiceProsody? = null

    /** Системный TTS уже отказал: повторять его вслепую нельзя. */
    private var fallbackDead = false

    /** Последняя серверная попытка не дала звука: серверный канал пока не работает. */
    private var lastServerUnavailable = true

    /** Серверное аудио хоть раз зазвучало (для [onServerAudioAvailable] — один раз). */
    private var serverAnnounced = false

    /** Сколько раз уходили на сервер — диагностика и тесты, решений не принимает. */
    var serverAttemptCount: Int = 0
        private set

    /** Сколько фраз ждёт предсинтеза — диагностика и тесты, решений не принимает. */
    val prefetchedCount: Int
        get() = prefetched.size

    /**
     * Текущий темп и голос, последние присланные хостом; `null` — хост ещё не присылал.
     *
     * Нужен хосту для диагностики («каким голосом движок считает, что говорит») и тестам:
     * без него проверять сохранение настройки пришлось бы по звуку.
     */
    val currentProsody: VoiceProsody?
        get() = prosody

    /**
     * Ключ предсинтеза для [text] ТЕКУЩИМ голосом.
     *
     * Читает [prosody] один раз на вызов: если голос сменится между prefetch и speak, ключи
     * разойдутся, и фраза будет синтезирована заново — это и требуется.
     */
    private fun prefetchKey(text: String): String =
        ServerTtsPrefetchKeys.of(prosody?.voiceId, text)

    /**
     * Предсинтезировать [text], пока звучит текущая фраза.
     *
     * Вызывается очередью ([LocalVoiceTts]) сразу после запуска предыдущего куска, поэтому
     * к моменту [speak] результат обычно уже готов. Отменяется в [stop]/[shutdown]: тишина
     * не должна оплачиваться сетевым запросом, который больше никому не нужен.
     *
     * Ничего не делает для пустого/слишком длинного текста (сервер ответил бы 400) и когда
     * оба канала уже мертвы: предсинтез мёртвого канала — это просто лишний запрос.
     */
    override fun prefetch(text: String) {
        if (text.isBlank()) return
        if (text.length > ServerTtsContract.MAX_TEXT_LENGTH) return
        if (fallbackDead && lastServerUnavailable) return
        val key = prefetchKey(text)
        if (prefetched.containsKey(key)) return
        // Потолок очереди: три фразы вперёд — это уже весь буфер SpeechChunker, а больше
        // означало бы синтез в пустоту (человек перебьёт ответ раньше, чем он зазвучит).
        if (prefetched.size >= MAX_PREFETCH) return
        val pending = scope.async {
            try {
                client.synthesize(text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Порт обязан не бросать; страховка — тот же фолбэк вместо падения.
                ServerTtsResult.Unavailable("transport")
            }
        }
        // Гонка двух вызовов на одну фразу: проигравший отменяется, иначе лишний сетевой
        // запрос жил бы до конца реплики и мог перезаписать уже лежащий результат.
        if (prefetched.putIfAbsent(key, pending) != null) pending.cancel()
    }

    /**
     * Озвучивает [text]: сервером, а при любом сбое — резервным движком ([fallback]).
     *
     * @return `true`, если реплика принята (серверная попытка запущена либо её принял
     *   резервный движок); `false` — звука не будет НИ одним каналом (оба уже отказали),
     *   тогда контроллер честно выключает вывод и показывает «озвучивание недоступно».
     */
    override fun speak(text: String): Boolean {
        // Правило длины: >5000 символов сервер отклоняет с 400 — не создаём этот 400 сами.
        if (text.length > ServerTtsContract.MAX_TEXT_LENGTH) {
            prefetched.remove(prefetchKey(text))?.cancel()
            return speakByFallback(text)
        }
        // Все каналы уже мертвы — не притворяемся, что реплика принята. Пока жив последний
        // резерв (платформа), фраза ещё может прозвучать, поэтому проверка его учитывает.
        if (fallbackDead && lastServerUnavailable && lastResort == null) return false
        attempt?.cancel()
        serverAttemptCount++
        // Новая реплика — новое поколение: поздние колбэки прошлой (асинхронный отказ декодера,
        // запоздавший ответ сети) больше не имеют права звучать. Это семантика `QUEUE_FLUSH`,
        // которую обещает контракт движка.
        generation++
        // Поколение снимается ДО запуска: поздний ответ этой попытки не должен звучать после
        // стопа, который случится, пока она в сети.
        val token = generation
        // Предсинтезированная фраза забирается ДО запуска новой попытки: ожидания синтеза
        // между фразами нет вовсе, звук стартует сразу после предыдущего.
        val ready = prefetched.remove(prefetchKey(text))
        attempt = scope.launch {
            val result = if (ready != null) {
                try {
                    ready.await()
                } catch (cancelled: CancellationException) {
                    // Отменённый предсинтез — это не отмена реплики: если нас самих не
                    // отменили, честно уходим в фолбэк, а не теряем фразу молча.
                    coroutineContext.ensureActive()
                    ServerTtsResult.Unavailable("transport")
                } catch (error: Exception) {
                    ServerTtsResult.Unavailable("transport")
                }
            } else {
                try {
                    client.synthesize(text)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // Порты обязаны не бросать; страховка — тот же фолбэк вместо падения.
                    ServerTtsResult.Unavailable("transport")
                }
            }
            // Реплику отменили, пока шёл синтез: ни играть, ни отдавать резерву нельзя.
            if (token != generation) return@launch
            when (result) {
                is ServerTtsResult.Audio -> {
                    val started = playback.play(result.bytes, result.contentType) {
                        // Асинхронный отказ декодера приходит уже ПОСЛЕ возврата из play():
                        // если за это время прошёл стоп, реплика отменена и звучать не должна.
                        if (token == generation && !speakByFallback(text)) notifySilent()
                    }
                    when {
                        started -> markServerWorking()
                        token == generation && !speakByFallback(text) -> notifySilent()
                    }
                }
                is ServerTtsResult.Unavailable -> {
                    lastServerUnavailable = true
                    if (token == generation && !speakByFallback(text)) notifySilent()
                }
            }
        }
        return true
    }

    /**
     * Темп и высота: сервер получает их в теле запроса, а резервный движок — вызовом.
     *
     * Две разные дороги, и обе нужны: у `api/tts` темп едет полем запроса (его подставляет
     * [RepositoryServerTtsClient]), а платформенный TTS меняет темп только через
     * `setSpeechRate`. Если пробросить вызов лишь в одну сторону, человек услышит свой
     * темп ровно до первого отказа сервера, а дальше — прежний.
     *
     * Третья дорога — [client] (порт синтеза). Раньше её не было: значение уходило в
     * [fallback] и забывалось, а клиент узнавал о голосе только через приведение типа в
     * [LocalVoiceTts]. Любая сборка без этого приведения (серверный чат, локальный чат,
     * тесты) теряла выбор человека — шторка применяла его визуально, а звучал прежний голос.
     *
     * Смена значения отменяет предсинтез: фраза, синтезированная ПРЕЖНИМ голосом, не имеет
     * права зазвучать после того, как человек выбрал новый. Текущая реплика при этом не
     * обрывается — её запрос уже ушёл, и рвать звук ради настройки значило бы наказать
     * человека за неё.
     */
    override fun setProsody(prosody: VoiceProsody) {
        val changed = this.prosody != prosody
        this.prosody = prosody
        if (changed) cancelPrefetched()
        // Клиент синтеза — явная типизированная зависимость порта ([ServerTtsClient]),
        // а не приведение к [RepositoryServerTtsClient] на стороне вызывающего.
        runCatching { client.setProsody(prosody) }
        runCatching { fallback.setProsody(prosody) }
    }

    /** Тишина: отменяет серверную попытку и предсинтез, глушит ВСЕ каналы. */
    override fun stop() {
        generation++
        attempt?.cancel()
        attempt = null
        cancelPrefetched()
        runCatching { playback.stop() }
        runCatching { fallback.stop() }
        runCatching { lastResort?.stop() }
    }

    /** Хост уничтожен: освобождаем все движки. */
    override fun shutdown() {
        generation++
        attempt?.cancel()
        attempt = null
        cancelPrefetched()
        runCatching { playback.release() }
        runCatching { fallback.shutdown() }
        runCatching { lastResort?.shutdown() }
    }

    /**
     * Отменить весь предсинтез и очистить карту.
     *
     * Отмена обязательна: незавершённый синтез держал бы сокет и (на платном голосе) деньги
     * за запрос, который уже никто не услышит. Очистка — чтобы следующий [speak] не забрал
     * результат, отменённый прошлой тишиной.
     */
    private fun cancelPrefetched() {
        prefetched.values.forEach { it.cancel() }
        prefetched.clear()
    }

    /**
     * Отдать реплику резерву — и сказать об этом хосту.
     *
     * Резерв разрешён, молчаливая подмена — нет. Хост получает [onVoiceSubstituted] с каналом
     * [fallbackChannel], поэтому «услышал робота вместо Светланы» перестаёт быть загадкой:
     * это либо локальная модель (нормальный резерв), либо платформа (крайний случай, о котором
     * нужно сообщить).
     *
     * `runCatching` обязателен: резерв — чужой код (нативный синтез, платформа), и его
     * исключение не должно всплывать из корутины синтеза.
     */
    private fun speakByFallback(text: String): Boolean {
        val accepted = runCatching { fallback.speak(text) }.getOrDefault(false)
        if (!accepted) {
            // «Резерв мёртв» — это про канал БЕЗ последнего резерва. Когда последний резерв
            // есть, отказ Piper чаще всего означает «модель ещё скачивается»: пометить его
            // мёртвым навсегда значило бы навсегда лишить озвучку локального голоса после
            // первой же фразы на новом устройстве.
            fallbackDead = lastResort == null
            // Последний шанс: платформа. Молчания не будет, и подмена объявляется честно.
            val platform = lastResort ?: return false
            val platformAccepted = runCatching { platform.speak(text) }.getOrDefault(false)
            if (!platformAccepted) return false
            runCatching {
                onVoiceSubstituted(
                    VoiceSubstitution(VoiceChannel.SYSTEM, VoiceSubstitutionReason.SYSTEM_RESERVE),
                )
            }
            return true
        }
        val reason = if (fallbackChannel == VoiceChannel.SYSTEM) {
            VoiceSubstitutionReason.SYSTEM_RESERVE
        } else {
            VoiceSubstitutionReason.CLOUD_FAILED
        }
        runCatching { onVoiceSubstituted(VoiceSubstitution(fallbackChannel, reason)) }
        return true
    }

    /** Звука не будет ни одним каналом: очередь обязана закрыть фазу, а не ждать колбэка. */
    private fun notifySilent() {
        runCatching { onSilent() }
    }

    /** Серверное аудио зазвучало: канал рабочий, при первом успехе сообщаем хосту. */
    private fun markServerWorking() {
        lastServerUnavailable = false
        if (!serverAnnounced) {
            serverAnnounced = true
            runCatching { onServerAudioAvailable() }
        }
    }

    companion object {
        /**
         * Сколько фраз можно предсинтезировать вперёд.
         *
         * Три — это ровно буфер `SpeechChunker` (текущая фраза + два куска очереди).
         * Больше означало бы синтез в пустоту: человек перебьёт ответ или уйдёт с экрана
         * раньше, чем эти фразы зазвучат, а запросы (и деньги на платном голосе) уже ушли.
         */
        const val MAX_PREFETCH: Int = 3
    }
}

/**
 * Мост от [ServerTtsSpeechEngine] к сетевому слою: [ServerTtsRepository] + базовый URL +
 * провайдер device-credential.
 *
 * Отсутствие credential — это [ServerTtsResult.Unavailable] (`unauthorized`), а не запрос
 * без авторизации и не исключение: движок просто уйдёт в фолбэк (тот же принцип, что у
 * `RepositoryContextCompressionGateway`).
 *
 * Голос выбирается ЯВНО ([selector]): иначе сервер подставляет свой дефолт и приложение
 * говорит не тем голосом, что в вебе. Первый кандидат — естественный Svetlana, локальный
 * piper — только резерв. Понижение профиля идёт по фактическому ответу `api/tts`, а
 * `429`/`401` профиль НЕ понижают (это лимитер и токен, а не отсутствие голоса).
 *
 * Порядок одной реплики:
 * 0. если человек выбрал платный облачный голос ([ServerTtsVoices.PLUSVIBE_ALICE] или
 *    [ServerTtsVoices.PLUSVIBE_ONYX]) — асинхронный контракт провайдера
 *    ([PlusVibeMediaTtsClient]: `media/generate` + поллинг задачи), а при любом отказе
 *    (нет ключа, потолок цены, таймаут, ошибка задачи) — прямой нейро-голос Дмитрия.
 *    «Оникс» и «Алиса» не падают в механический системный TTS и не ждут зависший апстрим
 *    дольше отведённого контрактом времени;
 * 1. прямой WebSocket ([EdgeNeuralTtsClient]) выбранным голосом — ДАЖЕ без credential:
 *    Microsoft не спрашивает наш токен, и именно этот путь снимает 2-секундный лимитер
 *    сервера, из-за которого «Светлана», «Дмитрий» и «как в вебе» звучали одним системным
 *    голосом Android (жалоба владельца «дмитрий, светлана — один и тот же голос»);
 * 2. если сокет не дал аудио (провайдер блокирует Bing, DRM-отказ, таймаут) — серверный
 *    `/api/tts` тем же голосом, с ОДНИМ повтором после `429`;
 * 3. дальше — цепочка профилей (Светлана → Дмитрий → Ава) по фактическому ответу сервера.
 *    Платные профили в автоматическом переборе не участвуют: они стоят денег и выбираются
 *    человеком осознанно (шаг 0), а не «на всякий случай».
 *
 * Одна реплика перебирает ЦЕПОЧКУ профилей, а не один профиль. Раньше здесь стоял
 * `selector.current()` и один запрос: любой отказ по голосу (`503` «Edge TTS engine not
 * installed», `400` invalid voice, таймаут) выбрасывал фразу на системный Android TTS,
 * хотя локальный piper объявлен именно резервом для этих случаев. Порядок
 * [ServerTtsVoices.VOICE_DIALOG] был объявлен, но не исполнялся.
 *
 * Голос, выбранный человеком в шторке (`voiceId`), идёт ПЕРВЫМ в этой цепочке. Без этого
 * перебор всегда начинался с дефолтного порядка, и выбор «Дмитрий» или «Ирина» не влиял
 * на звук: приложение говорило Светланой что бы человек ни выбрал.
 *
 * Отказ, который говорит о ГОЛОСЕ (5xx, 4xx кроме 429/401, таймаут, битый ответ),
 * понижает профиль и передаёт текст следующему. Отказ, который говорит о КЛИЕНТЕ или
 * СЕРВЕРЕ в целом (`429` лимитер, `401` токен), цепочку не перебирает: другой голос не
 * поможет, а лишний запрос только добьёт лимитер.
 *
 * @param selector выбор профиля голоса; `null` — «пусть сервер решит сам» (старое поведение,
 *   нужное только тестам контракта).
 * @param transport точка подмены для тестов; по умолчанию — боевой HTTP-репозиторий.
 */
class RepositoryServerTtsClient(
    private val baseUrl: String,
    private val credentialProvider: () -> ConsumedDeviceCredential?,
    private val selector: SpeechVoiceProfileSelector? = SpeechVoiceProfileSelector(),
    /**
     * Источник темпа и голоса для КАЖДОЙ реплики.
     *
     * Лямбда, а не значение: человек меняет темп в шторке во время диалога, и клиент
     * обязан видеть новое значение на следующей фразе. Передавать сюда снимок значило
     * бы применять темп только после пересоздания клиента — то есть никогда.
     *
     * `null` — клиент создан без источника (тесты контракта, старая проводка): работает
     * ровно как раньше, темп берётся из профиля голоса. Это не дыра, а обратная
     * совместимость: темп появился позже самого клиента.
     */
    private val prosodyProvider: (() -> VoiceProsody)? = null,
    /**
     * Темп и высота, выбранные человеком. `null` — «как в вебе»: `rate`/`pitch` не
     * отправляем вовсе, сервер берёт свои дефолты.
     *
     * Живёт здесь, а НЕ в [ServerTtsVoiceProfile], потому что темп — не свойство голоса:
     * Svetlana с ускорением 1.15× — тот же голос, а не второй профиль. Положив темп в
     * профиль, мы получили бы по профилю на каждую ступень темпа и сломали бы политику
     * понижения — она знает только «этот голос работает / не работает».
     *
     * `@Volatile`: ставится с главного потока из шторки, читается из корутины синтеза.
     */
    @Volatile
    private var prosody: VoiceProsody? = null,
    /**
     * Прямой нейросетевой синтез Edge (WebSocket, без сервера Марса).
     *
     * Почему он ПЕРВЫЙ, а не запасной: серверный `/api/tts` держит лимитер 2 с на клиента
     * и буферизует ответ целиком, из-за чего вторая фраза подряд получала `429` и уходила
     * в системный Android TTS — все «нейро»-голоса звучали одним роботом. Прямой сокет
     * лимитера не имеет и начинает звук за ~0.4 с. Токен устройства при этом НЕ нужен:
     * Microsoft не требует нашей авторизации, поэтому голос работает даже с просроченным
     * credential.
     *
     * `null` — прямой путь отключён (тесты контракта и сценарии без сети): тогда работает
     * только серверная цепочка.
     */
    private val directEdgeClient: EdgeNeuralTtsClient? = EdgeNeuralTtsClient(),
    /**
     * Платный облачный синтез (асинхронный контракт провайдера).
     *
     * `null` (значение по умолчанию) — платный голос выключен: профили
     * [ServerTtsVoices.PLUSVIBE_ALICE] и [ServerTtsVoices.PLUSVIBE_ONYX] в переборе
     * пропускаются, а при их явном выборе фраза уходит прямому нейро-голосу. Это не дыра,
     * а обратная совместимость: ключ провайдера приезжает из настроек позже, и все
     * существующие вызовы конструктора остаются валидными без правок.
     *
     * Тип — порт [PaidTtsClient], а не конкретный класс: боевой маршрут
     * ([PlusVibeMediaTtsClient]) асинхронный, а синхронный [OpenAiSpeechTtsClient] оставлен
     * эталоном контракта и подменой в тестах.
     */
    private val paidClient: PaidTtsClient? = null,
    private val transport: ServerTtsTransport = ServerTtsTransport { url, credential, request ->
        ServerTtsRepository().synthesize(url, credential, request)
    },
    /**
     * Выбранный ПЛАТНЫЙ голос не прозвучал, и фразу прочитал нейро-резерв.
     *
     * Зачем отдельный сигнал: в этой ветке отказ платного клиента не доходит до движка —
     * вызывающему возвращается уже готовое аудио другого голоса, и без этого колбэка
     * человек слышал бы Дмитрия, выбрав «Алису», без единого объяснения. Ровно тот же
     * принцип, что у [ServerTtsSpeechEngine.onVoiceSubstituted]: резерв разрешён, молчаливая
     * подмена — нет.
     *
     * @param reason причина отказа клиента (`no_plusvibe_profile`, `no_api_key`, `timeout`,
     *   `transport`, …) — короткий код без ключа и текста реплики.
     */
    private val onPaidUnavailable: (String?) -> Unit = {},
    /**
     * Ожидание перед повтором после `429`.
     *
     * Подменяется в тестах: настоящие 0.5–2.5 с сделали бы проверку ретрая медленной, а
     * логика («подождать и повторить РОВНО один раз») от длительности не зависит.
     */
    private val sleeper: suspend (Long) -> Unit = { millis -> delay(millis) },
    /**
     * Сколько ждать ОДНУ реплику выбранным платным голосом до нейро-фолбэка, мс.
     *
     * Значение берётся у профиля ([ServerTtsVoiceProfile.waitBudgetMs]): у именованных
     * голосов (`Alice`, `onyx`) бюджета нет — их потолок остаётся транспортным (90 с), потому
     * что апстрим под нагрузкой честно отвечает за 13 с, и обрывать живой запрос значило бы
     * платить дважды. А у голоса из библиотеки (`voice_id`) дефект другой: живой замер
     * 18.11.2026 — 5 из 5 задач не завершились вовсе, поэтому ждать 90 с бессмысленно.
     *
     * Лямбда, а не значение: решение «сколько ждать» принадлежит профилю, а подмена в тестах
     * не должна ждать реальные 8 с.
     */
    private val paidWaitBudget: (ServerTtsVoiceProfile) -> Long? = { it.waitBudgetMs },

    /**
     * Выбранный человеком голос не прозвучал — прозвучал ДРУГОЙ профиль этой же реплики.
     *
     * Это профильный уровень той же правды, что [ServerTtsSpeechEngine.onVoiceSubstituted]
     * сообщает на уровне канала. Нужен потому, что внутри одной реплики цепочка перебирает
     * профили САМА: выбранный «Дмитрий» получает `400 invalid voice`, и фразу читает
     * «Светлана». Аудио при этом приходит нормальное, движок видит успех и молчит — то есть
     * человек слышит не тот голос и не узнаёт об этом. Здесь этот факт не теряется.
     *
     * @param wanted id профиля, который человек выбрал (`null` — он ничего не выбирал,
     *   и тогда подмены нет вовсе: колбэк не вызывается).
     * @param actual id профиля, который реально синтезировал реплику.
     * @param reason код отказа выбранного профиля (`invalid_voice`, `server_error`,
     *   `transport`, …) либо `null`, если отказ случился не в этой реплике. Ни текста
     *   реплики, ни ключей здесь нет.
     */
    private val onVoiceSubstituted: (wanted: String?, actual: String, reason: String?) -> Unit =
        { _, _, _ -> },
) : ServerTtsClient {

    /** Исход попытки одним профилем — только метаданные, без текста реплики. */
    data class Attempt(val profileId: String?, val reason: String?, val httpCode: Int?)

    /** Последний профиль, которым реально синтезировали (диагностика, без текста). */
    var lastProfileId: String? = null
        private set

    /** Что произошло с каждым профилем в последней реплике (диагностика, без текста). */
    @Volatile
    var lastAttempts: List<Attempt> = emptyList()
        private set

    override suspend fun synthesize(text: String): ServerTtsResult {
        val explicitChoice = chosenProfile()
        // Ничего не выбрано — говорим ОСНОВНЫМ голосом приложения (PlusVibe), а не первым
        // бесплатным: это и есть «основной голос» из политики [ServerTtsVoices.DEFAULT_VOICE].
        val chosen = explicitChoice ?: ServerTtsVoices.DEFAULT_VOICE
        val attempts = ArrayList<Attempt>(ServerTtsVoices.VOICE_DIALOG.size + 2)

        // 0) Платный облачный голос (engine = "plusvibe"/"openai"): асинхронный контракт
        //    провайдера. Он НЕ ходит в серверный `/api/tts`: серверный движок не знает ни
        //    модели ElevenLabs, ни голоса `onyx`, поэтому запрос туда гарантировал бы 400 и
        //    лишнюю паузу в речи.
        if (isPaidEngine(chosen.engine)) {
            val paid = paidAttempt(text, chosen)
            if (paid is ServerTtsResult.Audio) {
                attempts += Attempt(chosen.id, null, null)
                lastProfileId = chosen.id
                lastAttempts = attempts
                selector?.onAudio(chosen)
                return paid
            }
            val paidFailure = paid as? ServerTtsResult.Unavailable
            paidFailure?.let { failure ->
                attempts += Attempt(chosen.id, failure.reason, failure.httpCode)
                selector?.onUnavailable(chosen, failure)
            }
            // Подмена объявляется СРАЗУ после отказа, а не в ветке успеха нейро-резерва.
            // Раньше колбэк стоял только там, и любой другой исход (Дмитрий тоже не ответил,
            // фразу прочитала «Светлана» серверной цепочки) уходил человеку МОЛЧА: он выбрал
            // «Алису», услышал чужой голос и не получил ни одного объяснения. Платный
            // профиль в автоматическом переборе не участвует (см. цикл ниже), поэтому
            // вернуться к нему эта реплика уже не может — событие окончательное.
            notifyPaidUnavailable(paidFailure?.reason)
            // Прозрачный нейро-фолбэк: платный голос не должен звучать механическим
            // системным роботом только потому, что у провайдера нет ключа или задача
            // не уложилась в потолок ожидания.
            val neural = directAttempt(text, ServerTtsVoices.NATURAL_DMITRY)
            if (neural is ServerTtsResult.Audio) {
                attempts += Attempt(ServerTtsVoices.NATURAL_DMITRY.id, null, null)
                lastProfileId = ServerTtsVoices.NATURAL_DMITRY.id
                lastAttempts = attempts
                selector?.onAudio(ServerTtsVoices.NATURAL_DMITRY)
                return neural
            }
            (neural as? ServerTtsResult.Unavailable)?.let { failure ->
                attempts += Attempt(ServerTtsVoices.NATURAL_DMITRY.id, failure.reason, failure.httpCode)
            }
            // Дальше — обычный серверный маршрут: он тем же текстом попробует остальные
            // профили (платный профиль в переборе пропускается, см. цикл ниже).
        }

        // 1) Прямой WebSocket к Microsoft: без credential и без серверного лимитера.
        val direct = directAttempt(text, chosen)
        if (direct is ServerTtsResult.Audio) {
            attempts += Attempt(chosen.id, null, null)
            lastProfileId = chosen.id
            lastAttempts = attempts
            selector?.onAudio(chosen)
            return direct
        }
        // Отказ прямого пути НЕ понижает профиль: недоступность Bing — свойство сети, а не
        // голоса, и серверный маршрут тем же голосом ещё может ответить.
        (direct as? ServerTtsResult.Unavailable)?.let {
            attempts += Attempt(chosen.id, it.reason, it.httpCode)
        }

        val credential = credentialProvider()
        val selector = this.selector
        if (credential == null) {
            // Прямой путь не дал звука, а сервер без токена не отвечает: это приглашение
            // к системному TTS, а не запрос без авторизации.
            lastProfileId = if (direct == null) null else chosen.id
            lastAttempts = attempts
            return ServerTtsResult.Unavailable(REASON_UNAUTHORIZED)
        }
        // Старое поведение «пусть сервер выберет сам» — только для контрактных тестов.
        if (selector == null) {
            lastProfileId = if (direct == null) null else chosen.id
            lastAttempts = attempts
            return request(text, credential, null)
        }
        val candidates = chain(selector.candidates(), explicitChoice)
        if (candidates.isEmpty()) {
            // Все профили на паузе. Запрос БЕЗ профиля здесь запрещён: сервер подставит
            // свой дефолт (piper/dmitri) и приложение заговорит чужим голосом молча.
            // Честнее явный отказ — вызывающий слой уйдёт на локальную модель.
            lastProfileId = if (direct == null) null else chosen.id
            lastAttempts = attempts
            return ServerTtsResult.Unavailable(PROFILES_COOLING)
        }
        var last: ServerTtsResult.Unavailable? = null
        // Почему не зазвучал ИМЕННО выбранный профиль: без этой причины событие подмены
        // сообщало бы «прозвучал другой голос» без единого намёка, что чинить.
        var chosenFailure: ServerTtsResult.Unavailable? = null
        for (profile in candidates) {
            // Платный облачный профиль пробуется ТОЛЬКО выбранным человеком (шаг 0): в
            // автоматическом переборе он стоил бы денег за каждую фразу и не является
            // резервом «на случай отсутствия сети» — для этого есть edge и piper.
            if (isPaidEngine(profile.engine)) continue
            lastProfileId = profile.id
            when (val result = requestWithRateLimitRetry(text, credential, profile)) {
                is ServerTtsResult.Audio -> {
                    attempts += Attempt(profile.id, null, null)
                    lastAttempts = attempts
                    selector.onAudio(profile)
                    // Выбранный голос не прозвучал, зазвучал этот. Внутри одной реплики
                    // цепочка перебирает профили молча, и без этого уведомления человек,
                    // выбравший «Дмитрия», слышит «Светлану» без объяснения — та самая
                    // «визуально применилось, а по факту нет».
                    if (explicitChoice != null && explicitChoice.id != profile.id) {
                        notifyVoiceSubstituted(explicitChoice.id, profile.id, chosenFailure?.reason)
                    }
                    return result
                }
                is ServerTtsResult.Unavailable -> {
                    attempts += Attempt(profile.id, result.reason, result.httpCode)
                    if (profile.id == explicitChoice?.id) chosenFailure = result
                    last = result
                    // false — отказ не про голос (лимитер/токен): следующий голос не поможет.
                    if (!selector.onUnavailable(profile, result)) {
                        lastAttempts = attempts
                        return result
                    }
                }
            }
        }
        lastAttempts = attempts
        // Цепочка исчерпана: явная ошибка с последней причиной, а не тишина. Движок
        // увидит Unavailable и отдаст фразу резерву — это видимая деградация, не молчание.
        // Повтор на уровне очереди это не отменяет: на реплику по-прежнему уходит не больше
        // запросов, чем профилей в политике (Светлана → Дмитрий → Ава → Ирина), и каждый
        // следующий идёт ДРУГИМ голосом — то есть с новым шансом на звук.
        return ServerTtsResult.Unavailable(
            reason = PROFILES_EXHAUSTED,
            httpCode = last?.httpCode,
            retryAfterMs = last?.retryAfterMs,
        )
    }

    /**
     * Платный облачный синтез выбранным голосом (маркер маршрута `plusvibe`/`openai`).
     *
     * Темп человека уезжает в клиент: у OpenAI-совместимого провайдера это множитель, а не
     * `+15%`, и `VoiceProsody.speed` уже ограничен `0.25..4.0`, поэтому в тело не попадёт
     * мусор. Media-контракт поля темпа не имеет — там его роль играет сама модель.
     *
     * Модель берётся из ПРОФИЛЯ ([ServerTtsVoiceProfile.model]): голос `Alice` существует
     * только внутри `elevenlabs-v4-tts`, а `onyx` — внутри `gpt-4o-mini-tts`. Пустая строка
     * означает «модель клиента по умолчанию».
     *
     * Голос из БИБЛИОТЕКИ провайдера ([ServerTtsVoiceProfile.voiceId], русские Лариса и Катя)
     * уезжает отдельным параметром: у ElevenLabs это другое поле контракта (`voice_id`), и
     * положить id в `voice` значило бы получить `422` или молчаливое «Rachel».
     *
     * Бюджет ожидания ([ServerTtsVoiceProfile.waitBudgetMs]) применяется ЗДЕСЬ, а не в
     * клиенте: истечение бюджета обязано привести к фолбэку, а фолбэк — политика этого класса.
     * Живой дефект `voice_id` — задача не завершается никогда; без бюджета реплика молчала бы
     * весь транспортный потолок (90 с). После бюджета вызывающий получает
     * [REASON_PAID_DEADLINE], и в [synthesize] сразу идёт нейро-резерв — то есть таймаут
     * не теряет фразу, а меняет голос.
     *
     * @return исход провайдера. Отсутствие клиента и исключение — тоже
     *   [ServerTtsResult.Unavailable]: вызывающий обязан уйти на нейро-фолбэк, а не упасть.
     */
    private suspend fun paidAttempt(
        text: String,
        profile: ServerTtsVoiceProfile,
    ): ServerTtsResult {
        val client = paidClient ?: return ServerTtsResult.Unavailable(REASON_NO_PAID_CLIENT)
        val effective = prosodyProvider?.invoke() ?: prosody
        val budget = paidWaitBudget(profile)?.takeIf { it > 0L }
        return try {
            val call: suspend () -> ServerTtsResult = {
                client.synthesize(
                    text = text,
                    voice = profile.voice,
                    model = profile.model.orEmpty(),
                    speed = effective?.speed ?: OpenAiSpeechTtsClient.DEFAULT_SPEED,
                    voiceId = profile.voiceId,
                )
            }
            // `withTimeoutOrNull`, а не `withTimeout`: отмена по бюджету — это НЕ отмена
            // реплики, и она обязана вернуть причину, по которой вызывающий уйдёт в резерв.
            // Отмена СНАРУЖИ (перебивание, стоп) по-прежнему бросает CancellationException.
            if (budget != null) {
                withTimeoutOrNull(budget) { call() }
                    ?: ServerTtsResult.Unavailable(REASON_PAID_DEADLINE)
            } else {
                call()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Порт обязан не бросать; страховка — тот же нейро-фолбэк.
            ServerTtsResult.Unavailable(OpenAiSpeechTtsClient.REASON_TRANSPORT)
        }
    }

    /**
     * Прямой синтез выбранным голосом через [EdgeNeuralTtsClient].
     *
     * @return `null` — прямой путь неприменим (клиента нет, профиль не edge-движка);
     *   иначе фактический исход, включая отказ: вызывающий обязан попробовать сервер.
     */
    private suspend fun directAttempt(
        text: String,
        profile: ServerTtsVoiceProfile,
    ): ServerTtsResult? {
        val client = directEdgeClient ?: return null
        if (profile.engine != ENGINE_EDGE) return null
        val effective = prosodyProvider?.invoke() ?: prosody
        val rate = effective?.serverRate() ?: profile.rate
        val pitch = effective?.serverPitch() ?: profile.pitch
        return try {
            client.synthesize(text, profile.voice, rate, pitch)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Порт обязан не бросать; страховка — тот же серверный фолбэк.
            ServerTtsResult.Unavailable(EdgeNeuralTtsClient.REASON_TRANSPORT)
        }
    }

    /**
     * Один запрос профилем с ОДНИМ повтором после `429`.
     *
     * Лимитер `/api/tts` — 2 секунды на клиента, а очередь фраз отправляет следующую
     * реплику, пока играет предыдущая. Без ожидания вторая фраза подряд гарантированно
     * получала `429` и уходила в системный Android TTS — так «нейро»-голоса и звучали
     * роботом. Повтор ровно один: два подряд ожидания уже превратили бы речь в паузу.
     */
    private suspend fun requestWithRateLimitRetry(
        text: String,
        credential: ConsumedDeviceCredential,
        profile: ServerTtsVoiceProfile,
    ): ServerTtsResult {
        val first = request(text, credential, profile)
        if (first !is ServerTtsResult.Unavailable || first.reason != REASON_RATE_LIMITED) return first
        val wait = (first.retryAfterMs ?: DEFAULT_RATE_LIMIT_WAIT_MS)
            .coerceIn(MIN_RATE_LIMIT_WAIT_MS, MAX_RATE_LIMIT_WAIT_MS)
        sleeper(wait)
        return request(text, credential, profile)
    }

    /**
     * Маршрут платного облака.
     *
     * Два маркера, потому что они появились в разное время: `openai` — исторический
     * (`plusvibe-onyx`, id профиля сохранён ради уже выбранного голоса), `plusvibe` —
     * новый основной (`plusvibe-alice`). Оба означают одно: реплику синтезирует
     * [PaidTtsClient], а не `/api/tts`.
     */
    private fun isPaidEngine(engine: String?): Boolean =
        engine == ENGINE_OPENAI || engine == ENGINE_PLUSVIBE

    /**
     * Профиль, выбранный человеком вручную, или `null` — «как в вебе».
     *
     * Источник — тот же, что у темпа: живой провайдер (шторка меняет выбор посреди
     * диалога), а при его отсутствии — последний [setProsody]. Неизвестный `voiceId`
     * (голос убрали из сборки, настройка приехала из старой версии) даёт `null`:
     * подставлять вместо него первый профиль значило бы врать о выборе.
     */
    private fun chosenProfile(): ServerTtsVoiceProfile? =
        (prosodyProvider?.invoke() ?: prosody)?.voiceId?.let { id -> ServerTtsVoices.byId(id) }

    /**
     * Порядок перебора профилей на одну реплику: выбранный человеком — ПЕРВЫМ.
     *
     * Без этого выбор в шторке не влиял ни на что: `candidates()` всегда отдавал
     * дефолтный порядок [ServerTtsVoices.VOICE_DIALOG], и «Дмитрий» звучал бы Светланой.
     * Выбранный профиль ставится перед остальными и удаляется из хвоста (без дублей),
     * причём ставится ДАЖЕ если селектор держит его на временной паузе: ручной выбор —
     * это новое решение человека, а не повтор прошлого отказа (пауза снимается в
     * [setProsody] через `reset()`).
     */
    private fun chain(
        available: List<ServerTtsVoiceProfile>,
        chosen: ServerTtsVoiceProfile?,
    ): List<ServerTtsVoiceProfile> {
        if (chosen == null) return available
        return listOf(chosen) + available.filter { it.id != chosen.id }
    }

    /** Один запрос одним профилем; исключение транспорта — это `Unavailable`, не падение. */
    private suspend fun request(
        text: String,
        credential: ConsumedDeviceCredential,
        profile: ServerTtsVoiceProfile?,
    ): ServerTtsResult = try {
        val base = ServerTtsSpeechRequest.of(text, profile)
        transport.synthesize(baseUrl, credential, withProsody(base, prosodyProvider?.invoke()))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // Репозиторий и сам не бросает; страховка — фолбэк вместо падения.
        ServerTtsResult.Unavailable("transport")
    }

    /**
     * Наложить выбранный темп на запрос.
     *
     * Темп человека ПЕРЕБИВАЕТ темп профиля: профиль несёт «эталонный» темп голоса
     * (`+10%` у Svetlana), а человек в шторке двигает его от обычного. Если бы темп
     * профиля оставался, ползунок 1.0× звучал бы как 1.1×, и шторка врала бы на первом
     * же шаге. Обратное тоже неверно (темп человека только при явном выборе) — поэтому
     * `null` в [VoiceProsody] не перебивает ничего.
     */
    private fun withProsody(
        request: ServerTtsSpeechRequest,
        chosen: VoiceProsody? = null,
    ): ServerTtsSpeechRequest {
        val effective = chosen ?: this.prosody ?: return request
        return request.copy(
            rate = effective.serverRate() ?: request.rate,
            pitch = effective.serverPitch() ?: request.pitch,
        )
    }

    /**
     * Выбранный человеком темп и голос: применяется к СЛЕДУЮЩЕЙ реплике.
     *
     * Текущая фраза не обрывается: сервер синтезирует её уже отправленной, и рвать звук
     * ради нового значения значило бы наказать человека за настройку.
     *
     * Смена `voiceId` дополнительно снимает временные паузы селектора. Иначе ручной выбор
     * молча не работал бы: профиль, понижённый прошлым отказом, пропускался бы
     * `candidates()` ещё 45 секунд, и человек слышал бы прежний голос, только что выбрав
     * новый. Снимать паузы на смену ТЕМПА нельзя — это обнуляло бы знание «голос не
     * работает» без всякой причины.
     *
     * `override` — метод порта [ServerTtsClient], а не «просто публичный метод»: движок
     * озвучки вызывает его через порт, поэтому выбор голоса доезжает до синтеза без
     * приведения типа у вызывающего.
     */
    override fun setProsody(prosody: VoiceProsody) {
        val previous = this.prosody
        this.prosody = prosody
        if (previous?.voiceId != prosody.voiceId) {
            runCatching { selector?.reset() }
        }
    }

    /**
     * Сказать хосту, что выбранный платный голос не прозвучал.
     *
     * `runCatching` обязателен: колбэк — чужой код (экран, уведомление), и его исключение не
     * должно отменять звук, который уже получен.
     */
    private fun notifyPaidUnavailable(reason: String?) {
        runCatching { onPaidUnavailable(reason) }
    }

    /**
     * Сказать хосту, что выбранный голос заменён другим профилем этой же реплики.
     *
     * `runCatching` по той же причине: факт подмены не должен стоить реплике звука.
     */
    private fun notifyVoiceSubstituted(wanted: String?, actual: String, reason: String?) {
        runCatching { onVoiceSubstituted(wanted, actual, reason) }
    }

    companion object {
        /** Ни один профиль не дал аудио: цепочка пройдена до конца. */
        const val PROFILES_EXHAUSTED: String = "profiles_exhausted"

        /** Все профили на паузе после отказов — запрос не отправлялся. */
        const val PROFILES_COOLING: String = "profiles_cooling"

        /** Нет device-credential и прямой сокет не дал звука — приглашение к фолбэку. */
        const val REASON_UNAUTHORIZED: String = "unauthorized"

        /** Лимитер `/api/tts` (2 с на клиента) — единственная причина повтора запроса. */
        const val REASON_RATE_LIMITED: String = "rate_limited"

        /** Движок, у которого есть прямой WebSocket-путь (остальные идут на сервер). */
        const val ENGINE_EDGE: String = "edge"

        /**
         * Движок платного облачного синтеза (исторический маркер `plusvibe-onyx`).
         *
         * Это маркер МАРШРУТА, а не поле запроса `/api/tts`: профиль с этим движком никогда
         * не уходит на сервер Марса — только в [PaidTtsClient], а при отказе — в прямой
         * нейро-голос ([ServerTtsVoices.NATURAL_DMITRY]).
         */
        const val ENGINE_OPENAI: String = "openai"

        /**
         * Движок ОСНОВНОГО платного голоса (`plusvibe-alice`, ElevenLabs turbo).
         *
         * Отдельный маркер, а не переиспользование [ENGINE_OPENAI]: маркер описывает модель,
         * а не компанию, и смешивать `gpt-4o-mini-tts` с `elevenlabs-v4-tts` под одним
         * именем значило бы потерять это различие в логах и диагностике.
         */
        const val ENGINE_PLUSVIBE: String = "plusvibe"

        /** Платный клиент не подключён (нет ключа провайдера): фолбэк на нейро-голос. */
        const val REASON_NO_PAID_CLIENT: String = "paid_client_absent"

        /**
         * Бюджет ожидания голоса истёк ([ServerTtsVoiceProfile.waitBudgetMs]).
         *
         * Отдельная причина, а не `timeout` транспорта: транспортный таймаут — «апстрим не
         * ответил за 90 с», а здесь мы сами решили не ждать зависший канал. В диагностике
         * `lastAttempts` эти два случая обязаны различаться, иначе нельзя понять, чинить ли
         * сеть или конкретный голос. Человеку показывается обычная подмена
         * ([ru.mars.mobile.VoiceSubstitutionNotice.paidUnavailable]): «голос не ответил —
         * читаю другим голосом».
         */
        const val REASON_PAID_DEADLINE: String = "deadline"

        /** Пауза повтора, если сервер не прислал `Retry-After`: окно лимитера — 2 с. */
        const val DEFAULT_RATE_LIMIT_WAIT_MS: Long = 2_000L

        /** Нижняя граница паузы: мгновенный повтор просто снова попадёт в лимитер. */
        const val MIN_RATE_LIMIT_WAIT_MS: Long = 500L

        /** Верхняя граница паузы: дольше ждать нельзя — речь превратится в тишину. */
        const val MAX_RATE_LIMIT_WAIT_MS: Long = 2_500L
    }

}
