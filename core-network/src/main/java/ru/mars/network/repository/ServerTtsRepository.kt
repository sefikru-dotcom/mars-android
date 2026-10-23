package ru.mars.network.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.mars.network.http.HttpClients
import ru.mars.network.model.ConsumedDeviceCredential
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.coroutines.coroutineContext

/**
 * Исход синтеза через серверный `POST /api/tts`.
 *
 * Ручка отвечает ГОТОВЫМ аудио (`Content-Type: audio/mpeg`, тело — mp3-байты),
 * а не ссылкой: `api/route_tts.py` буферизует аудио движка (edge/elevenlabs/openai)
 * и отдаёт его с `Content-Length`. Поэтому успех — это байты, а не URL.
 */
sealed interface ServerTtsResult {
    /** 2xx, Content-Type вида `audio/…` и непустое тело. */
    class Audio(val bytes: ByteArray, val contentType: String) : ServerTtsResult

    /**
     * Любой другой исход: 4xx/5xx (`{"error": …}`), невалидный ответ (200 без аудио),
     * пустое тело или транспортный сбой. Для клиента это ПРИГЛАШЕНИЕ к фолбэку на
     * системный TTS, а не ошибка пользователю: причина нужна только для лога/тестов.
     *
     * @param reason короткая причина: `unauthorized`, `not_found`, `rate_limited`,
     *   `server_error`, `invalid_response`, `transport`.
     * @param httpCode код ответа либо null (транспортный сбой/невалидное тело).
     * @param retryAfterMs сколько сервер просил подождать (заголовок `Retry-After`),
     *   уже ограниченное сверху [ServerTtsRetryPolicy.maxDelayMs]; null — сервер не сказал.
     */
    class Unavailable(
        val reason: String,
        val httpCode: Int? = null,
        val retryAfterMs: Long? = null,
    ) : ServerTtsResult
}

/**
 * Контракт `POST /api/tts`, снятый чтением боевого `api/route_tts.py` и проверенный
 * живым запросом с device Bearer.
 *
 * Авторизация: ручка входит в `NATIVE_BEARER_EXACT_PATHS` (`api/auth.py:121`), а
 * `api/route_tts.py:272-284` уважает выставленный `check_auth` флаг
 * `_native_auth_device` и делает cookie-проверку ТОЛЬКО когда device-токена нет.
 * То есть синтез с телефона разрешён по device-Bearer без браузерной сессии.
 *
 * Форма запроса:
 * - JSON-тело, обязательное поле `text` (после `strip()` непустое, максимум 5000 символов);
 * - необязательные `rate` (`[+-]?\\d{1,3}%`, −100..100) и `pitch` (`[+-]?\\d{1,3}Hz`, −100..100);
 * - любое несоответствие тела → 400 (`invalid request body`, `text is required`,
 *   `text too long (max 5000 characters)`, `invalid rate`, `invalid pitch`);
 * - нет авторизации → 401 `unauthorized`.
 *
 * Клиент обязан не создавать 400 сам: [isSendable] отсекает длинный текст ДО запроса,
 * а [prosodyOrNull] не пропускает в тело заведомо невалидные rate/pitch.
 */
object ServerTtsContract {

    /** Относительный путь ручки (без ведущего слэша, как у остальных вызовов модуля). */
    const val PATH: String = "api/tts"

    /** Жёсткий лимит сервера: `len(text) > 5000` → 400 `text too long`. */
    const val MAX_TEXT_LENGTH: Int = 5000

    /** Движок по умолчанию в `route_tts.py` (`engine = "edge"`). */
    const val DEFAULT_ENGINE: String = "edge"

    /**
     * Верхняя граница принимаемого аудио. Серверный прокси ограничен
     * `_TTS_PROXY_MAX_BYTES = 16 MB`; более длинное тело считаем невалидным ответом,
     * чтобы не держать в памяти случайный мусор.
     */
    private const val MAX_AUDIO_BYTES: Long = 16L * 1024 * 1024

    private val RATE_REGEX = Regex("""^[+-]?\d{1,3}%$""")
    private val PITCH_REGEX = Regex("""^[+-]?\d{1,3}Hz$""")

    /**
     * Текст допустим к отправке на сервер? Лимит считается по длине Kotlin-строки
     * (UTF-16): для астральных символов это СТРОЖЕ серверного `len()` в Python (код-поинты),
     * поэтому клиент никогда не отправит слишком длинный текст — в худшем случае раньше
     * уйдёт на системный TTS.
     */
    fun isSendable(text: String): Boolean = text.isNotBlank() && text.length <= MAX_TEXT_LENGTH

    /**
     * Нормализация `rate`/`pitch` под серверный `_normalize_tts_prosody`: значение
     * принимается только в форме `[+-]?\\d{1,3}<unit>` с суммой −100..100, иначе `null`.
     * `null` из этого метода означает «в тело НЕ включать» — сервер подставит дефолт
     * движка и 400 `invalid rate/pitch` не возникнет.
     *
     * @param unit `%` для rate, `Hz` для pitch.
     */
    fun prosodyOrNull(value: String?, unit: String): String? {
        val normalized = value?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        val regex = when (unit) {
            "%" -> RATE_REGEX
            "Hz" -> PITCH_REGEX
            else -> return null
        }
        if (!regex.matches(normalized)) return null
        val amount = normalized.dropLast(unit.length).toIntOrNull() ?: return null
        return if (amount in -100..100) normalized else null
    }

    /**
     * Тело запроса. Поля `engine` и `voice` включаются только когда заданы явно: без них
     * сервер подставляет свой дефолт (`engine=edge`, а для piper — свой `voice`), и
     * Android получает НЕ тот голос, что выбран в вебе. Пара (engine, voice) едет вместе,
     * потому что имя голоса имеет смысл только внутри движка.
     */
    fun buildBody(
        text: String,
        rate: String? = null,
        pitch: String? = null,
        engine: String? = null,
        voice: String? = null,
    ): String = buildString(text.length + 96) {
        append("{\"text\":").append(JsonStringEncoding.escape(text))
        engine?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append(",\"engine\":").append(JsonStringEncoding.escape(it))
        }
        voice?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append(",\"voice\":").append(JsonStringEncoding.escape(it))
        }
        prosodyOrNull(rate, "%")?.let { append(",\"rate\":").append(JsonStringEncoding.escape(it)) }
        prosodyOrNull(pitch, "Hz")?.let { append(",\"pitch\":").append(JsonStringEncoding.escape(it)) }
        append('}')
    }

    /** Тело запроса по [request]: один и тот же путь для профиля и для «пусть сервер решит». */
    fun buildBody(request: ServerTtsSpeechRequest): String = buildBody(
        text = request.text,
        rate = request.rate,
        pitch = request.pitch,
        engine = request.engine,
        voice = request.voice,
    )

    /** Ответ похож на синтезированное аудио? */
    fun isAudioPayload(contentType: String?, byteCount: Long): Boolean =
        byteCount in 1..MAX_AUDIO_BYTES && contentType.orEmpty().trim().lowercase().startsWith("audio/")

    /** Маппинг HTTP-кода в короткую причину недоступности. */
    fun reasonForHttpCode(code: Int): String = when (code) {
        401, 403 -> "unauthorized"
        404 -> "not_found"
        405 -> "method_not_allowed"
        429 -> "rate_limited"
        in 500..599 -> "server_error"
        else -> "http_$code"
    }
}

/**
 * Политика одного повторного запроса при `429`/`5xx` от `api/tts`.
 *
 * Почему повтор нужен именно здесь: у ручки лимитер **2 секунды на клиента**, а потоковая
 * озвучка отправляет фразы подряд, пока играет предыдущая. Короткая фраза синтезируется
 * быстрее, чем истекает окно лимитера, и сервер честно отвечает `429 rate limit exceeded`.
 * Без обработки очередь теряет фразу (дырка в речи), с бесконечными ретраями — залипает.
 * Поэтому: не больше [maxAttempts] попыток, пауза из `Retry-After` (если сервер её дал),
 * иначе фиксированный бэкофф, и жёсткий потолок [maxDelayMs] — задержка речи ограничена
 * сверху, а не «сколько попросил сервер».
 *
 * Пауза делается через [delay] в контексте вызывающей корутины: отмена (перебивание,
 * уход с экрана) прерывает ожидание немедленно, а не через 2 секунды.
 *
 * @param maxAttempts всего попыток, включая первую (2 = один повтор).
 * @param maxDelayMs потолок паузы: больше ждать бессмысленно, лучше отдать фразу как
 *   пропущенную и продолжить очередь.
 * @param sleeper подмена ожидания в тестах (по умолчанию настоящий `delay`).
 */
class ServerTtsRetryPolicy(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    private val sleeper: suspend (Long) -> Unit = { millis -> delay(millis) },
) {

    /** Повторять ли запрос с такой причиной отказа. */
    fun isRetryable(reason: String): Boolean = reason == "rate_limited" || reason == "server_error"

    /**
     * Пауза перед повтором: `Retry-After` сервера, иначе бэкофф по номеру попытки.
     * Всегда в `0..maxDelayMs`.
     *
     * @param attempt номер УЖЕ выполненной попытки (1 после первой).
     * @param retryAfterMs разобранный `Retry-After` или null.
     */
    fun delayBeforeRetryMs(attempt: Int, retryAfterMs: Long?): Long {
        val requested = retryAfterMs ?: (DEFAULT_BACKOFF_MS * attempt)
        return requested.coerceIn(0L, maxDelayMs)
    }

    /** Подождать перед повтором. Отмена корутины прерывает ожидание. */
    suspend fun awaitRetry(attempt: Int, retryAfterMs: Long?) {
        val wait = delayBeforeRetryMs(attempt, retryAfterMs)
        if (wait > 0) sleeper(wait)
    }

    companion object {

        /** Две попытки: один повтор закрывает окно лимитера, дальше фраза пропускается. */
        const val DEFAULT_MAX_ATTEMPTS: Int = 2

        /**
         * Потолок паузы. Лимитер `api/tts` — 2 с, поэтому 2.5 с хватает на его окно и не
         * превращает перебивание в «звук через пять секунд».
         */
        const val DEFAULT_MAX_DELAY_MS: Long = 2_500L

        /** Бэкофф, когда `Retry-After` нет: 1.2 с ≈ окно лимитера. */
        const val DEFAULT_BACKOFF_MS: Long = 1_200L

        /**
         * Разбор заголовка `Retry-After`: либо число секунд, либо HTTP-дата
         * (`RFC 1123`). Неизвестный/битый формат — null (тогда работает бэкофф), а не
         * исключение: заголовок приходит от внешнего прокси и не должен ронять синтез.
         *
         * @param nowMs текущее время в мс (для даты); в тестах подменяется.
         */
        fun parseRetryAfterMs(header: String?, nowMs: Long): Long? {
            val raw = header?.trim().orEmpty()
            if (raw.isEmpty()) return null
            raw.toLongOrNull()?.let { seconds -> return (seconds * 1_000L).coerceAtLeast(0L) }
            return try {
                val at = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
                (at.toInstant().toEpochMilli() - nowMs).coerceAtLeast(0L)
            } catch (parse: DateTimeParseException) {
                null
            }
        }
    }
}

/**
 * Синтез ответа сервером: `POST /api/tts` c device-Bearer.
 *
 * Отдельный HTTP-метод, потому что ручка — единственная, отдающая бинарное аудио, и её
 * ответ нельзя пропустить через JSON-парсеры остальных репозиториев.
 *
 * Никогда не бросает исключение наружу: транспортный сбой и невалидный ответ возвращаются
 * как [ServerTtsResult.Unavailable], чтобы вызывающий слой мог ПРОЗРАЧНО откатиться на
 * системный TTS без обработки ошибок в UI.
 *
 * Серверный маршрут открыт для device-Bearer: `/api/tts` перечислен в
 * `NATIVE_BEARER_EXACT_PATHS` (`api/auth.py:121`), а cookie-проверка внутри
 * `api/route_tts.py:272-284` выполняется только при отсутствии `_native_auth_device`.
 * 401 отсюда означает невалидный/отозванный токен, а не закрытый маршрут; фолбэк на
 * системный TTS остаётся страховкой на транспортные сбои и `429`.
 */
class ServerTtsRepository(
    private val client: OkHttpClient = HttpClients.rest,
    /**
     * Политика единственного повтора на `429`/`5xx`. Лимитер `api/tts` — 2 с на клиента,
     * поэтому повтор обязателен, но строго ограничен (см. [ServerTtsRetryPolicy]).
     */
    private val retryPolicy: ServerTtsRetryPolicy = ServerTtsRetryPolicy(),
    /** Часы для разбора `Retry-After` в формате HTTP-даты (в тестах подменяются). */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Синтезирует [text] на сервере.
     *
     * @param baseUrl базовый адрес бэкенда (например `https://marscore.ru`).
     * @param credential device-credential: заголовок `Authorization` со схемой Bearer и
     *   токеном устройства.
     * @param text уже отфильтрованный для озвучки текст (вызывающий слой обязан
     *   проверить [ServerTtsContract.isSendable] — здесь длина НЕ проверяется, чтобы
     *   факт отправки длинного текста был виден в тестах контракта).
     * @param rate необязательное `rate` (`+10%`); невалидное значение не попадёт в тело.
     * @param pitch необязательное `pitch` (`-5Hz`); невалидное значение не попадёт в тело.
     * @param engine необязательный движок (`edge`, `piper`); null — выбор сервера.
     * @param voice необязательный голос движка; имеет смысл только вместе с [engine].
     */
    suspend fun synthesize(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        text: String,
        rate: String? = null,
        pitch: String? = null,
        engine: String? = null,
        voice: String? = null,
    ): ServerTtsResult = synthesize(
        baseUrl = baseUrl,
        credential = credential,
        request = ServerTtsSpeechRequest(
            text = text,
            engine = engine,
            voice = voice,
            rate = rate,
            pitch = pitch,
        ),
    )

    /**
     * Синтез по готовому [request] (текст + профиль голоса).
     *
     * Один повтор на `429`/`5xx`: пауза берётся из `Retry-After`, ограничена
     * [ServerTtsRetryPolicy.maxDelayMs] и прерывается отменой корутины. Терминальные
     * ответы (`401`, `404`, `400 invalid voice`) НЕ повторяются — это сигнал сменить
     * голосовой профиль, а не подождать.
     */
    suspend fun synthesize(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        request: ServerTtsSpeechRequest,
    ): ServerTtsResult = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments(ServerTtsContract.PATH)
            .build()
        var attempt = 1
        var result: ServerTtsResult
        while (true) {
            result = attemptOnce(url.toString(), credential, request)
            val unavailable = result as? ServerTtsResult.Unavailable ?: break
            val canRetry = attempt < retryPolicy.maxAttempts && retryPolicy.isRetryable(unavailable.reason)
            if (!canRetry) break
            // Отмена (перебивание, уход с экрана) рвёт ожидание сразу, без «дожидаем окно».
            coroutineContext.ensureActive()
            retryPolicy.awaitRetry(attempt, unavailable.retryAfterMs)
            attempt++
        }
        result
    }

    /** Одна HTTP-попытка. Исключения не летят наружу: любой сбой — [ServerTtsResult.Unavailable]. */
    private fun attemptOnce(
        url: String,
        credential: ConsumedDeviceCredential,
        speech: ServerTtsSpeechRequest,
    ): ServerTtsResult {
        val body = ServerTtsContract.buildBody(speech)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${credential.accessToken}")
            .header("Accept", "audio/mpeg")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                val contentType = response.header("Content-Type")
                when {
                    response.code !in 200..299 -> ServerTtsResult.Unavailable(
                        reason = ServerTtsContract.reasonForHttpCode(response.code),
                        httpCode = response.code,
                        retryAfterMs = ServerTtsRetryPolicy.parseRetryAfterMs(
                            response.header("Retry-After"),
                            nowMs(),
                        ),
                    )
                    ServerTtsContract.isAudioPayload(contentType, bytes.size.toLong()) ->
                        ServerTtsResult.Audio(bytes, contentType.orEmpty())
                    else -> ServerTtsResult.Unavailable("invalid_response", response.code)
                }
            }
        } catch (error: IOException) {
            ServerTtsResult.Unavailable("transport")
        } catch (error: Exception) {
            ServerTtsResult.Unavailable("transport")
        }
    }
}
