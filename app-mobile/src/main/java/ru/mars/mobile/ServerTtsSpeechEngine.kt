package ru.mars.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.EdgeNeuralTtsClient
import ru.mars.network.repository.ServerTtsContract
import ru.mars.network.repository.ServerTtsRepository
import ru.mars.network.repository.ServerTtsResult
import ru.mars.network.repository.ServerTtsSpeechRequest
import ru.mars.network.repository.ServerTtsVoiceProfile
import ru.mars.network.repository.ServerTtsVoices

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
 * Голосовой вывод с серверным синтезом и ПРОЗРАЧНЫМ фолбэком на системный Android TTS.
 *
 * Реализует контракт [VoiceOutputController.SpeechEngine], оборачивая платформенный
 * движок ([fallback], обычно [AndroidSpeechEngine]), поэтому [VoiceOutputController] и
 * весь существующий голосовой путь не меняются: контроллер видит один движок и
 * по-прежнему получает только «принято / не принято».
 *
 * Порядок решения для одной реплики:
 * 1. текст длиннее [ServerTtsContract.MAX_TEXT_LENGTH] — на сервер НЕ отправляем
 *    (иначе гарантированный `400 text too long`), сразу системный TTS;
 * 2. иначе запускается серверный синтез; [speak] возвращает `true` немедленно
 *    (fire-and-forget, как у платформенного движка) и не блокирует UI-поток;
 * 3. пришло аудио и оно стартовало — говорим им; системный TTS не задействован;
 * 4. любой другой исход (401/404/429/5xx, невалидный ответ, транспортный сбой,
 *    не запустившееся воспроизведение, асинхронная ошибка декодера) — ТОТ ЖЕ текст
 *    уходит в [fallback]. Пользователю не показывается ни ошибки, ни тоста, наружу
 *    ничего не бросается.
 *
 * Серверный маршрут открыт: `/api/tts` входит в `NATIVE_BEARER_EXACT_PATHS`
 * (`api/auth.py`), device-Bearer получает 200 и MP3 — ветка 4 ниже остаётся
 * честной деградацией при реальном отказе сервера, а не постоянным путём
 * исполнения (прежнее описание «всегда 401 из-за cookie-проверки» устарело).
 *
 * Реплики не накладываются: каждый новый [speak] отменяет предыдущую незавершённую
 * попытку (семантика `QUEUE_FLUSH` платформенного движка).
 *
 * @param fallback системный TTS — обязательный путь деградации.
 * @param client источник серверного аудио.
 * @param playback проигрывание серверного аудио.
 * @param scope область для фонового синтеза (в приложении — `lifecycleScope`).
 * @param onServerAudioAvailable вызывается один раз при первом реально зазвучавшем
 *   серверном аудио: хост может вернуть вывод в строй, если платформенный TTS на
 *   устройстве отсутствовал (серверный канал от него не зависит).
 */
class ServerTtsSpeechEngine(
    private val fallback: VoiceOutputController.SpeechEngine,
    private val client: ServerTtsClient,
    private val playback: ServerTtsPlayback,
    private val scope: CoroutineScope,
    private val onServerAudioAvailable: () -> Unit = {},
) : VoiceOutputController.SpeechEngine {

    /** Незавершённая серверная попытка; отменяется новым [speak]/[stop]/[shutdown]. */
    private var attempt: Job? = null

    /** Системный TTS уже отказал: повторять его вслепую нельзя. */
    private var fallbackDead = false

    /** Последняя серверная попытка не дала звука: серверный канал пока не работает. */
    private var lastServerUnavailable = true

    /** Серверное аудио хоть раз зазвучало (для [onServerAudioAvailable] — один раз). */
    private var serverAnnounced = false

    /** Сколько раз уходили на сервер — диагностика и тесты, решений не принимает. */
    var serverAttemptCount: Int = 0
        private set

    /**
     * Озвучивает [text]: сервером, а при любом сбое — системным TTS.
     *
     * @return `true`, если реплика принята (серверная попытка запущена либо её принял
     *   системный движок); `false` — звука не будет НИ одним каналом (оба уже отказали),
     *   тогда контроллер честно выключает вывод и показывает «озвучивание недоступно».
     */
    override fun speak(text: String): Boolean {
        // Правило длины: >5000 символов сервер отклоняет с 400 — не создаём этот 400 сами.
        if (text.length > ServerTtsContract.MAX_TEXT_LENGTH) {
            return trackFallback(fallback.speak(text))
        }
        // Оба канала уже мертвы — не притворяемся, что реплика принята.
        if (fallbackDead && lastServerUnavailable) return false
        attempt?.cancel()
        serverAttemptCount++
        attempt = scope.launch {
            val result = try {
                client.synthesize(text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Порты обязаны не бросать; страховка — тот же фолбэк вместо падения.
                ServerTtsResult.Unavailable("transport")
            }
            when (result) {
                is ServerTtsResult.Audio -> {
                    val started = playback.play(result.bytes, result.contentType) {
                        // Асинхронный отказ декодера: тот же текст уходит в системный TTS.
                        trackFallback(fallback.speak(text))
                    }
                    if (started) markServerWorking() else trackFallback(fallback.speak(text))
                }
                is ServerTtsResult.Unavailable -> {
                    lastServerUnavailable = true
                    trackFallback(fallback.speak(text))
                }
            }
        }
        return true
    }

    /**
     * Темп и высота: сервер получает их в теле запроса, а системный резерв — вызовом.
     *
     * Две разные дороги, и обе нужны: у `api/tts` темп едет полем запроса (его подставляет
     * [RepositoryServerTtsClient]), а платформенный TTS меняет темп только через
     * `setSpeechRate`. Если пробросить вызов лишь в одну сторону, человек услышит свой
     * темп ровно до первого отказа сервера, а дальше — прежний.
     */
    override fun setProsody(prosody: VoiceProsody) {
        runCatching { fallback.setProsody(prosody) }
    }

    /** Тишина: отменяет серверную попытку и глушит оба канала. */
    override fun stop() {
        attempt?.cancel()
        attempt = null
        runCatching { playback.stop() }
        runCatching { fallback.stop() }
    }

    /** Хост уничтожен: освобождаем оба движка. */
    override fun shutdown() {
        attempt?.cancel()
        attempt = null
        runCatching { playback.release() }
        runCatching { fallback.shutdown() }
    }

    /** Системный TTS ответил отказом — фиксируем, чтобы не обещать звук вслепую. */
    private fun trackFallback(accepted: Boolean): Boolean {
        if (!accepted) fallbackDead = true
        return accepted
    }

    /** Серверное аудио зазвучало: канал рабочий, при первом успехе сообщаем хосту. */
    private fun markServerWorking() {
        lastServerUnavailable = false
        if (!serverAnnounced) {
            serverAnnounced = true
            runCatching { onServerAudioAvailable() }
        }
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
 * 1. прямой WebSocket ([EdgeNeuralTtsClient]) выбранным голосом — ДАЖЕ без credential:
 *    Microsoft не спрашивает наш токен, и именно этот путь снимает 2-секундный лимитер
 *    сервера, из-за которого «Светлана», «Дмитрий» и «как в вебе» звучали одним системным
 *    голосом Android (жалоба владельца «дмитрий, светлана — один и тот же голос»);
 * 2. если сокет не дал аудио (провайдер блокирует Bing, DRM-отказ, таймаут) — серверный
 *    `/api/tts` тем же голосом, с ОДНИМ повтором после `429`;
 * 3. дальше — цепочка профилей (Дмитрий → Ава → Ирина) по фактическому ответу сервера.
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
    private val transport: ServerTtsTransport = ServerTtsTransport { url, credential, request ->
        ServerTtsRepository().synthesize(url, credential, request)
    },
    /**
     * Ожидание перед повтором после `429`.
     *
     * Подменяется в тестах: настоящие 0.5–2.5 с сделали бы проверку ретрая медленной, а
     * логика («подождать и повторить РОВНО один раз») от длительности не зависит.
     */
    private val sleeper: suspend (Long) -> Unit = { millis -> delay(millis) },
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
        val chosen = explicitChoice ?: ServerTtsVoices.NATURAL_SVETLANA
        val attempts = ArrayList<Attempt>(ServerTtsVoices.VOICE_DIALOG.size + 1)

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
            // Честнее явный отказ — вызывающий слой уйдёт на системный TTS.
            lastProfileId = if (direct == null) null else chosen.id
            lastAttempts = attempts
            return ServerTtsResult.Unavailable(PROFILES_COOLING)
        }
        var last: ServerTtsResult.Unavailable? = null
        for (profile in candidates) {
            lastProfileId = profile.id
            when (val result = requestWithRateLimitRetry(text, credential, profile)) {
                is ServerTtsResult.Audio -> {
                    attempts += Attempt(profile.id, null, null)
                    lastAttempts = attempts
                    selector.onAudio(profile)
                    return result
                }
                is ServerTtsResult.Unavailable -> {
                    attempts += Attempt(profile.id, result.reason, result.httpCode)
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
        // увидит Unavailable и озвучит системным TTS — это видимая деградация, не молчание.
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
     */
    fun setProsody(value: VoiceProsody) {
        val previous = prosody
        prosody = value
        if (previous?.voiceId != value.voiceId) {
            runCatching { selector?.reset() }
        }
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

        /** Пауза повтора, если сервер не прислал `Retry-After`: окно лимитера — 2 с. */
        const val DEFAULT_RATE_LIMIT_WAIT_MS: Long = 2_000L

        /** Нижняя граница паузы: мгновенный повтор просто снова попадёт в лимитер. */
        const val MIN_RATE_LIMIT_WAIT_MS: Long = 500L

        /** Верхняя граница паузы: дольше ждать нельзя — речь превратится в тишину. */
        const val MAX_RATE_LIMIT_WAIT_MS: Long = 2_500L
    }

}
