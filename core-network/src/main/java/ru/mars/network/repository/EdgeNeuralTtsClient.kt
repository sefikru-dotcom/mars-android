package ru.mars.network.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import ru.mars.network.http.HttpClients
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Прямой нейросетевой синтез Microsoft Edge «ReadAloud» с телефона: WebSocket без сервера
 * Марса, без device-credential и без 2-секундного лимитера `/api/tts`.
 *
 * Зачем свой клиент, а не только серверный прокси. Через `/api/tts` звук приходил с TTFB
 * 1.7–7 с (двойная буферизация: edge-tts на сервере + HTTP-ответ), а на второй фразе
 * подряд сервер отвечал `429 rate limit exceeded` — и движок молча падал на системный
 * Android TTS. В итоге «Как в вебе», «Светлана» и «Дмитрий» звучали ОДНИМ системным
 * голосом. Прямой WebSocket даёт TTFB ≈ 350–450 мс и снимает лимитер: каждый голос
 * слышен как отдельный голос.
 *
 * Протокол — порт открытого клиента `rany2/edge-tts` (v7.x) на Kotlin + OkHttp:
 * 1. DRM-токен `Sec-MS-GEC` v2 ([generateSecMsGecV2]): `CS64-SHA256` от
 *    `"<windows_file_time><TRUSTED_CLIENT_TOKEN>"`. Без него сервер закрывает сокет
 *    (`403` на upgrade), поэтому алгоритм перенесён посимвольно, а не «похоже».
 * 2. Апгрейд на `wss://speech.platform.bing.com/.../edge/v1` с заголовками браузера
 *    Edge (`User-Agent`, `Origin: chrome-extension://jdiccldimp…`, `Cookie: muid=…`).
 * 3. Два текстовых фрейма: `Path:speech.config` (формат mp3 24 кГц) и `Path:ssml`.
 * 4. Ответ: бинарные фреймы с 2-байтовым big-endian префиксом длины заголовка; аудио —
 *    хвост после `Path:audio`. Конец реплики — текстовый `Path:turn.end`.
 *
 * Клиент НИКОГДА не бросает наружу: любой сбой (нет сети, блокировка провайдера,
 * `403` от DRM, пустое аудио) — [ServerTtsResult.Unavailable], то есть приглашение
 * вызывающему слою уйти на серверный `/api/tts` или системный TTS.
 *
 * @param httpClient транспорт; таймауты заданы здесь (3 с коннект, 6 с чтение), потому
 *   что зависший сокет не должен держать реплику дольше, чем её озвучил бы сервер.
 * @param wssUrl база сокета; параметры `ConnectionId`/`Sec-MS-GEC` добавляются на каждый
 *   запрос. Отдельным параметром — только чтобы тест мог поднять локальный сервер.
 * @param nowSeconds часы (секунды Unix) — момент для `Sec-MS-GEC` и `X-Timestamp`.
 * @param muid генератор `Cookie: muid=…`; подменяется в тестах, чтобы запрос был
 *   детерминированным.
 *
 * Класс `open` намеренно: юнит-тесты `RepositoryServerTtsClient` подменяют [synthesize]
 * наследником, чтобы проверить порядок «прямой сокет → сервер» без реальной сети.
 */
open class EdgeNeuralTtsClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val wssUrl: String = WSS_URL,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
    private val muid: () -> String = { randomHex32() },
) {

    /**
     * Синтезирует [text] голосом [voice].
     *
     * @param voice имя голоса движка (`ru-RU-SvetlanaNeural`, `en-US-AvaMultilingualNeural`).
     * @param rate темп в форме `+8%`; `null` — `+0%`.
     * @param pitch высота в форме `+0Hz`; `null` — `+0Hz`.
     * @return [ServerTtsResult.Audio] с mp3-байтами либо [ServerTtsResult.Unavailable]:
     *   `empty_text`, `edge_transport`, `edge_empty_audio`.
     */
    open suspend fun synthesize(
        text: String,
        voice: String,
        rate: String? = null,
        pitch: String? = null,
    ): ServerTtsResult {
        val plain = cleanText(text)
        if (plain.isBlank()) return ServerTtsResult.Unavailable(REASON_EMPTY_TEXT)
        val request = try {
            buildRequest(voice, plain, rate, pitch)
        } catch (error: Exception) {
            // Кривой URL/заголовок — это отказ синтеза, а не падение реплики.
            return ServerTtsResult.Unavailable(REASON_TRANSPORT)
        }
        return try {
            suspendCancellableCoroutine<ServerTtsResult> { continuation ->
                val audio = ByteArrayOutputStream(DEFAULT_AUDIO_BUFFER_BYTES)
                val finished = AtomicBoolean(false)
                val listener = object : WebSocketListener() {

                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        // Порядок обязателен: конфиг формата ДО ssml, иначе сервер синтезирует
                        // в своём дефолтном формате (webm/opus), который мы не просили.
                        webSocket.send(speechConfigFrame(nowSeconds()))
                        webSocket.send(ssmlFrame(plain, voice, rate, pitch, nowSeconds()))
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        val frame = bytes.toByteArray()
                        // Первые 2 байта — длина заголовка (big-endian), дальше сам заголовок.
                        if (frame.size < HEADER_PREFIX_BYTES) return
                        val headerLength =
                            ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
                        val payloadStart = HEADER_PREFIX_BYTES + headerLength
                        if (headerLength <= 0 || payloadStart > frame.size) return
                        val header = String(frame, HEADER_PREFIX_BYTES, headerLength, Charsets.UTF_8)
                        if (!header.contains(PATH_AUDIO)) return
                        synchronized(audio) {
                            audio.write(frame, payloadStart, frame.size - payloadStart)
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (!text.contains(PATH_TURN_END)) return
                        // Флаг ставится ДО закрытия: иначе `onClosing` успел бы раньше и
                        // превратил бы готовое аудио в «обрыв» (гонка двух колбэков).
                        if (!finished.compareAndSet(false, true)) return
                        webSocket.close(CLOSE_NORMAL, null)
                        val bytes = synchronized(audio) { audio.toByteArray() }
                        continuation.resume(
                            if (bytes.isEmpty()) {
                                ServerTtsResult.Unavailable(REASON_EMPTY_AUDIO)
                            } else {
                                ServerTtsResult.Audio(bytes, AUDIO_CONTENT_TYPE)
                            },
                        )
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(CLOSE_NORMAL, null)
                        // Закрытие без `turn.end` — обрыв на середине: аудио неполное.
                        if (finished.compareAndSet(false, true)) {
                            continuation.resume(ServerTtsResult.Unavailable(REASON_TRANSPORT))
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (finished.compareAndSet(false, true)) {
                            continuation.resume(ServerTtsResult.Unavailable(REASON_TRANSPORT))
                        }
                    }
                }
                val socket = httpClient.newWebSocket(request, listener)
                // Отмена (перебивание, уход с экрана) закрывает сокет сразу, а не «доживает»
                // до конца реплики: иначе сервер продолжал бы синтез в пустоту.
                continuation.invokeOnCancellation { runCatching { socket.cancel() } }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ServerTtsResult.Unavailable(REASON_TRANSPORT)
        }
    }

    /** Запрос на апгрейд: URL с DRM-токеном + заголовки браузера Edge. */
    private fun buildRequest(
        voice: String,
        text: String,
        rate: String?,
        pitch: String?,
    ): Request {
        val connectionId = randomHex32()
        val separator = if (wssUrl.contains('?')) '&' else '?'
        val url = buildString(wssUrl.length + 160) {
            append(wssUrl).append(separator)
            append("ConnectionId=").append(connectionId)
            append("&Sec-MS-GEC=").append(generateSecMsGecV2(nowSeconds()))
            append("&Sec-MS-GEC-Version=").append(SEC_MS_GEC_VERSION)
        }
        // voice/text здесь не нужны: они едут в SSML-фрейме. Параметры оставлены в сигнатуре,
        // чтобы порядок «URL → фреймы» был очевиден на месте вызова.
        check(voice.isNotBlank() && text.isNotBlank())
        return Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Origin", ORIGIN)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Cookie", "muid=${muid()}")
            // Accept-Encoding не задаём: сжатие тела у WebSocket не бывает, а OkHttp сам
            // добавит gzip и сам его распакует — ручной `br` он разобрать не смог бы.
            .header("Accept-Language", "en-US,en;q=0.9,ru;q=0.8")
            .build()
    }

    companion object {
        /** Публичный токен клиента Edge ReadAloud (не секрет: он в открытом клиенте). */
        const val TRUSTED_CLIENT_TOKEN: String = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

        /** База сокета; параметры `ConnectionId`/`Sec-MS-GEC` добавляются на запрос. */
        const val WSS_URL: String =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
                "?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"

        /** Версия DRM-схемы; сервер сверяет её с алгоритмом токена. */
        const val SEC_MS_GEC_VERSION: String = "2-143.0.3650.75"

        /** Браузер Edge, которым представляется клиент: другой UA → отказ апгрейда. */
        const val USER_AGENT: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"

        /** Origin расширения Edge ReadAloud: без него сокет не апгрейдится. */
        const val ORIGIN: String = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"

        /** Формат ответа: mp3 24 кГц, который играет `AndroidServerTtsPlayback`. */
        const val AUDIO_FORMAT: String = "audio-24khz-48kbitrate-mono-mp3"

        /** Content-Type отдаваемого аудио (совпадает с серверным `/api/tts`). */
        const val AUDIO_CONTENT_TYPE: String = "audio/mpeg"

        /** `Path:audio` — заголовок бинарного фрейма с аудио. */
        private const val PATH_AUDIO = "Path:audio"

        /** `Path:turn.end` — реплика синтезирована целиком. */
        private const val PATH_TURN_END = "Path:turn.end"

        private const val HEADER_PREFIX_BYTES = 2

        private const val CLOSE_NORMAL = 1000

        private const val DEFAULT_AUDIO_BUFFER_BYTES = 64 * 1024

        /** Пустой текст: синтезировать нечего, сервер бы ответил ошибкой. */
        const val REASON_EMPTY_TEXT: String = "empty_text"

        /** Сокет не поднялся/оборвался: приглашение к серверному `/api/tts`. */
        const val REASON_TRANSPORT: String = "edge_transport"

        /** `turn.end` без аудио: сервер отказал по голосу или DRM. */
        const val REASON_EMPTY_AUDIO: String = "edge_empty_audio"

        /**
         * Коннект 3 с: недоступный Bing должен отдавать отказ быстро, а не держать фразу
         * 8 с. Раньше здесь стояло 8 с, и при блокировке провайдера очередь речи стояла
         * почти десять секунд ровно на первой же фразе.
         */
        private const val CONNECT_TIMEOUT_SECONDS = 3L

        /**
         * Чтение 6 с: реплика Edge приходит за 350–450 мс, поэтому 6 с — уже аварийный
         * порог (было 15 с: зависший сокет превращался в 15-секундную тишину).
         */
        private const val READ_TIMEOUT_SECONDS = 6L

        /** Транспорт с таймаутами реплики (общий пул соединений модуля). */
        fun defaultHttpClient(): OkHttpClient = HttpClients.restBuilder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        /** 32 hex-символа: `ConnectionId` и `Cookie: muid`. */
        fun randomHex32(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.toHexUpperCase()
        }
        /**
         * Кадр `Path:speech.config`: просим mp3 24 кГц и отключаем границы слов.
         *
         * Границы не нужны: подсветка слов не используется, а лишние фреймы только
         * добавляют работы разбору на каждой реплике.
         */
        fun speechConfigFrame(nowSeconds: Long): String = buildString(256) {
            append("X-Timestamp:").append(timestamp(nowSeconds)).append(CRLF)
            append("Content-Type:application/json; charset=utf-8").append(CRLF)
            append("Path:speech.config").append(CRLF).append(CRLF)
            append("{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{")
            append("\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},")
            append("\"outputFormat\":\"").append(AUDIO_FORMAT).append("\"}}}}")
        }

        /**
         * Кадр `Path:ssml`: текст в `<voice>`/`<prosody>`.
         *
         * Текст обязан быть уже очищен ([cleanText]) и экранирован ([escapeXml]): `&` и `<`
         * в реплике Марса (код, сравнения, «<файл>») ломают XML, и сервер отвечает
         * `turn.end` без аудио — то есть молчанием вместо фразы.
         */
        fun ssmlFrame(
            text: String,
            voice: String,
            rate: String?,
            pitch: String?,
            nowSeconds: Long,
        ): String = buildString(text.length + 320) {
            append("X-RequestId:").append(randomHex32()).append(CRLF)
            append("Content-Type:application/ssml+xml").append(CRLF)
            append("X-Timestamp:").append(timestamp(nowSeconds)).append(CRLF)
            append("Path:ssml").append(CRLF).append(CRLF)
            append("<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' ")
            append("xml:lang='ru-RU'><voice name='").append(escapeXml(voice)).append("'>")
            append("<prosody pitch='").append(escapeXml(pitch ?: DEFAULT_PITCH))
            append("' rate='").append(escapeXml(rate ?: DEFAULT_RATE))
            append("' volume='+0%'>").append(escapeXml(text)).append("</prosody></voice></speak>")
        }

        /**
         * Очистка текста перед SSML.
         *
         * Управляющие символы (`0..8`, `11..12`, `14..31`) недопустимы в XML 1.0 даже в
         * escaped-виде: сервер закрывает сокет. `\n`/`\t` оставлены — они законны и нужны
         * как паузы. Остальное (`&`, `<`, `>`, кавычки) экранируется в [escapeXml].
         */
        fun cleanText(text: String): String = text.filter { char ->
            val code = char.code
            code == 0x09 || code == 0x0A || code == 0x0D || (code in 0x20..0x10FFFF)
        }.trim()

        /** Экранирование XML: пять обязательных сущностей, в правильном порядке. */
        fun escapeXml(value: String): String = buildString(value.length + 16) {
            value.forEach { char ->
                when (char) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '\'' -> append("&apos;")
                    '"' -> append("&quot;")
                    else -> append(char)
                }
            }
        }

        /** Темп по умолчанию: `+0%` — движок читает в своём естественном темпе. */
        const val DEFAULT_RATE: String = "+0%"

        /** Высота по умолчанию: `+0Hz`. */
        const val DEFAULT_PITCH: String = "+0Hz"

        private const val CRLF = "\r\n"

        /** `X-Timestamp` в формате, который ждёт сервер (RFC 1123 в GMT). */
        private fun timestamp(nowSeconds: Long): String =
            SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(nowSeconds * 1000L))

        private const val TIMESTAMP_PATTERN =
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'"

        /** Байты → верхний hex без `java.util.HexFormat` (API 34 в core-network нет). */
        private fun ByteArray.toHexUpperCase(): String = buildString(size * 2) {
            for (byte in this@toHexUpperCase) {
                val value = byte.toInt() and 0xFF
                append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
            }
        }

        private const val HEX_DIGITS = "0123456789ABCDEF"
        // --- DRM Sec-MS-GEC v2 -------------------------------------------------------------
        //
        // Ниже — посимвольный перенос `edge_tts/drm.py` (rany2/edge-tts). Это НЕ «похожий
        // хеш»: сервер сверяет токен побитово, и любая вольность в константах или в порядке
        // операций даёт `403` на апгрейде сокета (то есть полную тишину вместо голоса).
        //
        // Вся арифметика — 32-битная: каждое значение маскируется [U32], а «вращение»
        // делается трюком `e * (2^32 + 1)` в 64 битах, где логический сдвиг вправо даёт
        // ROTR(e, n) в младших 32 битах. Kotlin `Long` переполняется ровно так же, как
        // 64-битное беззнаковое значение, поэтому битовый узор совпадает с Python.

        /** Маска 32-битного слова. */
        private const val U32: Long = 0xFFFFFFFFL

        /** Смещение эпохи Windows file time (1601-01-01) относительно Unix, секунды. */
        private const val WIN_EPOCH: Long = 11_644_473_600L

        /** 100-наносекундные тики: 1 секунда = 10 000 000 тиков. */
        private const val TICKS_PER_SECOND: Long = 10_000_000L

        /** Окно округления вниз — 5 минут, как у клиента Edge. */
        private const val ROUNDING_SECONDS: Long = 300L

        private val SHA256_INITIAL = longArrayOf(
            0x6A09E667L, 0xBB67AE85L, 0x3C6EF372L, 0xA54FF53AL,
            0x510E527FL, 0x9B05688CL, 0x1F83D9ABL, 0x5BE0CD19L,
        )

        /** Начальное состояние сжатия для префикса токена (первые 7 байт). */
        private val SHA256_STATE = longArrayOf(
            0xFD9FBEA2L, 0x91243CE7L, 0xBE499094L, 0xCD2E1E0AL,
            0x6C006F49L, 0x04BD35ABL, 0xFEB0A7E4L, 0x03EFFBD2L,
        )

        private val SHA256_SCHEDULE = longArrayOf(
            0xEB24BF9EL, 0x1558691FL, 0xA0C09EE2L, 0x0CFA32BAL,
            0xA622EE8CL, 0xD1DD9491L, 0xC0DD25A0L, 0xF9E70AB6L,
            0xF0C31573L, 0x7058CE4EL, 0x7188E606L, 0x00E5AB33L,
            0xD258B060L, 0x72766564L,
        )

        private val SHA256_ROUND_CONSTANTS = longArrayOf(
            0x428A2F98L, 0x71374491L, 0xB5C0FBCFL, 0xE9B5DBA5L,
            0x3956C25BL, 0x59F111F1L, 0x923F82A4L, 0xAB1C5ED5L,
            0xD807AA98L, 0x12835B01L, 0x243185BEL, 0x550C7DC3L,
            0x72BE5D74L, 0x80DEB1FEL, 0x9BDC06A7L, 0xC19BF174L,
            0xE49B69C1L, 0xEFBE4786L, 0x0FC19DC6L, 0x240CA1CCL,
            0x2DE92C6FL, 0x4A7484AAL, 0x5CB0A9DCL, 0x76F988DAL,
            0x983E5152L, 0xA831C66DL, 0xB00327C8L, 0xBF597FC7L,
            0xC6E00BF3L, 0xD5A79147L, 0x06CA6351L, 0x14292967L,
            0x27B70A85L, 0x2E1B2138L, 0x4D2C6DFCL, 0x53380D13L,
            0x650A7354L, 0x766A0ABBL, 0x81C2C92EL, 0x92722C85L,
            0xA2BFE8A1L, 0xA81A664BL, 0xC24B8B70L, 0xC76C51A3L,
            0xD192E819L, 0xD6990624L, 0xF40E3585L, 0x106AA070L,
            0x19A4C116L, 0x1E376C08L, 0x2748774CL, 0x34B0BCB5L,
            0x391C0CB3L, 0x4ED8AA4AL, 0x5B9CCA4FL, 0x682E6FF3L,
            0x748F82EEL, 0x78A5636FL, 0x84C87814L, 0x8CC70208L,
            0x90BEFFFAL, 0xA4506CEBL, 0xBEF9A3F7L, 0xC67178F2L,
        )

        /** Поправка раунда 14: префиксное состояние уже «съело» эту константу. */
        private const val SHA256_ADJUSTMENT: Long = 0xC9DC06A7L

        private const val SHA256_SIGMA0_ADJUSTMENT: Long = 0x059C0B80L

        private const val SHA256_SIGMA1_ADJUSTMENT: Long = 0x000B92C0L

        /** Константы раундов для продолжения сжатия с 14-го раунда. */
        private val SHA256_BLOCK_CONSTANTS: LongArray = LongArray(64) { index ->
            if (index == 14) SHA256_ADJUSTMENT else SHA256_ROUND_CONSTANTS[index]
        }

        /** σ0 по FIPS 180-4: три сдвига и два вращения, маска один раз в конце. */
        private fun smallSigma0(value: Long): Long =
            ((value ushr 7) xor (value shl 25) xor (value ushr 18) xor
                (value shl 14) xor (value ushr 3)) and U32

        /** σ1 по FIPS 180-4. */
        private fun smallSigma1(value: Long): Long =
            ((value ushr 17) xor (value shl 15) xor (value ushr 19) xor
                (value shl 13) xor (value ushr 10)) and U32

        /** Σ1 (e) через ROTR в 64-битном произведении `e * 0x100000001`. */
        private fun bigSigma1(value: Long): Long {
            val rotated = value * 0x100000001L
            return ((rotated ushr 6) xor (rotated ushr 11) xor (rotated ushr 25)) and U32
        }

        /** Σ0 (a) тем же приёмом. */
        private fun bigSigma0(value: Long): Long {
            val rotated = value * 0x100000001L
            return ((rotated ushr 2) xor (rotated ushr 13) xor (rotated ushr 22)) and U32
        }

        /**
         * 64 раунда сжатия SHA-256, начиная с [firstRound].
         *
         * `firstRound = 14` — продолжение уже начатого блока: раунды 0..13 посчитаны
         * префиксным состоянием [SHA256_STATE], и их результат зашит в константы.
         */
        private fun sha256Rounds(state: LongArray, words: LongArray, firstRound: Int): LongArray {
            var a = state[0]
            var b = state[1]
            var c = state[2]
            var d = state[3]
            var e = state[4]
            var f = state[5]
            var g = state[6]
            var h = state[7]
            val constants = if (firstRound == 14) SHA256_BLOCK_CONSTANTS else SHA256_ROUND_CONSTANTS
            for (index in firstRound until 64) {
                val t1 = (h + bigSigma1(e) + (g xor (e and (f xor g))) +
                    constants[index] + words[index]) and U32
                val t2 = (bigSigma0(a) + ((a and b) or (c and (a or b)))) and U32
                h = g
                g = f
                f = e
                e = (d + t1) and U32
                d = c
                c = b
                b = a
                a = (t1 + t2) and U32
            }
            return longArrayOf(a, b, c, d, e, f, g, h)
        }

        /** Расширение расписания сообщения: слова 16..63 из предыдущих. */
        private fun expandSchedule(words: LongArray, firstWord: Int) {
            for (index in firstWord until 64) {
                val x = words[index - 2]
                val y = words[index - 15]
                val sigma1 = (x ushr 17) xor (x shl 15) xor (x ushr 19) xor (x shl 13) xor (x ushr 10)
                val sigma0 = (y ushr 7) xor (y shl 25) xor (y ushr 18) xor (y shl 14) xor (y ushr 3)
                words[index] = (sigma1 + words[index - 7] + sigma0 + words[index - 16]) and U32
            }
        }
        /**
         * Начальное состояние сжатия для первых 7 байт токена.
         *
         * Первые 7 байт — это `<тики>` (19 цифр не влезают в два слова), поэтому клиент
         * Edge «прогревает» блок вручную: 3 байта + 4 байта в слова 14 и 15, затем раунды
         * с 14-й константой. Считать это заново с нуля нельзя — токен не совпадёт.
         */
        private fun sha256Init(data: ByteArray): LongArray {
            require(data.size >= 7) { "Token salt must contain at least seven bytes" }
            val words = LongArray(64)
            words[14] = (((data[0].toInt() and 0xFF) shl 16) or
                ((data[1].toInt() and 0xFF) shl 8) or
                (data[2].toInt() and 0xFF)).toLong()
            words[15] = (((data[3].toInt() and 0xFF) shl 24) or
                ((data[4].toInt() and 0xFF) shl 16) or
                ((data[5].toInt() and 0xFF) shl 8) or
                (data[6].toInt() and 0xFF)).toLong()
            for (index in 16 until 30) {
                var value = SHA256_SCHEDULE[index - 16]
                if (index - 2 >= 14) {
                    value += if (index == 16) {
                        smallSigma1(words[index - 2]) xor SHA256_SIGMA1_ADJUSTMENT
                    } else {
                        smallSigma1(words[index - 2])
                    }
                }
                if (index - 7 >= 14) value += words[index - 7]
                if (index - 15 >= 14) {
                    value += if (index == 29) {
                        smallSigma0(words[index - 15]) xor SHA256_SIGMA0_ADJUSTMENT
                    } else {
                        smallSigma0(words[index - 15])
                    }
                }
                words[index] = value and U32
            }
            words[30] = (smallSigma1(words[28]) + words[23] + smallSigma0(words[15]) +
                words[14] + SHA256_ADJUSTMENT - SHA256_ROUND_CONSTANTS[14]) and U32
            expandSchedule(words, 31)
            val working = sha256Rounds(SHA256_STATE, words, 14)
            return LongArray(8) { index -> (SHA256_INITIAL[index] + working[index]) and U32 }
        }

        /** Полный SHA-256 от [data] (не меньше 7 байт) — 32 байта digest. */
        private fun sha256Digest(data: ByteArray): ByteArray {
            require(data.size >= 7) { "Token salt must contain at least seven bytes" }
            val length = 57 + data.size
            val padding = ((55 - length) % 64 + 64) % 64
            val padded = ByteArrayOutputStream(length + 8)
            padded.write(data, 7, data.size - 7)
            padded.write(0x80)
            repeat(padding) { padded.write(0) }
            val bitLength = length.toLong() * 8L
            for (shift in 56 downTo 0 step 8) {
                padded.write(((bitLength ushr shift) and 0xFFL).toInt())
            }
            val block = padded.toByteArray()
            var state = sha256Init(data.copyOfRange(0, 7))
            var offset = 0
            while (offset < block.size) {
                val words = LongArray(64)
                for (index in 0 until 16) {
                    val base = offset + index * 4
                    words[index] = (((block[base].toInt() and 0xFF) shl 24) or
                        ((block[base + 1].toInt() and 0xFF) shl 16) or
                        ((block[base + 2].toInt() and 0xFF) shl 8) or
                        (block[base + 3].toInt() and 0xFF)).toLong()
                }
                expandSchedule(words, 16)
                val working = sha256Rounds(state, words, 0)
                state = LongArray(8) { index -> (state[index] + working[index]) and U32 }
                offset += 64
            }
            return ByteArray(32) { index ->
                val word = state[index / 4]
                ((word ushr (24 - 8 * (index % 4))) and 0xFFL).toByte()
            }
        }

        /** Вращение 32-битного слова влево на 16 (в CS64 это «swap halves»). */
        private fun rol16(value: Long): Long {
            val masked = value and U32
            return ((masked shl 16) or (masked ushr 16)) and U32
        }

        /**
         * Данные → 32-битные слова little-endian, дополненные нулём до чётного количества.
         *
         * Чётность важна: CS64 сворачивает слова ПАРАМИ, и непарный хвост сдвинул бы
         * ключи (key_a/key_b) относительно данных — токен стал бы неверным.
         */
        private fun wordsLe4Even(data: ByteArray): LongArray {
            val rem = ((4 - (data.size % 4)) % 4)
            val padded = data.size + rem
            if (padded == 0) return LongArray(0)
            val count = padded / 4
            val even = if (count % 2 == 0) count else count + 1
            val words = LongArray(even)
            for (index in 0 until count) {
                val base = index * 4
                // Хвост добивается нулями: последняя группа может быть короче четырёх байт.
                words[index] = (byteAt(data, base) or
                    (byteAt(data, base + 1) shl 8) or
                    (byteAt(data, base + 2) shl 16) or
                    (byteAt(data, base + 3) shl 24)).toLong() and U32
            }
            return words
        }

        /** Байт или 0 за границей массива (дополнение нулями, как у `struct.unpack`). */
        private fun byteAt(data: ByteArray, index: Int): Int =
            if (index < data.size) data[index].toInt() and 0xFF else 0

        /** 32-битное слово → hex его little-endian байтов (порядок как в `struct.pack("<I")`). */
        private fun leHex32(value: Long): String = buildString(8) {
            for (index in 0..3) {
                val byte = ((value ushr (8 * index)) and 0xFFL).toInt()
                append(HEX_DIGITS[byte ushr 4]).append(HEX_DIGITS[byte and 0x0F])
            }
        }
        /** Коэффициенты CS64: (коэффициент, смещение), 14 пар — «соль» схемы. */
        private val CS64_COEFFICIENTS: Array<LongArray> = arrayOf(
            longArrayOf(0xE82E91B1L, 0xB32700D0L),
            longArrayOf(0x44DE24C6L, 0x88DFF51DL),
            longArrayOf(0x3BEB65BCL, 0x29514038L),
            longArrayOf(0xC89C5933L, 0xB328CBDFL),
            longArrayOf(0x8D1EC211L, 0xD405B4D5L),
            longArrayOf(0x314EF5D5L, 0x26E55F3CL),
            longArrayOf(0xE92491B1L, 0x607801C6L),
            longArrayOf(0xF0A5B9D5L, 0x82B2C1FBL),
            longArrayOf(0x06064CF3L, 0xFA51A3DAL),
            longArrayOf(0xDA79FA60L, 0x207CC464L),
            longArrayOf(0xBED20560L, 0x3C3C74F3L),
            longArrayOf(0x89F9C9F8L, 0xD2D14B61L),
            longArrayOf(0x35FA38C6L, 0xBB04A2C1L),
            longArrayOf(0xCB49E3C6L, 0xC5626167L),
        )

        private const val CS64_FACTOR: Long = 0x82893933L

        private const val CS64_ADJUSTMENT0: Long = 0xF2F90B1AL

        private const val CS64_ADJUSTMENT1: Long = 0x0B1A0000L

        /** 32-битное слово little-endian из [bytes] по смещению [offset]. */
        private fun le32(bytes: ByteArray, offset: Int): Long =
            (((bytes[offset].toInt() and 0xFF)) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)).toLong() and U32

        /**
         * CS64-свёртка данных с ключами из SHA-256 digest: две 32-битные «контрольные суммы».
         *
         * Это не стандартный хеш: два независимых аккумулятора ([swapAcc] и [acc]) считаются
         * по ПАРАМ слов с чередованием ключей `key_a`/`key_b` и цепочкой вращений с
         * умножением. Порядок операций здесь и есть алгоритм — переставлять нельзя.
         */
        private fun cs64Digest(data: ByteArray, digest: ByteArray): LongArray {
            val keyA = le32(digest, 0) or 1L
            val keyB = le32(digest, 4) or 1L
            val factors = longArrayOf((keyA * CS64_FACTOR) and U32, (keyB * CS64_FACTOR) and U32)
            val words = wordsLe4Even(byteArrayOf(0) + data)
            val terms = ArrayList<Long>(CS64_COEFFICIENTS.size + words.size)
            val offsets = ArrayList<Long>(CS64_COEFFICIENTS.size + words.size)
            CS64_COEFFICIENTS.forEachIndexed { index, pair ->
                terms.add((pair[0] * factors[index % 2]) and U32)
                offsets += pair[1]
            }
            words.forEachIndexed { index, word ->
                var term = (word * (if (index % 2 == 0) keyA else keyB)) and U32
                var offset = (rol16(word) * (if (index % 2 == 0) 0xEF0569FBL else 0xC31713DBL)) and U32
                if (index == 0) {
                    term = (term + CS64_ADJUSTMENT0 * factors[0]) and U32
                    offset = (offset + CS64_ADJUSTMENT1) and U32
                }
                terms += term
                offsets += offset
            }
            var swapAcc = 0L
            var swapLast = 0L
            var state = 0L
            var acc = 0L
            var index = 0
            while (index < terms.size) {
                val x = terms[index]
                val y = terms[index + 1]
                var t = rol16((offsets[index] + x) and U32)
                var q = (t * 0x76F50F34L) and U32
                swapAcc = (swapAcc + ((rol16(q) * 0xC3EFEA97L + q) and U32)) and U32
                t = rol16((offsets[index + 1] + y) and U32)
                q = (t * 0x1881CE3CL) and U32
                swapLast = (rol16(q) * 0x35BD1EC9L + q) and U32
                swapAcc = (swapAcc + swapLast) and U32

                state = (state * keyA + x) and U32
                state = (rol16(state) * 0xCF98B111L) and U32
                state = (rol16(state) * 0x87085B9FL) and U32
                state = (rol16(state) * 0x12CEB96DL) and U32
                state = (rol16(state) * 0x257E1D83L) and U32
                acc = (acc + state) and U32

                var u = (state * keyB + y) and U32
                u = (rol16(u) * 0xA27416F5L) and U32
                u = (rol16(u) * 0xD38396FFL) and U32
                state = (rol16((rol16(u) * 0x7C932B89L) and U32) * 0xBFA49F69L) and U32
                acc = (acc + state) and U32
                index += 2
            }
            return longArrayOf(swapLast xor state, swapAcc xor acc)
        }

        /**
         * Токен `Sec-MS-GEC` v2 для момента [unixSeconds].
         *
         * Формат `"<16 hex>-<64 hex>"`: CS64 от `"<тики><TRUSTED_CLIENT_TOKEN>"` и SHA-256 от
         * той же строки. Тики — Windows file time в 100-нс интервалах, округлённые ВНИЗ до
         * 5 минут: сервер допускает расхождение часов в пределах окна, поэтому токен можно
         * кэшировать на 5 минут (но кэш здесь не нужен — генерация дешевле запроса).
         *
         * @return токен ровно в том виде, в каком его ждёт `Sec-MS-GEC` в query.
         */
        fun generateSecMsGecV2(unixSeconds: Long = System.currentTimeMillis() / 1000L): String {
            val salt = saltFor(unixSeconds)
            val digest = sha256Digest(salt)
            val cs64 = cs64Digest(salt, digest)
            return leHex32(cs64[0]) + leHex32(cs64[1]) + "-" + digest.toHexUpperCase()
        }

        /**
         * Строка, которую хеширует DRM: `"<тики><TRUSTED_CLIENT_TOKEN>"` в ASCII.
         *
         * Вынесена отдельно, потому что это единственная часть токена, которую можно
         * проверить глазами: для `1700000000` она равна
         * `1334447340000000006A5AA1D4EAFF4E9FB37E23D68491D6F4` (тики = 133444734000000000).
         */
        fun saltFor(unixSeconds: Long): ByteArray {
            val seconds = unixSeconds + WIN_EPOCH
            val rounded = seconds - seconds % ROUNDING_SECONDS
            // Целочисленная арифметика вместо float: Python округляет double до целого, но
            // (секунды * 10^7) всегда кратно 128, поэтому double-путь даёт ровно то же число.
            val ticks = rounded * TICKS_PER_SECOND
            return (ticks.toString() + TRUSTED_CLIENT_TOKEN).toByteArray(Charsets.US_ASCII)
        }




    }
}
