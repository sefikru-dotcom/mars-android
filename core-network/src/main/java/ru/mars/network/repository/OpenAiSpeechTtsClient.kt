package ru.mars.network.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.mars.network.http.HttpClients
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Платный облачный синтез по OpenAI-совместимому API: `POST <baseUrl>/audio/speech`.
 *
 * Зачем отдельный клиент, а не серверный `/api/tts`: у провайдера (PlusVibe, OpenAI)
 * объявлена модель `gpt-4o-mini-tts`, которая читает живой разговорной интонацией и умеет
 * `instructions` (тембр, темп, манера). Это ЧЕТВЁРТЫЙ тембр, а не перекраска edge-голосов,
 * и он платный — поэтому он не включается сам, а выбирается человеком в шторке
 * ([ServerTtsVoices.PLUSVIBE_ONYX]).
 *
 * Главное свойство — БЫСТРЫЙ отказ. Апстрим `gpt-4o-mini-tts` у PlusVibe нестабилен
 * (живой замер: 1 из 10 запросов отдал валидный mp3, остальные зависли на чтении дольше
 * 15 с). Ожидание такого зависания в потоке речи — это ровно та пауза, из-за которой
 * владелец слышал «доходит до запятой и останавливается». Поэтому таймауты жёсткие:
 * коннект 3 с, чтение 5 с, весь вызов 6 с — и любой сбой возвращается как
 * [ServerTtsResult.Unavailable], чтобы [RepositoryServerTtsClient] мгновенно озвучил ту же
 * фразу прямым нейро-голосом ([EdgeNeuralTtsClient]) без второй паузы.
 *
 * Клиент НИКОГДА не бросает наружу: нет ключа/базы, зависший сокет, 4xx/5xx, пустое тело —
 * всё это [ServerTtsResult.Unavailable] с короткой причиной (без текста реплики и без ключа
 * в причине: причина попадает в логи и тесты).
 *
 * @param baseUrlProvider база API, например `https://plusvibeapi.ru/v1` или
 *   `https://api.openai.com/v1`. Пустая строка/`null` — «ключа нет»: запрос не отправляется.
 * @param apiKeyProvider ключ провайдера. Лямбда, а не значение: ключ приезжает из настроек
 *   уже после создания клиента, и снимок в конструкторе означал бы «голос оживёт только
 *   после перезапуска экрана».
 * @param httpClient транспорт; таймауты заданы в [defaultHttpClient], но подменяются в тестах.
 *
 * Класс `open` намеренно: юнит-тесты [RepositoryServerTtsClient] подменяют [synthesize]
 * наследником, чтобы проверить фолбэк «платный голос → прямой нейро-голос» без сети.
 *
 * ## Статус маршрута: НЕ основной
 * Синхронная ручка работает быстро, но живой замер показал **3 обрезанных ответа из 10**
 * (0,78–1,44 с вместо 5,6 с) на одинаковых запросах. Основной путь платного голоса —
 * асинхронный [PlusVibeMediaTtsClient] (`/api/media/generate` + поллинг задачи), а этот
 * клиент остаётся как эталон контракта OpenAI-совместимой ручки и для сравнения поведения.
 * Боевая проводка его больше не использует: обрыв аудио в речи недопустим.
 */
open class OpenAiSpeechTtsClient(
    private val baseUrlProvider: () -> String? = { DEFAULT_BASE_URL },
    private val apiKeyProvider: () -> String? = { null },
    private val httpClient: OkHttpClient = defaultHttpClient(),
) : PaidTtsClient {

    /**
     * Синтезирует [text] голосом [voice] моделью [model].
     *
     * @param voice имя голоса провайдера (`onyx`, `alloy`, `nova`, `shimmer`, `echo`, `fable`).
     * @param model модель синтеза; пустая строка — [DEFAULT_MODEL], единственная, которую
     *   PlusVibe реально отдаёт (остальные отвечают `400 requested speech model is unavailable`).
     *   Значение по умолчанию объявлено в порту [PaidTtsClient]: переопределение не имеет
     *   права дублировать дефолты (это запрещено языком), поэтому пустая строка здесь
     *   означает «взять [DEFAULT_MODEL]».
     * @param speed темп `0.25..4.0`; значения вне диапазона зажимаются, `NaN` — [DEFAULT_SPEED].
     * @param instructions манера чтения (`gpt-4o-mini-tts`); `null` — [DEFAULT_INSTRUCTIONS]:
     *   без подсказки модель читает ровно и безжизненно, поэтому «нет значения» и «нет
     *   подсказки» — разные вещи, и второе доступно только через [buildBody] напрямую.
     * @param voiceId голос из библиотеки провайдера. У синхронного `/v1/audio/speech` такого
     *   поля НЕТ (там `voice` — имя из перечисления модели), поэтому параметр принимается
     *   ради общего порта [PaidTtsClient] и НЕ отправляется: притвориться, что голос уехал,
     *   значило бы вернуть аудио не тем голосом и без единого признака подмены.
     * @return [ServerTtsResult.Audio] с mp3-байтами либо [ServerTtsResult.Unavailable]:
     *   `no_api_key`, `empty_text`, `text_too_long`, `empty_audio`, `transport`, `http_<code>`.
     */
    override suspend fun synthesize(
        text: String,
        voice: String,
        model: String,
        speed: Float,
        instructions: String?,
        @Suppress("UNUSED_PARAMETER") voiceId: String?,
    ): ServerTtsResult {
        // Ключ и база проверяются ДО сети: без ключа запрос гарантированно 401, а стоил бы
        // коннекта и ожидания. Это не ошибка пользователю, а приглашение к фолбэку.
        val apiKey = apiKeyProvider()?.trim().orEmpty()
        val baseUrl = baseUrlProvider()?.trim().orEmpty()
        if (apiKey.isEmpty() || baseUrl.isEmpty()) {
            return ServerTtsResult.Unavailable(REASON_NO_KEY)
        }
        val input = text.trim()
        if (input.isEmpty()) return ServerTtsResult.Unavailable(REASON_EMPTY_TEXT)
        // Лимит модели — 4096 символов: не создаём 400 сами, как и ServerTtsContract.MAX_TEXT_LENGTH.
        if (input.length > MAX_INPUT_LENGTH) return ServerTtsResult.Unavailable(REASON_TOO_LONG)
        val url = try {
            baseUrl.toHttpUrl().newBuilder().addPathSegments(PATH).build().toString()
        } catch (invalid: IllegalArgumentException) {
            // Кривая база (человек вписал её руками) — отказ синтеза, а не падение реплики.
            return ServerTtsResult.Unavailable(REASON_TRANSPORT)
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", AUDIO_CONTENT_TYPE)
            // Пустая модель/`null` манера — это «дефолт провайдера», а не «без поля»:
            // у `gpt-4o-mini-tts` отсутствие `instructions` даёт ровное безжизненное чтение.
            .post(
                buildBody(
                    text = input,
                    voice = voice,
                    model = model,
                    speed = speed,
                    instructions = instructions ?: DEFAULT_INSTRUCTIONS,
                ).toRequestBody(JSON_MEDIA_TYPE),
            )
            .build()
        return withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(request).execute().use { response ->
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    when {
                        // Не 2xx — отказ провайдера. Причина без тела ответа: тело может
                        // содержать служебный текст провайдера, а нам нужен только код.
                        response.code != HTTP_OK -> ServerTtsResult.Unavailable(
                            reason = ServerTtsContract.reasonForHttpCode(response.code),
                            httpCode = response.code,
                        )
                        bytes.isEmpty() -> ServerTtsResult.Unavailable(
                            reason = REASON_EMPTY_AUDIO,
                            httpCode = response.code,
                        )
                        else -> ServerTtsResult.Audio(bytes, AUDIO_CONTENT_TYPE)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                // Таймаут чтения/коннекта — самый частый исход у зависшего апстрима.
                ServerTtsResult.Unavailable(REASON_TRANSPORT)
            } catch (error: Exception) {
                ServerTtsResult.Unavailable(REASON_TRANSPORT)
            }
        }
    }

    companion object {

        /** Относительный путь ручки (добавляется к базе вида `.../v1`). */
        const val PATH: String = "audio/speech"

        /** База по умолчанию: PlusVibe отдаёт `gpt-4o-mini-tts` именно на этом префиксе. */
        const val DEFAULT_BASE_URL: String = "https://plusvibeapi.ru/v1"

        /** Модель синтеза: единственная живая у PlusVibe (`tts-1`/`tts-1-hd` отвечают 400). */
        const val DEFAULT_MODEL: String = "gpt-4o-mini-tts"

        /** Голос по умолчанию: глубокий мужской тембр («Оникс»). */
        const val DEFAULT_VOICE: String = "onyx"

        /**
         * Манера чтения по умолчанию: русский язык, живая интонация, спокойный темп.
         *
         * `instructions` — единственное, что отличает `gpt-4o-mini-tts` от «диктора»:
         * без подсказки модель читает ровно и безжизненно, а с ней — как собеседник.
         */
        const val DEFAULT_INSTRUCTIONS: String =
            "Говори по-русски живо и естественно, тепло, с ясной дикцией и спокойным темпом."

        /** Формат ответа: mp3, который играет `AndroidServerTtsPlayback`. */
        const val RESPONSE_FORMAT: String = "mp3"

        /** Content-Type отдаваемого аудио (совпадает с серверным `/api/tts`). */
        const val AUDIO_CONTENT_TYPE: String = "audio/mpeg"

        /** Границы поля `speed` в контракте провайдера. */
        const val MIN_SPEED: Float = 0.25f

        const val MAX_SPEED: Float = 4.0f

        /** Темп по умолчанию: естественная скорость голоса. */
        const val DEFAULT_SPEED: Float = 1.0f

        /** Лимит модели на длину `input` (символы). */
        const val MAX_INPUT_LENGTH: Int = 4096

        private const val HTTP_OK = 200

        /** Нет ключа или базы: платный голос недоступен, это приглашение к фолбэку. */
        const val REASON_NO_KEY: String = "no_api_key"

        /** Сокет не поднялся/завис на чтении: приглашение к прямому нейро-голосу. */
        const val REASON_TRANSPORT: String = "transport"

        /** Синтезировать нечего. */
        const val REASON_EMPTY_TEXT: String = "empty_text"

        /** Текст длиннее лимита модели — отказ ДО запроса, чтобы не ловить 400. */
        const val REASON_TOO_LONG: String = "text_too_long"

        /** `200` без байтов: провайдер ответил пустотой. */
        const val REASON_EMPTY_AUDIO: String = "empty_audio"

        private const val CONNECT_TIMEOUT_SECONDS = 3L

        private const val READ_TIMEOUT_SECONDS = 5L

        /**
         * Потолок всего вызова. Отдельно от чтения: зависший апстрим может отдавать
         * заголовки и молчать в теле — без `callTimeout` такой вызов жил бы до 5 с на
         * каждый чанк, а речь не может ждать столько между фразами.
         */
        private const val CALL_TIMEOUT_SECONDS = 6L

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /**
         * Транспорт с жёсткими таймаутами (общий пул соединений модуля).
         *
         * 3 с коннект / 5 с чтение / 6 с весь вызов: при зависании PlusVibe фраза уходит
         * на прямой нейро-голос почти сразу, а не через 15–20 с (таймауты `HttpClients.rest`).
         */
        fun defaultHttpClient(): OkHttpClient = HttpClients.restBuilder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        /**
         * Тело запроса. Порядок полей фиксирован контрактом провайдера
         * (`model`, `input`, `voice`, `response_format`, `speed`, `instructions`), а
         * `instructions` включается только когда задан: пустая строка в этом поле
         * заставляет модель читать «никак», то есть хуже, чем без поля.
         */
        fun buildBody(
            text: String,
            voice: String,
            model: String = DEFAULT_MODEL,
            speed: Float = DEFAULT_SPEED,
            instructions: String? = DEFAULT_INSTRUCTIONS,
        ): String = buildString(text.length + 192) {
            append("{\"model\":").append(JsonStringEncoding.escape(model.trim().ifEmpty { DEFAULT_MODEL }))
            append(",\"input\":").append(JsonStringEncoding.escape(text))
            append(",\"voice\":").append(JsonStringEncoding.escape(voice.trim().ifEmpty { DEFAULT_VOICE }))
            append(",\"response_format\":").append(JsonStringEncoding.escape(RESPONSE_FORMAT))
            append(",\"speed\":").append(speedJson(speed))
            instructions?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append(",\"instructions\":").append(JsonStringEncoding.escape(it))
            }
            append('}')
        }

        /**
         * `speed` как JSON-число в границах `0.25..4.0`.
         *
         * `NaN`/`Infinity` не пропускаются: провайдер ответил бы `400 invalid speed`, а
         * причина отказа была бы неотличима от зависания. `Float.toString` даёт точку
         * (не запятую) и не зависит от локали устройства.
         */
        fun speedJson(speed: Float): String {
            val safe = if (speed.isNaN()) DEFAULT_SPEED else speed.coerceIn(MIN_SPEED, MAX_SPEED)
            return safe.toString()
        }
    }
}
