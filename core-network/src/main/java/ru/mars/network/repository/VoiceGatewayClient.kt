package ru.mars.network.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.mars.network.api.MarsApi
import ru.mars.network.http.HttpClients
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.VoiceCloseResult
import ru.mars.network.model.VoiceEvent
import ru.mars.network.model.VoiceGatewayError
import ru.mars.network.model.VoiceInterruptResult
import ru.mars.network.model.VoiceResult
import ru.mars.network.model.VoiceSession
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference

/**
 * Клиент серверного голосового шлюза: SSE вниз, POST вверх.
 *
 * Шлюз НЕ запускает ход агента — он проецирует уже идущий ход в голосовой словарь.
 * Отправка хода голосом остаётся обычным путём (`transcribePcmWav` ->
 * `ChatRepository.sendMessage`), а этот клиент делает три вещи:
 *
 * - `POST api/voice/session` — [createSession]: получить `voice_id` на сессию;
 * - `GET api/voice/stream?voice_id=...` — [events]: поток [VoiceEvent] с
 *   докачкой по `Last-Event-ID` и переподключением через
 *   [AdaptiveSseReconnectPolicy];
 * - `POST api/voice/interrupt` / `POST api/voice/close` — [interrupt] и [close].
 *
 * Авторизация та же, что у [ChatRepository]: device-token приходит готовым
 * [ConsumedDeviceCredential] и уходит заголовком `Authorization: Bearer <token>`;
 * повторной авторизации здесь нет. Транспорт — общие профили [HttpClients]: поток
 * идёт через [HttpClients.stream] (длинный read-timeout, 5-секундные keepalive
 * сервера его держат), управляющие POST — через [HttpClients.rest] (короткие
 * таймауты, чтобы зависший interrupt не держал UI).
 *
 * Ошибки наружу НЕ выбрасываются: одиночные вызовы возвращают [VoiceResult]
 * с [VoiceGatewayError], а поток завершается кадром [VoiceEvent.StreamError].
 * Терминальные ответы контракта (401 — токен, 404 — неизвестная сессия, 409 —
 * поток уже занят другим подписчиком) не ретраятся; 503 (слишком много потоков),
 * 429 и 5xx — ретраятся по политике.
 *
 * Отмена сбора [events] закрывает соединение: по завершении корутины-производителя
 * активный OkHttp `Call` отменяется, сокет закрывается сразу, а не после
 * read-timeout.
 */
class VoiceGatewayClient(
    /** Профиль для SSE-потока: длинный read-timeout, без callTimeout. */
    private val streamClient: OkHttpClient = HttpClients.stream,
    /** Профиль для POST-вызовов: короткие per-phase таймауты. */
    private val restClient: OkHttpClient = HttpClients.rest,
    /** Базовый адрес бэкенда; переопределяется параметром каждого вызова. */
    private val baseUrl: String = MarsApi.BASE_URL,
    /**
     * Фабрика политики переподключения: НОВЫЙ экземпляр на каждый сбор [events],
     * чтобы новый подписчик не наследовал исчерпанный бюджет предыдущего.
     */
    private val reconnectPolicyFactory: () -> AdaptiveSseReconnectPolicy = { AdaptiveSseReconnectPolicy() },
) {

    /**
     * `POST api/voice/session` — открыть голосовой поток для сессии.
     *
     * Ответ 200: `{"voice_id":"v1_...","session_id":"...","expires_in":<сек>}`.
     * 404 — сессия неизвестна серверу (не ретраить: сессию надо создать заново).
     */
    suspend fun createSession(
        sessionId: String,
        credential: ConsumedDeviceCredential,
        baseUrl: String = this.baseUrl,
    ): VoiceResult<VoiceSession> = withContext(Dispatchers.IO) {
        if (sessionId.isBlank()) {
            return@withContext VoiceResult.Failure(VoiceGatewayError.Protocol("session_id не задан"))
        }
        val request = Request.Builder()
            .url(url(baseUrl, "api/voice/session"))
            .header("Authorization", bearer(credential))
            .header("Content-Type", JSON_MEDIA_TYPE_HEADER)
            .post(
                "{\"session_id\":${JsonStringEncoding.escape(sessionId)}}"
                    .toRequestBody(JSON_MEDIA_TYPE)
            )
            .build()
        execute(request) { code, body ->
            val payload = TolerantJson.parseMap(body)
            if (code !in 200..299) {
                VoiceResult.Failure(VoiceGatewayError.Http(code, describeHttpError(code, body)))
            } else if (payload == null) {
                VoiceResult.Failure(VoiceGatewayError.Protocol("HTTP $code: неразбираемый ответ"))
            } else {
                val voiceId = TolerantJson.string(payload, "voice_id")
                if (voiceId == null) {
                    VoiceResult.Failure(VoiceGatewayError.Protocol("HTTP $code: нет voice_id"))
                } else {
                    VoiceResult.Success(
                        VoiceSession(
                            voiceId = voiceId,
                            sessionId = TolerantJson.string(payload, "session_id") ?: sessionId,
                            expiresInSeconds = TolerantJson.long(payload, "expires_in"),
                        )
                    )
                }
            }
        }
    }

    /**
     * `GET api/voice/stream?voice_id=...` — поток событий голосового шлюза.
     *
     * Поток долгоживущий: завершается кадром [VoiceEvent.AgentFinished] (ход
     * закончился) либо [VoiceEvent.StreamError] (терминальный HTTP-код или
     * исчерпан бюджет переподключений). Отмена сбора закрывает соединение.
     *
     * Докачка: курсор берётся из `id:` последнего полученного кадра и уходит
     * заголовком `Last-Event-ID` при следующей попытке, поэтому сервер продолжает
     * поток с того же места, а не с начала. Обрыв ПОСЛЕ [VoiceEvent.AgentFinished]
     * не считается потерей — поток просто завершается.
     *
     * Один активный поток на `voice_id`: второй подписчик получит 409 и поток
     * завершится [VoiceEvent.StreamError] без ретраев.
     */
    fun events(
        voiceId: String,
        credential: ConsumedDeviceCredential,
        baseUrl: String = this.baseUrl,
    ): Flow<VoiceEvent> = callbackFlow {
        if (voiceId.isBlank()) {
            trySend(VoiceEvent.StreamError(VoiceGatewayError.Protocol("voice_id не задан")))
            close()
            return@callbackFlow
        }
        val policy = reconnectPolicyFactory()
        // Курсор докачки и активный вызов живут в замыкании: продюсер их пишет,
        // awaitClose читает при отмене — именно так отмена сбора рвёт сокет сразу,
        // не дожидаясь read-timeout. AtomicReference, потому что awaitClose
        // выполняется на потоке коллектора, а продюсер — на Dispatchers.IO.
        val cursor = AtomicReference<String?>(null)
        val currentCall = AtomicReference<Call?>(null)
        var stalledAttempts = 0

        val producer = launch(Dispatchers.IO) {
            while (isActive) {
                val cursorAtStart = cursor.get()
                val call = streamClient.newCall(streamRequest(baseUrl, voiceId, credential, cursorAtStart))
                currentCall.set(call)
                var failure: VoiceGatewayError? = null
                var finished = false
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            val body = runCatching { response.body?.string() }.getOrNull().orEmpty()
                            failure = VoiceGatewayError.Http(response.code, describeHttpError(response.code, body))
                            return@use
                        }
                        val body = response.body
                        if (body == null) {
                            failure = VoiceGatewayError.Protocol("HTTP ${response.code}: пустое тело потока")
                            return@use
                        }
                        val parser = VoiceStreamParser()
                        suspend fun deliver(events: List<VoiceEvent>) {
                            for (event in events) {
                                // Первое же полученное событие доказывает, что связь жива:
                                // политика сбрасывает серию неудач и бюджет реконнектов.
                                policy.onStreamEvent()
                                send(event)
                                if (event is VoiceEvent.AgentFinished) finished = true
                            }
                            parser.lastEventId?.let { cursor.set(it) }
                        }
                        val reader = InputStreamReader(body.byteStream(), Charsets.UTF_8)
                        val chars = CharArray(8 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = reader.read(chars)
                            if (read < 0) break
                            if (read > 0) deliver(parser.feed(String(chars, 0, read)))
                        }
                        // Поток закрылся без завершающей пустой строки — последний кадр
                        // всё равно доставляем (см. SseChunkParser.end).
                        deliver(parser.end())
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (io: IOException) {
                    failure = VoiceGatewayError.Transport(describeNetworkError(io), io)
                } finally {
                    currentCall.compareAndSet(call, null)
                }

                if (finished) {
                    close()
                    return@launch
                }

                val error = failure
                if (error != null && !error.isRetryable) {
                    // 401/404/409 и прочие терминальные ответы: повтор бессмысленен.
                    send(VoiceEvent.StreamError(error))
                    close()
                    return@launch
                }

                stalledAttempts = if (cursor.get() == cursorAtStart) stalledAttempts + 1 else 0
                val plan = policy.onStreamFailure()
                if (plan == null || stalledAttempts > policy.maxRetries) {
                    send(
                        VoiceEvent.StreamError(
                            error ?: VoiceGatewayError.Transport("поток закрыт сервером без завершения хода")
                        )
                    )
                    close()
                    return@launch
                }
                delay(plan.delayMs)
            }
        }

        awaitClose {
            currentCall.getAndSet(null)?.cancel()
            producer.cancel()
        }
    }

    /**
     * `POST api/voice/interrupt` — попросить сервер замолчать (barge-in).
     *
     * Идемпотентно: повторный вызов вернёт `already_interrupted = true`. Ход агента
     * НЕ отменяется — сервер лишь рассылает событие `speech.stop`, поэтому
     * прерывается только озвучка.
     */
    suspend fun interrupt(
        voiceId: String,
        sessionId: String,
        credential: ConsumedDeviceCredential,
        baseUrl: String = this.baseUrl,
    ): VoiceResult<VoiceInterruptResult> = withContext(Dispatchers.IO) {
        postVoiceControl("api/voice/interrupt", voiceId, sessionId, credential, baseUrl) { code, body ->
            val payload = TolerantJson.parseMap(body)
            if (code !in 200..299) {
                VoiceResult.Failure(VoiceGatewayError.Http(code, describeHttpError(code, body)))
            } else if (payload == null) {
                VoiceResult.Failure(VoiceGatewayError.Protocol("HTTP $code: неразбираемый ответ"))
            } else {
                VoiceResult.Success(
                    VoiceInterruptResult(
                        voiceId = TolerantJson.string(payload, "voice_id") ?: voiceId,
                        interrupted = TolerantJson.bool(payload, "interrupted"),
                        alreadyInterrupted = TolerantJson.bool(payload, "already_interrupted"),
                    )
                )
            }
        }
    }

    /**
     * `POST api/voice/close` — закрыть голосовой поток на сервере (конец разговора).
     * После закрытия `voice_id` непригоден: для новой реплики нужен новый
     * [createSession].
     */
    suspend fun close(
        voiceId: String,
        sessionId: String,
        credential: ConsumedDeviceCredential,
        baseUrl: String = this.baseUrl,
    ): VoiceResult<VoiceCloseResult> = withContext(Dispatchers.IO) {
        postVoiceControl("api/voice/close", voiceId, sessionId, credential, baseUrl) { code, body ->
            val payload = TolerantJson.parseMap(body)
            if (code !in 200..299) {
                VoiceResult.Failure(VoiceGatewayError.Http(code, describeHttpError(code, body)))
            } else if (payload == null) {
                VoiceResult.Failure(VoiceGatewayError.Protocol("HTTP $code: неразбираемый ответ"))
            } else {
                VoiceResult.Success(
                    VoiceCloseResult(
                        voiceId = TolerantJson.string(payload, "voice_id") ?: voiceId,
                        closed = TolerantJson.bool(payload, "closed"),
                    )
                )
            }
        }
    }

    // ── request plumbing ──

    private fun <T> postVoiceControl(
        path: String,
        voiceId: String,
        sessionId: String,
        credential: ConsumedDeviceCredential,
        baseUrl: String,
        onResponse: (Int, String) -> VoiceResult<T>,
    ): VoiceResult<T> {
        val json = "{\"voice_id\":${JsonStringEncoding.escape(voiceId)}," +
            "\"session_id\":${JsonStringEncoding.escape(sessionId)}}"
        val request = Request.Builder()
            .url(url(baseUrl, path))
            .header("Authorization", bearer(credential))
            .header("Content-Type", JSON_MEDIA_TYPE_HEADER)
            .post(json.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request, onResponse)
    }

    /**
     * Синхронный вызов с типизированным исходом: транспортные сбои превращаются в
     * [VoiceGatewayError.Transport], а не летят исключением в вызывающий код.
     */
    private fun <T> execute(
        request: Request,
        onResponse: (Int, String) -> VoiceResult<T>,
    ): VoiceResult<T> = try {
        restClient.newCall(request).execute().use { response ->
            val body = runCatching { response.body?.string() }.getOrNull().orEmpty()
            onResponse(response.code, body)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (io: IOException) {
        VoiceResult.Failure(VoiceGatewayError.Transport(describeNetworkError(io), io))
    }

    private fun streamRequest(
        baseUrl: String,
        voiceId: String,
        credential: ConsumedDeviceCredential,
        lastEventId: String?,
    ): Request = Request.Builder()
        .url(
            url(baseUrl, "api/voice/stream").newBuilder()
                .addQueryParameter("voice_id", voiceId)
                .build()
        )
        .header("Authorization", bearer(credential))
        .header("Accept", "text/event-stream")
        .header("Cache-Control", "no-cache")
        .apply { lastEventId?.takeIf { it.isNotBlank() }?.let { header("Last-Event-ID", it) } }
        .get()
        .build()

    private fun url(baseUrl: String, path: String): HttpUrl =
        baseUrl.toHttpUrl().newBuilder().addPathSegments(path).build()

    private fun bearer(credential: ConsumedDeviceCredential) = "Bearer ${credential.accessToken}"

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val JSON_MEDIA_TYPE_HEADER = "application/json"
    }
}
