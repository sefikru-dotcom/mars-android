package ru.mars.network.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.mars.network.http.HttpClients
import ru.mars.network.model.ConsumedDeviceCredential
import java.io.IOException

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
     */
    class Unavailable(val reason: String, val httpCode: Int? = null) : ServerTtsResult
}

/**
 * Контракт `POST /api/tts`, снятый чтением боевого `api/route_tts.py` и проверенный
 * живым запросом (401 на device Bearer — пока серверный блокер, см.
 * `parity-contracts-verified.md`, A5).
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
     * Тело запроса. Флаг `engine` включается только когда задан явно: без него сервер
     * сам подставляет `edge`.
     */
    fun buildBody(
        text: String,
        rate: String? = null,
        pitch: String? = null,
        engine: String? = null,
    ): String = buildString(text.length + 96) {
        append("{\"text\":").append(JsonStringEncoding.escape(text))
        engine?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append(",\"engine\":").append(JsonStringEncoding.escape(it))
        }
        prosodyOrNull(rate, "%")?.let { append(",\"rate\":").append(JsonStringEncoding.escape(it)) }
        prosodyOrNull(pitch, "Hz")?.let { append(",\"pitch\":").append(JsonStringEncoding.escape(it)) }
        append('}')
    }

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
 * Синтез ответа сервером: `POST /api/tts` c device-Bearer.
 *
 * Отдельный HTTP-метод, потому что ручка — единственная, отдающая бинарное аудио, и её
 * ответ нельзя пропустить через JSON-парсеры остальных репозиториев.
 *
 * Никогда не бросает исключение наружу: транспортный сбой и невалидный ответ возвращаются
 * как [ServerTtsResult.Unavailable], чтобы вызывающий слой мог ПРОЗРАЧНО откатиться на
 * системный TTS без обработки ошибок в UI.
 *
 * Известный серверный блокер (не баг клиента): `/api/tts` делает собственную cookie-проверку
 * и не входит в `NATIVE_BEARER_EXACT_PATHS`, поэтому device-Bearer сейчас получает 401. Код
 * синтеза всё равно реализован: когда сервер откроет маршрут, фолбэк перестанет срабатывать
 * без единой правки клиента.
 */
class ServerTtsRepository(
    private val client: OkHttpClient = HttpClients.rest,
) {

    /**
     * Синтезирует [text] на сервере.
     *
     * @param baseUrl базовый адрес бэкенда (например `https://marscore.ru`).
     * @param credential device-credential: `Authorization: Bearer <accessToken>`.
     * @param text уже отфильтрованный для озвучки текст (вызывающий слой обязан
     *   проверить [ServerTtsContract.isSendable] — здесь длина НЕ проверяется, чтобы
     *   факт отправки длинного текста был виден в тестах контракта).
     * @param rate необязательное `rate` (`+10%`); невалидное значение не попадёт в тело.
     * @param pitch необязательное `pitch` (`-5Hz`); невалидное значение не попадёт в тело.
     */
    suspend fun synthesize(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        text: String,
        rate: String? = null,
        pitch: String? = null,
    ): ServerTtsResult = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments(ServerTtsContract.PATH)
            .build()
        val body = ServerTtsContract.buildBody(text, rate = rate, pitch = pitch)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${credential.accessToken}")
            .header("Accept", "audio/mpeg")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                val contentType = response.header("Content-Type")
                when {
                    response.code !in 200..299 -> ServerTtsResult.Unavailable(
                        reason = ServerTtsContract.reasonForHttpCode(response.code),
                        httpCode = response.code,
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
