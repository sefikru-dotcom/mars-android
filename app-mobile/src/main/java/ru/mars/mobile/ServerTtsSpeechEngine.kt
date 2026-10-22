package ru.mars.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.ServerTtsContract
import ru.mars.network.repository.ServerTtsRepository
import ru.mars.network.repository.ServerTtsResult
import ru.mars.network.repository.ServerTtsSpeechRequest

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
 * @param selector выбор профиля голоса; `null` — «пусть сервер решит сам» (старое поведение,
 *   нужное только тестам контракта).
 * @param transport точка подмены для тестов; по умолчанию — боевой HTTP-репозиторий.
 */
class RepositoryServerTtsClient(
    private val baseUrl: String,
    private val credentialProvider: () -> ConsumedDeviceCredential?,
    private val selector: SpeechVoiceProfileSelector? = SpeechVoiceProfileSelector(),
    private val transport: ServerTtsTransport = ServerTtsTransport { url, credential, request ->
        ServerTtsRepository().synthesize(url, credential, request)
    },
) : ServerTtsClient {

    /** Последний профиль, которым реально синтезировали (диагностика, без текста). */
    var lastProfileId: String? = null
        private set

    override suspend fun synthesize(text: String): ServerTtsResult {
        val credential = credentialProvider() ?: return ServerTtsResult.Unavailable("unauthorized")
        val profile = selector?.current()
        lastProfileId = profile?.id
        val request = ServerTtsSpeechRequest.of(text, profile)
        val result = try {
            transport.synthesize(baseUrl, credential, request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Репозиторий и сам не бросает; страховка — фолбэк вместо падения.
            ServerTtsResult.Unavailable("transport")
        }
        // Обратная связь только по факту ответа сервера: удача снимает понижение, отказ по
        // голосу понижает профиль, а лимитер/токен — нет (см. SpeechVoiceProfileSelector).
        if (profile != null) {
            when (result) {
                is ServerTtsResult.Audio -> selector?.onAudio(profile)
                is ServerTtsResult.Unavailable -> selector?.onUnavailable(profile, result)
            }
        }
        return result
    }
}
