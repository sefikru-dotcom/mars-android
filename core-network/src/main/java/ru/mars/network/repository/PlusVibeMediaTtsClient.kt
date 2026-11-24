package ru.mars.network.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import ru.mars.network.http.HttpClients
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Асинхронный синтез PlusVibe: `POST /api/media/generate` → `GET /api/media/jobs/{id}` →
 * скачивание готового аудио по ссылке из `resultUrls`.
 *
 * ## Почему именно этот маршрут основной
 * Живой замер (см. `plusvibe-tts-live-20261118.md`): асинхронный путь дал 21 успешную
 * задачу из 21, списание совпало с котировкой до копейки, аудио всегда целое. Синхронный
 * `/v1/audio/speech` работает быстрее, но **3 из 10 вызовов вернули обрезанный фрагмент**
 * фразы — как единственный путь он непригоден ([OpenAiSpeechTtsClient] остаётся только
 * для сравнения и старых тестов).
 *
 * ## Что нужно знать про контракт (проверено живьём)
 * - `POST` может БЛОКИРОВАТЬСЯ 4–32 с: у части моделей синтез выполняется внутри запроса.
 *   Поэтому `readTimeout` — 180 с, а `callTimeout` — 200 с; с дефолтными 10 с OkHttp
 *   клиент рвал бы соединение на здоровом ответе;
 * - ответ `202` содержит `jobId` и `status`; окончательная цена — только в `job.priceRub`
 *   после `success`, а `x-pv-cost-rub` в ответе `POST` предварительная;
 * - `resultUrls` — ПУБЛИЧНЫЕ ссылки: при скачивании авторизация не нужна, `Content-Type`
 *   корректный. Ссылки живут ~5 суток (`resultsExpireAt`), после — `410`. В массиве лежат
 *   и аудио, и служебный JSON со словами, а подписанная форма ссылки
 *   (`…/api/media/file/<id>/0?sig=…`) вообще без расширения — поэтому кандидат считается
 *   аудио только после проверки тела ([looksLikeAudioPayload]), а неудачная ссылка не
 *   обрывает попытку: берётся следующая ([audioCandidates]);
 * - `max_price_rub` — предохранитель ДО запуска: превышение даёт `422
 *   media_max_price_exceeded` **без списания**. Ставим всегда;
 * - лимит длины у моделей разный (4096 / 5000 / 15000), а котировка длину не проверяет,
 *   поэтому текст режет КЛИЕНТ — по лимиту ВЫБРАННОЙ модели ([promptLimitFor]).
 * - `opts.voice_id` — ВТОРОЙ способ выбрать голос: свободная строка с id голоса из
 *   библиотеки ElevenLabs (русские Larisa/Kate живут только там). Проверено живым
 *   запросом 18.11.2026: `{"engine":"turbo","voice_id":"AB9XsbSA4eLG12t2myjN",
 *   "language_code":"ru","output_format":"mp3"}` принимается (`202`, `effectiveOpts`
 *   подтверждает id), но ЗАДАЧА НЕ ЗАВЕРШАЕТСЯ — 5 из 5 попыток остались в `processing`
 *   через 20–25 с, `priceRub=null`, `failMsg=null`, списания нет. Контрольный вызов тем же
 *   телом с `voice:"Alice"` в это же время дал аудио за 13,7 с, то есть канал живой, а
 *   ломается именно библиотечный голос. Поэтому `voice_id` — рабочая ручка контракта, но
 *   НЕ гарантия звука: вызывающий слой обязан ограничить ожидание
 *   ([ServerTtsVoiceProfile.waitBudgetMs]) и уйти в нейро-резерв.
 *
 * ## Задержка: что реально можно сократить на клиенте
 * `POST` блокируется, пока модель синтезирует (1,9–2,7 с у gpt-4o-mini, 13,7 с у ElevenLabs
 * под нагрузкой), и часто возвращает уже готовую задачу — тогда первый же `GET` отдаёт
 * `success` (живой замер: 1 опрос на 3 фразы). Поэтому первый опрос идёт СРАЗУ после `POST`
 * (без паузы), а первые [EARLY_POLL_WINDOW_MS] ожидания опрашиваются шагом
 * [EARLY_POLL_INTERVAL_MS] = 250 мс, и только потом — [DEFAULT_POLL_INTERVAL_MS] = 1,2 с.
 * Это снимает «квантование» до 1,2 с у задач, которые дозревают сразу после ответа: звук
 * начинается на 0,25–1 с раньше, а число запросов растёт не больше чем на 4 на реплику.
 * Стриминга у провайдера нет вовсе: TTFB синхронной ручки равен полному времени.
 *
 * ## Отмена
 * Каждый HTTP-вызов идёт через [execute] на `enqueue`: отмена корутины (перебивание,
 * уход с экрана, [ru.mars.mobile.ServerTtsSpeechEngine.stop]) вызывает `Call.cancel()` и
 * рвёт сокет немедленно. Ожидание следующего опроса — `delay`, который тоже отменяем.
 * Задача на стороне провайдера при этом доигрывает (отменить её API не даёт), но ни звука,
 * ни ожидания на клиенте уже нет.
 *
 * ## Чего клиент НЕ делает
 * - не бросает исключений: любой сбой — [ServerTtsResult.Unavailable] с короткой причиной;
 * - не передаёт ключ и текст реплики в причину отказа (причина уходит в логи и тесты);
 * - не отдаёт не-аудио: JSON со словами или HTML ошибки не станут «звуком» — лучше честный
 *   `empty_result`, чем мусор в проигрывателе;
 * - не кеширует аудио: кеш — забота вызывающего слоя (ссылки живут 5 суток).
 *
 * @param baseUrlProvider база провайдера. Принимается и `https://plusvibeapi.ru`, и
 *   `https://plusvibeapi.ru/v1`: сегмент `v1` отбрасывается ([mediaRoot]), потому что
 *   media-ручки живут в корне, а не под `/v1`. Открытый `http` (кроме петли) отклоняется
 *   ([isAllowedBase]): ключ в заголовке `Authorization` нельзя отдавать в сеть открытым.
 * @param apiKeyProvider ключ провайдера. Лямбда, а не значение: ключ приезжает из настроек
 *   уже после создания клиента.
 * @param httpClient транспорт; таймауты заданы в [defaultHttpClient], подменяются в тестах.
 * @param pollIntervalMs шаг опроса задачи. 1,2 с: провайдер советует 3 с, но задача обычно
 *   готова раньше, а лишняя секунда — это слышимая пауза в речи.
 * @param earlyPollIntervalMs шаг опроса в первые [EARLY_POLL_WINDOW_MS] жизни задачи.
 *   Живой замер: задача, дозревшая сразу после `POST`, ждала бы следующего шага 1,2 с —
 *   это чистая пауза в речи, а лишние 3–4 `GET` стоят провайдеру дешевле, чем тишина.
 * @param maxWaitMs потолок ожидания задачи после создания (провайдер объявляет 90 с).
 * @param maxPriceRub потолок цены одной задачи. `<= 0` — поле не отправляется (не рекомендуется).
 * @param sleeper ожидание между опросами (в тестах — мгновенное).
 * @param nowMs часы для потолка ожидания (в тестах подменяются).
 */
class PlusVibeMediaTtsClient(
    private val baseUrlProvider: () -> String? = { DEFAULT_BASE_URL },
    private val apiKeyProvider: () -> String? = { null },
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val earlyPollIntervalMs: Long = EARLY_POLL_INTERVAL_MS,
    private val maxWaitMs: Long = DEFAULT_MAX_WAIT_MS,
    private val maxPriceRub: Double = DEFAULT_MAX_PRICE_RUB,
    private val sleeper: suspend (Long) -> Unit = { millis -> delay(millis) },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : PaidTtsClient {

    /**
     * Синтез одной реплики: постановка задачи, ожидание, скачивание.
     *
     * @return готовые байты либо отказ. `speed`/`instructions` в media-контракте полей не
     *   имеют: темп там задаётся моделью, а манеру чтения задаёт голос. Параметры приняты
     *   ради общего порта [PaidTtsClient] и намеренно не уходят в тело запроса — отправить
     *   их как «лишние» опции значило бы получить `422` при `strict` или тихую потерю без него.
     */
    override suspend fun synthesize(
        text: String,
        voice: String,
        model: String,
        speed: Float,
        instructions: String?,
        voiceId: String?,
    ): ServerTtsResult {
        val apiKey = apiKeyProvider()?.trim().orEmpty()
        val baseUrl = baseUrlProvider()?.trim().orEmpty()
        if (apiKey.isEmpty() || baseUrl.isEmpty()) return ServerTtsResult.Unavailable(REASON_NO_KEY)
        val root = mediaRoot(baseUrl) ?: return ServerTtsResult.Unavailable(REASON_TRANSPORT)
        // Ключ едет в заголовке Authorization: по открытому http его прочитает любой в сети,
        // поэтому такой базе отказываем ДО запроса (исключение — петля, см. [isAllowedBase]).
        if (!isAllowedBase(root)) return ServerTtsResult.Unavailable(REASON_INSECURE_BASE)
        val resolvedModel = model.trim().ifEmpty { DEFAULT_MODEL }
        // Библиотечный голос (`voice_id`) едет ОТДЕЛЬНЫМ полем и вытесняет именованный:
        // отправить оба значит дать провайдеру выбор, и он выберет `voice` (проверено живым
        // запросом — `effectiveOpts` эхом подтвердил и `voice`, и `voice_id`, а задача висела).
        val resolvedVoiceId = voiceId?.trim()?.takeIf { it.isNotEmpty() }
        val resolvedVoice = voice.trim().ifEmpty { DEFAULT_VOICE }
        // Лимит длины у моделей РАЗНЫЙ (4096/5000/15000), а котировка длину не проверяет:
        // режем по лимиту ВЫБРАННОЙ модели, а не по самому строгому из всех.
        val prompt = preparePrompt(text, promptLimitFor(resolvedModel))
            ?: return ServerTtsResult.Unavailable(REASON_EMPTY_TEXT)
        return withContext(Dispatchers.IO) {
            try {
                when (
                    val started = startJob(
                        root = root,
                        apiKey = apiKey,
                        prompt = prompt,
                        voice = resolvedVoice,
                        voiceId = resolvedVoiceId,
                        model = resolvedModel,
                    )
                ) {
                    is Envelope.Failed -> started.result
                    is Envelope.Job -> started.audio ?: pollJob(root, apiKey, started.id)
                }
            } catch (cancelled: CancellationException) {
                // Перебивание/уход с экрана — не «отказ синтеза»: корутина обязана умереть.
                throw cancelled
            } catch (error: Exception) {
                ServerTtsResult.Unavailable(REASON_TRANSPORT)
            }
        }
    }

    // --- шаги контракта ---------------------------------------------------------------------

    /** Исход `POST generate`: задача создана (возможно, сразу с аудио) или отказ. */
    private sealed interface Envelope {
        class Job(val id: String, val audio: ServerTtsResult.Audio?) : Envelope
        class Failed(val result: ServerTtsResult.Unavailable) : Envelope
    }

    /**
     * `POST /api/media/generate`.
     *
     * Часть моделей отдаёт результат прямо в ответе (задача успевала завершиться, пока
     * запрос висел), поэтому `resultUrls` проверяются и здесь: лишний опрос — это лишняя
     * секунда тишины.
     */
    private suspend fun startJob(
        root: String,
        apiKey: String,
        prompt: String,
        voice: String,
        voiceId: String?,
        model: String,
    ): Envelope {
        val url = runCatching {
            root.toHttpUrl().newBuilder().addPathSegments(PATH_GENERATE).build().toString()
        }.getOrNull() ?: return Envelope.Failed(ServerTtsResult.Unavailable(REASON_TRANSPORT))
        val body = buildBody(prompt, voice, model, maxPriceRub, voiceId)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val reply = execute(request) ?: return Envelope.Failed(ServerTtsResult.Unavailable(REASON_TRANSPORT))
        if (reply.code !in HTTP_OK..HTTP_ACCEPTED_RANGE_END) {
            return Envelope.Failed(ServerTtsResult.Unavailable(reasonFor(reply), reply.code))
        }
        val map = TolerantJson.parseMap(reply.body.toString(Charsets.UTF_8))
        // 422 с телом `{"code":"media_max_price_exceeded"}` приходит и на 2xx-кодах не бывает,
        // но `ok:false` без кода тоже возможно — тогда честный «invalid_response».
        if (TolerantJson.bool(map, "ok", default = true).not()) {
            return Envelope.Failed(
                ServerTtsResult.Unavailable(
                    reason = TolerantJson.string(map, "code") ?: REASON_INVALID_RESPONSE,
                    httpCode = reply.code,
                ),
            )
        }
        val direct = audioFromResultUrls(root, map)
        val jobId = TolerantJson.string(map, "jobId")
        if (jobId == null) {
            return if (direct != null) {
                Envelope.Job(id = "", audio = direct)
            } else {
                Envelope.Failed(ServerTtsResult.Unavailable(REASON_INVALID_RESPONSE, reply.code))
            }
        }
        return Envelope.Job(id = jobId, audio = direct)
    }

    /**
     * Опрос задачи до `success`/`failed`/потолка ожидания.
     *
     * Потолок — свой таймер, а не `maxWaitSec` провайдера: сервер может ответить «ещё
     * processing» и через 90 с, а речь ждать столько не может. Таймаут НЕ отменяет задачу
     * (API этого не даёт) — вызывающий слой уйдёт в резерв, а результат останется в истории.
     */
    private suspend fun pollJob(root: String, apiKey: String, jobId: String): ServerTtsResult {
        val url = runCatching {
            root.toHttpUrl().newBuilder()
                .addPathSegments(PATH_JOBS)
                .addPathSegment(jobId)
                .build()
                .toString()
        }.getOrNull() ?: return ServerTtsResult.Unavailable(REASON_TRANSPORT)
        val startedAt = nowMs()
        val deadline = startedAt + maxWaitMs
        while (true) {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .get()
                .build()
            val reply = execute(request)
                ?: return ServerTtsResult.Unavailable(REASON_TRANSPORT)
            if (reply.code !in HTTP_OK..HTTP_ACCEPTED_RANGE_END) {
                return ServerTtsResult.Unavailable(reasonFor(reply), reply.code)
            }
            val map = TolerantJson.parseMap(reply.body.toString(Charsets.UTF_8))
                ?: return ServerTtsResult.Unavailable(REASON_INVALID_RESPONSE, reply.code)
            // `{"ok":false,"code":"media_result_expired"}` провайдер может отдать и с 2xx:
            // без этой проверки клиент поллил бы такую задачу до своего потолка ожидания.
            if (!TolerantJson.bool(map, "ok", default = true)) {
                return ServerTtsResult.Unavailable(
                    reason = TolerantJson.string(map, "code") ?: REASON_INVALID_RESPONSE,
                    httpCode = reply.code,
                )
            }
            when (statusOf(map)) {
                Status.SUCCESS -> {
                    return audioFromResultUrls(root, map)
                        ?: ServerTtsResult.Unavailable(REASON_EMPTY_RESULT)
                }
                Status.FAILED -> {
                    return ServerTtsResult.Unavailable(
                        reason = TolerantJson.string(map, "errorCode")
                            ?: TolerantJson.string(map, "failMsg")?.let { REASON_JOB_FAILED }
                            ?: REASON_JOB_FAILED,
                        httpCode = reply.code,
                    )
                }
                Status.PENDING -> Unit
            }
            if (nowMs() >= deadline) return ServerTtsResult.Unavailable(REASON_TIMEOUT)
            // Отменяемое ожидание: перебивание не должно ждать шаг опроса.
            sleeper(pollStepMs(nowMs() - startedAt))
        }
    }

    /**
     * Шаг опроса в зависимости от того, сколько задача уже живёт.
     *
     * Первые [EARLY_POLL_WINDOW_MS] — частый шаг ([earlyPollIntervalMs]): задача, дозревшая
     * сразу после `POST`, находится почти без задержки. Дальше — обычный [pollIntervalMs]:
     * долгие задачи всё равно идут десятками секунд, и частый опрос там ничего не ускоряет,
     * только тратит запросы.
     */
    private fun pollStepMs(elapsedMs: Long): Long =
        if (elapsedMs < EARLY_POLL_WINDOW_MS) {
            earlyPollIntervalMs.coerceAtLeast(1L)
        } else {
            pollIntervalMs.coerceAtLeast(1L)
        }

    /**
     * Скачивание аудио по ссылке из `resultUrls` (авторизация НЕ нужна — ссылка публичная).
     *
     * Тело проверяется на «это вообще аудио» ([looksLikeAudioPayload]): провайдер кладёт в
     * `resultUrls` и служебный JSON со словами/таймкодами, а подписанная форма ссылки
     * (`…/api/media/file/<id>/0?sig=…`) не имеет расширения — по одному имени файла их не
     * различить. Отдать JSON как `audio/mpeg` значит дать проигрывателю мусор вместо речи,
     * поэтому не-аудио ответ считается неудачей и очередь переходит к следующей ссылке.
     *
     * @return байты аудио либо `null` (не аудио, 4xx/5xx, пусто, слишком большое тело).
     */
    private suspend fun download(root: String, url: String): ServerTtsResult.Audio? {
        val absolute = when {
            url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> url
            // Относительная ссылка возможна у другого прокси-слоя: достраиваем от корня.
            else -> runCatching { root.toHttpUrl().resolve(url)?.toString() }.getOrNull()
        } ?: return null
        val target = runCatching { absolute.toHttpUrl() }.getOrNull() ?: return null
        val request = Request.Builder()
            .url(target)
            .header("Accept", "audio/*")
            .get()
            .build()
        val reply = execute(request) ?: return null
        if (reply.code !in HTTP_OK..HTTP_ACCEPTED_RANGE_END) return null
        if (reply.body.isEmpty()) return null
        if (!looksLikeAudioPayload(reply.contentType, reply.body)) return null
        return ServerTtsResult.Audio(reply.body, audioContentType(reply.contentType, absolute))
    }

    /**
     * Аудио из `resultUrls` задачи, если оно уже готово.
     *
     * В массиве лежат и аудио, и служебный JSON (у ElevenLabs `resultUrls[1]` — слова с
     * таймкодами), поэтому кандидаты перебираются в порядке [audioCandidates]: сначала
     * ссылки с аудио-расширением, затем прочие, документы — последними. Ссылка, которая не
     * отдала аудио (JSON, 410, HTML), не обрывает попытку — берётся следующая.
     */
    private suspend fun audioFromResultUrls(root: String, map: Map<String, Any?>?): ServerTtsResult.Audio? {
        val urls = TolerantJson.stringListOrNull(map, "resultUrls").orEmpty()
        for (candidate in audioCandidates(urls)) {
            download(root, candidate)?.let { return it }
        }
        return null
    }

    // --- тело запроса и разбор ответа -------------------------------------------------------

    /** Статус задачи: незнакомое значение считается «ещё идёт», а не успехом. */
    private enum class Status { PENDING, SUCCESS, FAILED }

    private fun statusOf(map: Map<String, Any?>?): Status =
        when (TolerantJson.string(map, "status")?.lowercase(Locale.US)) {
            "success", "succeeded", "completed", "done", "ready" -> Status.SUCCESS
            "failed", "error", "canceled", "cancelled", "expired" -> Status.FAILED
            else -> Status.PENDING
        }

    /**
     * Причина отказа: сначала код провайдера из тела (`media_max_price_exceeded`,
     * `text_too_long`, …) — он точнее HTTP-кода, — затем маппинг кода ответа.
     */
    private fun reasonFor(reply: Reply): String {
        val map = TolerantJson.parseMap(reply.body.toString(Charsets.UTF_8))
        val code = TolerantJson.string(map, "code")
        if (!code.isNullOrBlank()) return code
        return ServerTtsContract.reasonForHttpCode(reply.code)
    }

    /** HTTP-вызов с настоящей отменой: `Call.cancel()` по отмене корутины. */
    private suspend fun execute(request: Request): Reply? = suspendCancellableCoroutine { continuation ->
        val call: Call = httpClient.newCall(request)
        continuation.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Транспорт (таймаут, обрыв, отмена) — это `null`, вызывающий отдаст `transport`.
                if (continuation.isActive) continuation.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val reply = try {
                    response.use { received ->
                        val body = received.body
                        // Тело читается с потолком: битый апстрим не должен стоить приложению OOM.
                        val bytes = if (body == null) ByteArray(0) else readBounded(body) ?: return@use null
                        Reply(
                            code = received.code,
                            contentType = received.header("Content-Type"),
                            body = bytes,
                        )
                    }
                } catch (error: Exception) {
                    null
                }
                if (continuation.isActive) continuation.resume(reply)
            }
        })
    }

    /**
     * Тело ответа целиком, но не больше [MAX_RESPONSE_BYTES].
     *
     * `ResponseBody.bytes()` читает без ограничения: одна ссылка на «аудио», отдающая гигабайт
     * (или бесконечный поток), уронила бы приложение по памяти. Фраза речи — это десятки
     * килобайт, поэтому тело сверх потолка считается нечитаемым (`null` → `transport`).
     */
    private fun readBounded(body: ResponseBody): ByteArray? {
        val declared = body.contentLength()
        if (declared > MAX_RESPONSE_BYTES) return null
        val capacity = if (declared > 0) declared.toInt() else INITIAL_BODY_BUFFER_BYTES
        val out = java.io.ByteArrayOutputStream(capacity)
        val chunk = ByteArray(READ_CHUNK_BYTES)
        body.byteStream().use { input ->
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                if (out.size().toLong() + read > MAX_RESPONSE_BYTES) return null
                out.write(chunk, 0, read)
            }
        }
        return out.toByteArray()
    }

    /**
     * Тело запроса. Порядок полей фиксирован контрактом провайдера.
     *
     * Ровно ОДНО из полей выбора голоса уходит в `opts`:
     * - [voiceId] задан → `"voice_id"` (голос из библиотеки, включая русские);
     * - иначе → `"voice"` (имя из перечисления 21 голоса).
     *
     * Оба поля одновременно не отправляются: живой запрос с обоими провайдер принимает
     * (`effectiveOpts` эхом подтверждает и `voice`, и `voice_id`), но задача не завершается —
     * то есть второе поле не «уточняет» первое, а ломает его.
     */
    private fun buildBody(
        prompt: String,
        voice: String,
        model: String,
        priceCap: Double,
        voiceId: String? = null,
    ): String =
        buildString(prompt.length + 192) {
            append("{\"model\":").append(JsonStringEncoding.escape(model))
            append(",\"prompt\":").append(JsonStringEncoding.escape(prompt))
            append(",\"opts\":{")
            if (voiceId != null) {
                append("\"voice_id\":").append(JsonStringEncoding.escape(voiceId))
            } else {
                append("\"voice\":").append(JsonStringEncoding.escape(voice))
            }
            append(",\"language_code\":").append(JsonStringEncoding.escape(LANGUAGE_CODE))
            append(",\"output_format\":").append(JsonStringEncoding.escape(OUTPUT_FORMAT))
            // `engine` есть только у ElevenLabs; у остальных моделей это поле — лишний опт.
            engineFor(model)?.let { append(",\"engine\":").append(JsonStringEncoding.escape(it)) }
            append('}')
            if (priceCap > 0.0) {
                append(",\"max_price_rub\":").append(priceJson(priceCap))
            }
            append('}')
        }

    companion object {

        /** Корень API провайдера (media-ручки живут в корне, а не под `/v1`). */
        const val DEFAULT_BASE_URL: String = "https://plusvibeapi.ru"

        /** Модель по умолчанию: единственная проверенная по тексту, цифрам и бренду. */
        const val DEFAULT_MODEL: String = "elevenlabs-v4-tts"

        /** Голос по умолчанию: проверен обратным распознаванием на русском тексте. */
        const val DEFAULT_VOICE: String = "Alice"

        /** Движок ElevenLabs: `turbo` — быстрый вариант (6,0 с на 19 с речи). */
        const val ELEVENLABS_ENGINE: String = "turbo"

        /** Модель ElevenLabs — единственная, которой нужен опт `engine`. */
        const val MODEL_ELEVENLABS: String = "elevenlabs-v4-tts"

        /** Язык синтеза: приложение русскоязычное, голоса подбираются под него. */
        const val LANGUAGE_CODE: String = "ru"

        /** Формат результата: mp3 играет `AndroidServerTtsPlayback`. */
        const val OUTPUT_FORMAT: String = "mp3"

        /** Относительные пути контракта (добавляются к корню). */
        const val PATH_GENERATE: String = "api/media/generate"

        const val PATH_JOBS: String = "api/media/jobs"

        /**
         * Осторожный лимит длины промпта по умолчанию (и лимит `gpt-4o-mini-tts`).
         *
         * У моделей лимит РАЗНЫЙ — 4096 / 5000 / 15000 (замер на генерации, котировка длину
         * не проверяет), поэтому для конкретной модели берётся [promptLimitFor]; это значение
         * остаётся для незнакомых моделей, где меньшее безопаснее.
         */
        const val MAX_PROMPT_LENGTH: Int = 4096

        /** Лимит `elevenlabs-v4-tts` и `espeech-tts`: замерено на генерации. */
        const val PROMPT_LIMIT_5000: Int = 5000

        /** Лимит `grok-tts`: замерено на генерации. */
        const val PROMPT_LIMIT_15000: Int = 15000

        /** Потолок читаемого тела ответа: аудио фразы — десятки КБ, 16 МиБ — уже авария. */
        const val MAX_RESPONSE_BYTES: Long = 16L * 1024 * 1024

        private const val READ_CHUNK_BYTES = 8 * 1024

        private const val INITIAL_BODY_BUFFER_BYTES = 16 * 1024

        /** Шаг опроса задачи. */
        const val DEFAULT_POLL_INTERVAL_MS: Long = 1_200L

        /**
         * Шаг опроса в первые [EARLY_POLL_WINDOW_MS] жизни задачи.
         *
         * 250 мс — тот шаг, которым снят живой замер 18.11.2026: задача почти всегда готова
         * внутри `POST`, а редкий «дозревший сразу после» случай иначе ждал бы полный
         * [DEFAULT_POLL_INTERVAL_MS]. Это единственный шаг задержки, который зависит от
         * клиента: остальное время — очередь провайдера.
         */
        const val EARLY_POLL_INTERVAL_MS: Long = 250L

        /** Окно частого опроса после постановки задачи: 4 шага по 250 мс = 1 с. */
        const val EARLY_POLL_WINDOW_MS: Long = 1_000L

        /** Потолок ожидания задачи после её создания. */
        const val DEFAULT_MAX_WAIT_MS: Long = 90_000L

        /**
         * Потолок цены одной задачи, ₽.
         *
         * 30 ₽ покрывает худший законный случай профилей приложения: 5000 знаков самой
         * дорогой используемой модели (`elevenlabs-v4-tts`/`gemini` flash) ≈ 27–28 ₽, поэтому
         * предохранитель не режет нормальную речь, но не даёт улететь в бесконечную цену при
         * сбое котировки. Отказ по потолку бесплатный (422 до запуска задачи).
         *
         * Единственное исключение — `grok-tts` на своём пределе 15000 знаков (≈30,8 ₽): там
         * сработает именно потолок, отказ бесплатный. Ни один голосовой профиль его не
         * использует, поэтому потолок не поднят.
         */
        const val DEFAULT_MAX_PRICE_RUB: Double = 30.0

        private const val HTTP_OK = 200

        /** `202 Accepted` — штатный ответ постановки задачи. */
        private const val HTTP_ACCEPTED_RANGE_END = 299

        /** Нет ключа или базы: платный голос недоступен, это приглашение к резерву. */
        const val REASON_NO_KEY: String = "no_api_key"

        /** Сокет не поднялся/оборвался. */
        const val REASON_TRANSPORT: String = "transport"

        /** Синтезировать нечего. */
        const val REASON_EMPTY_TEXT: String = "empty_text"

        /** Провайдер ответил не тем, что обещал контракт. */
        const val REASON_INVALID_RESPONSE: String = "invalid_response"

        /** Задача завершилась успехом, но ссылки на аудио нет. */
        const val REASON_EMPTY_RESULT: String = "empty_result"

        /** Задача завершилась ошибкой (`failMsg`/`errorCode` без кода). */
        const val REASON_JOB_FAILED: String = "job_failed"

        /** Потолок ожидания задачи истёк: звука в этом ходе не будет. */
        const val REASON_TIMEOUT: String = "timeout"

        /**
         * База провайдера задана по открытому `http` (кроме петли): ключ ушёл бы в сеть
         * открытым текстом, поэтому запрос не делается вовсе.
         */
        const val REASON_INSECURE_BASE: String = "insecure_base_url"

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /**
         * Корень media-API из базы провайдера.
         *
         * Человек вписывает базу как `https://plusvibeapi.ru/v1` (это база OpenAI-совместимых
         * ручек), а media-контракт живёт в корне. Поэтому сегмент `v1` отбрасывается, а
         * остальной путь сохраняется (провайдер за реверс-прокси с префиксом).
         *
         * @return корень без завершающего слэша либо `null`, если база не URL.
         */
        fun mediaRoot(baseUrl: String): String? {
            val url = runCatching { baseUrl.trim().toHttpUrl() }.getOrNull() ?: return null
            val defaultPort = if (url.scheme == "https") 443 else 80
            return buildString(url.toString().length) {
                append(url.scheme).append("://").append(url.host)
                if (url.port != defaultPort) append(':').append(url.port)
                url.pathSegments
                    .filter { it.isNotBlank() }
                    .filterNot { it.equals("v1", ignoreCase = true) }
                    .forEach { segment -> append('/').append(segment) }
            }
        }

        /**
         * Можно ли отправлять ключ на этот корень.
         *
         * `https` — всегда. Открытый `http` допускается только для петли (`127.0.0.1`,
         * `localhost`, `::1`): это тестовый `MockWebServer` и локальный прокси на том же
         * устройстве, где сниффинга в сети нет. Всё остальное — отказ ДО запроса: ключ
         * провайдера в открытом заголовке `Authorization` — это утечка, а не «медленно».
         *
         * Побочно это совпадает с политикой Android: с API 28 cleartext-трафик выключен по
         * умолчанию, и такой запрос всё равно не дошёл бы.
         */
        fun isAllowedBase(root: String): Boolean {
            val url = runCatching { root.trim().toHttpUrl() }.getOrNull() ?: return false
            if (url.isHttps) return true
            val host = url.host.lowercase(Locale.US)
            return host == "127.0.0.1" || host == "::1" || host == "localhost" || host == "[::1]"
        }

        /**
         * Текст для синтеза: trim и обрезка по лимиту модели.
         *
         * Котировка длину НЕ проверяет (принимает 6000 и 20000 знаков молча), а генерация
         * отвечает `422` — поэтому режет клиент. Обрезка идёт по границе слова, чтобы не
         * произнести половину слова; если границы нет рядом с лимитом — режем посимвольно.
         *
         * @return текст или `null`, если после trim он пуст.
         */
        fun preparePrompt(text: String, limit: Int = MAX_PROMPT_LENGTH): String? {
            val body = text.trim()
            if (body.isEmpty()) return null
            if (body.length <= limit) return body
            val cut = body.take(limit)
            val boundary = cut.indexOfLast { it == ' ' || it == '\n' || it == '\t' || it == ',' || it == '.' }
            val trimmed = if (boundary > limit / 2) cut.substring(0, boundary) else cut
            return trimmed.trimEnd().takeIf { it.isNotEmpty() }
        }

        /** Опт `engine` для модели: `turbo` только у ElevenLabs, остальным поле не нужно. */
        fun engineFor(model: String): String? =
            if (model.trim().equals(MODEL_ELEVENLABS, ignoreCase = true)) ELEVENLABS_ENGINE else null

        /**
         * Лимит длины промпта КОНКРЕТНОЙ модели (замерено на генерации).
         *
         * Котировка длину не валидирует (принимает 6000 и 20000 знаков молча), а генерация
         * отвечает `422`; резать обязан клиент — и по лимиту выбранной модели, иначе у
         * ElevenLabs без нужды теряется последняя пятая часть законного текста.
         * Незнакомая модель — [MAX_PROMPT_LENGTH] (меньшее безопаснее).
         */
        fun promptLimitFor(model: String): Int = when (model.trim().lowercase(Locale.US)) {
            MODEL_ELEVENLABS, "espeech-tts" -> PROMPT_LIMIT_5000
            "grok-tts" -> PROMPT_LIMIT_15000
            else -> MAX_PROMPT_LENGTH
        }

        /** `max_price_rub` как JSON-число с точкой независимо от локали устройства. */
        fun priceJson(rub: Double): String {
            val safe = if (rub.isNaN() || rub.isInfinite()) 0.0 else rub.coerceAtLeast(0.0)
            return String.format(Locale.US, "%.2f", safe)
        }

        /** Ссылка похожа на аудио (а не на служебный JSON со словами и таймкодами). */
        fun looksLikeAudio(url: String): Boolean {
            val path = url.substringBefore('?').lowercase(Locale.US)
            return AUDIO_EXTENSIONS.any { path.endsWith(it) }
        }

        /** Ссылка похожа на служебный документ (JSON со словами, субтитры, текст). */
        fun looksLikeDocument(url: String): Boolean {
            val path = url.substringBefore('?').lowercase(Locale.US)
            return DOCUMENT_EXTENSIONS.any { path.endsWith(it) }
        }

        /**
         * Ссылки `resultUrls` в порядке предпочтения для скачивания.
         *
         * Расширение — единственный признак, доступный ДО запроса, а у подписанной формы
         * (`…/api/media/file/<id>/0?sig=…`) его нет вовсе. Поэтому порядок: аудио-расширения
         * → всё остальное без признаков документа → документы (последняя надежда, если
         * провайдер отдал файл вообще без расширения и без имени). Стабильная сортировка
         * сохраняет исходный порядок внутри группы.
         */
        fun audioCandidates(urls: List<String>): List<String> =
            urls.withIndex()
                .sortedWith(compareBy({ candidateRank(it.value) }, { it.index }))
                .map { it.value }

        /** 0 — аудио, 1 — неизвестно, 2 — документ. */
        private fun candidateRank(url: String): Int = when {
            looksLikeAudio(url) -> 0
            looksLikeDocument(url) -> 2
            else -> 1
        }

        /**
         * Тело ответа — это аудио, а не JSON/HTML с ошибкой?
         *
         * Проверяются и заголовок, и первые байты: `Content-Type` у подписанной ссылки может
         * быть `application/octet-stream`, а ошибка вместо файла приходит как HTML-страница.
         * Ни один аудиоконтейнер не начинается с `{`, `[` или `<` (MP3 — кадр, WAV — `RIFF`,
         * OGG — `OggS`, MP4 — `ftyp` в 4-м байте), поэтому такой отказ ничего живого не режет.
         */
        fun looksLikeAudioPayload(contentType: String?, body: ByteArray): Boolean {
            val normalized = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.US).orEmpty()
            if (normalized.startsWith("application/json") ||
                normalized.startsWith("text/") ||
                normalized == "application/xml" ||
                normalized == "application/xhtml+xml"
            ) {
                return false
            }
            val first = body.firstOrNull { it != SPACE && it != TAB && it != LF && it != CR } ?: return false
            return first != '{'.code.toByte() && first != '['.code.toByte() && first != '<'.code.toByte()
        }

        /**
         * `Content-Type` скачанного аудио.
         *
         * Приоритет у заголовка ответа; если его нет или он не про аудио — по расширению
         * ссылки; в худшем случае `audio/mpeg` (контракт отдаёт mp3). Ошибка здесь стоит не
         * тишины, а только расширения временного файла ([ru.mars.mobile.ServerTtsAudioFormat]),
         * поэтому запасное значение допустимо.
         */
        fun audioContentType(header: String?, url: String): String {
            val normalized = header?.substringBefore(';')?.trim()?.lowercase(Locale.US).orEmpty()
            if (normalized.startsWith("audio/")) return normalized
            val path = url.substringBefore('?').lowercase(Locale.US)
            return when {
                path.endsWith(".wav") -> "audio/wav"
                path.endsWith(".ogg") || path.endsWith(".opus") -> "audio/ogg"
                path.endsWith(".m4a") || path.endsWith(".mp4") -> "audio/mp4"
                path.endsWith(".flac") -> "audio/flac"
                else -> "audio/mpeg"
            }
        }

        private val AUDIO_EXTENSIONS =
            listOf(".mp3", ".wav", ".ogg", ".opus", ".m4a", ".mp4", ".flac", ".aac")

        /** Расширения служебных файлов задачи: JSON со словами/таймкодами, субтитры, текст. */
        private val DOCUMENT_EXTENSIONS =
            listOf(".json", ".txt", ".srt", ".vtt", ".csv", ".xml", ".html", ".htm")

        private val SPACE = ' '.code.toByte()
        private val TAB = '\t'.code.toByte()
        private val LF = '\n'.code.toByte()
        private val CR = '\r'.code.toByte()

        /**
         * Транспорт с таймаутами под реальный контракт.
         *
         * 15 с коннект / 180 с чтение / 30 с запись / 200 с весь вызов: `POST generate`
         * блокируется до 32 с, а зависший апстрим должен обрываться по `callTimeout`, а не
         * держать корутину речи минутами.
         */
        fun defaultHttpClient(): OkHttpClient = HttpClients.restBuilder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 180L
        private const val WRITE_TIMEOUT_SECONDS = 30L
        private const val CALL_TIMEOUT_SECONDS = 200L
    }

    /** Один HTTP-ответ: только то, что нужно контракту (тело уже прочитано). */
    private class Reply(val code: Int, val contentType: String?, val body: ByteArray)
}
