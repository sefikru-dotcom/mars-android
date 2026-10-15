package ru.mars.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.ServerTtsContract
import ru.mars.network.repository.ServerTtsRepository
import ru.mars.network.repository.ServerTtsResult

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
        text: String,
        rate: String?,
        pitch: String?,
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
 * Известный серверный блокер (не баг клиента): `/api/tts` делает собственную
 * cookie-проверку и не входит в `NATIVE_BEARER_EXACT_PATHS`, поэтому device-Bearer
 * сейчас получает 401 и всегда срабатывает ветка 4. Когда сервер откроет маршрут,
 * серверное аудио начнёт звучать без единой правки клиента.
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
 * @param transport точка подмены для тестов; по умолчанию — боевой HTTP-репозиторий.
 */
class RepositoryServerTtsClient(
    private val baseUrl: String,
    private val credentialProvider: () -> ConsumedDeviceCredential?,
    private val rate: String? = null,
    private val pitch: String? = null,
    private val transport: ServerTtsTransport = ServerTtsTransport { url, credential, text, rateValue, pitchValue ->
        ServerTtsRepository().synthesize(url, credential, text, rate = rateValue, pitch = pitchValue)
    },
) : ServerTtsClient {

    override suspend fun synthesize(text: String): ServerTtsResult {
        val credential = credentialProvider() ?: return ServerTtsResult.Unavailable("unauthorized")
        return try {
            transport.synthesize(baseUrl, credential, text, rate, pitch)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Репозиторий и сам не бросает; страховка — фолбэк вместо падения.
            ServerTtsResult.Unavailable("transport")
        }
    }
}
