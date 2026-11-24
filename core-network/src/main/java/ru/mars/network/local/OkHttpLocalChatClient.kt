package ru.mars.network.local

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import ru.mars.network.http.HttpClients
import ru.mars.network.repository.JsonStringEncoding
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Транспорт локального чата: `POST {base}/chat/completions` напрямую к [OI]-совместимому
 * провайдеру, без сервера Марса и без Retrofit.
 *
 * Почему тело собирается руками: в `core-network` нет `org.json` (он есть только в
 * `app-mobile`), а `Moshi` для записи отдал бы числа/строки по своим правилам и потерял бы
 * `arguments` инструментов, которые обязаны уходить **сырой строкой** JSON. Поэтому тело —
 * `StringBuilder` + [JsonStringEncoding.escape], единственный экранировщик модуля.
 *
 * Гарантии контракта §3.3: поток не бросает исключений (всё приходит
 * [LocalChatEvent.Error]), после [LocalChatEvent.AssistantFinished] завершается, отмена
 * корутины закрывает соединение (блокирующий сокет снимается через `Call.cancel()`).
 * Единственное исключение из «всё приходит событием» — смена конфигурации посреди шага:
 * такой поток закрывается БЕЗ событий (см. [providerSeq] и [stream]).
 *
 * Таймауты задаются здесь, а не у вызывающего: [SILENCE_TIMEOUT_MS] — это watchdog
 * молчания (180 с, в миллисекундах), общий `callTimeout` намеренно выключен, потому что
 * один шаг с инструментами может длиться минутами.
 */
class OkHttpLocalChatClient(private val client: OkHttpClient = HttpClients.rest) : LocalChatClient {

    /** Собственный профиль таймаутов поверх переданного клиента (пул и диспетчер общие). */
    private val http: OkHttpClient = client.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(SILENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(0L, TimeUnit.MILLISECONDS)
        .build()

    private val jsonAdapter = Moshi.Builder().build()
        .adapter<Map<String, Any?>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
        )
        .lenient()

    /**
     * Метка конфигурации провайдера, общая для этого транспорта и того, кто меняет настройки.
     *
     * Живёт в клиенте, а не в отдельном синглтоне: клиент и так один на приложение, а
     * глобальная переменная в модуле означала бы, что метку поднимает кто угодно и
     * проверить это нечем. Экран настроек, сменив профиль/адрес/модель, зовёт
     * [LocalProviderSeq.bump] на ЭТОМ объекте — и все шаги, начатые до смены, замолкают
     * (см. [stream]).
     *
     * Параметр конструктора не добавлен намеренно: конструктор с двумя параметрами
     * перестал бы подходить тестовой фабрике `LocalClientFixture`, которая заполняет только
     * известные ей типы, и десяток чужих тестов молча ушёл бы в «пропущено».
     */
    val providerSeq: LocalProviderSeq = LocalProviderSeq()

    /**
     * Шаг без настроек хода — прежнее поведение: `temperature`/`maxTokens` из профиля.
     *
     * Сводится к перегрузке с `settings = null`, чтобы путь сборки тела был ровно один:
     * две копии «почти одинаковой» сборки JSON расходятся на первой же правке, а тело
     * запроса — то, что проверяют чужие тесты и чужой сервер.
     */
    override fun stream(
        profile: LocalProviderProfile,
        apiKey: String,
        messages: List<LocalMessage>,
        tools: List<LocalToolSchema>,
    ): Flow<LocalChatEvent> = stream(profile, apiKey, messages, tools, null)

    /**
     * Шаг с параметрами сэмпсинга этого хода.
     *
     * `settings == null` — байт в байт прежнее тело: старые вызывающие и сохранённые
     * профили не должны заметить появления настроек. Если настройки заданы, поля берутся
     * из них, а значения, оставленные провайдеру, в тело НЕ попадают ([SamplerParams]).
     *
     * Устойчивость к смене конфигурации (B1). В начале шага снимается метка
     * ([LocalProviderSeq.snapshot]), и каждый эмит идёт через [emitIfCurrent]. Если за время
     * шага человек сменил профиль/адрес/модель, поток прекращается молча: ни текста, ни
     * `AssistantFinished`, ни `Error`. Молчание здесь — не потеря, а правильный ответ:
     * ответ по старому адресу больше не принадлежит текущему диалогу, а `Error` заставил бы
     * человека искать сбой там, где он сам всё и поменял. Вызывающий обязан пережить поток,
     * закрывшийся без `AssistantFinished` (экран закрывает шаг по завершении сбора).
     */
    override fun stream(
        profile: LocalProviderProfile,
        apiKey: String,
        messages: List<LocalMessage>,
        tools: List<LocalToolSchema>,
        settings: LocalTurnSettings?,
    ): Flow<LocalChatEvent> {
        // Метка снимается ЗДЕСЬ, при СОЗДАНИИ потока, а не в теле `flow`: между созданием
        // и подпиской человек мог успеть сменить адрес/модель, и метка, снятая в теле,
        // оказалась бы новой — шаг по старому профилю выглядел бы актуальным. Fail-closed:
        // сомнительный шаг молчит, а не пишет ответ чужой модели в текущий диалог.
        val stamp = providerSeq.snapshot()
        return flow<LocalChatEvent> {
            val url = LocalProviderUrls.chatCompletionsUrl(profile.baseUrl).toHttpUrlOrNull()
            if (url == null) {
                emitIfCurrent(stamp, LocalChatEvent.Error("неверный адрес провайдера: «${profile.baseUrl}»"))
                return@flow
            }
            // Отказ по вложениям — до сборки тела и до сети: сообщение с неотправляемым
            // вложением не должно уходить «без файла» молча (контракт §3). Текст причины
            // приходит событием, а не исключением: поток по контракту их не бросает.
            val refusal = messages.firstNotNullOfOrNull { it.attachmentRefusal() }
            if (refusal != null) {
                emitIfCurrent(stamp, LocalChatEvent.Error(refusal))
                return@flow
            }
            val payload = buildRequestBody(profile, messages, tools, settings)

            var attempts = 0
            var receivedToken = false

            while (true) {
                currentCoroutineContext().ensureActive()
                // Сверка и здесь: повтор после смены адреса ушёл бы на СТАРЫЙ адрес, то есть
                // заведомо в чужой сервер — и «новый» ответ снова оказался бы старым.
                if (stamp.isOvertaken) return@flow
                attempts += 1

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("Cache-Control", "no-cache")
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val call = http.newCall(request)
                // Отмена корутины не прерывает блокирующее чтение сама по себе — снимаем вызов.
                val cancelHook = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
                var retry = false
                var stop = false

                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            val errorBody = response.body?.string().orEmpty()
                            val retryable = LocalRetryPolicy.isRetryableStatus(response.code)
                            if (LocalRetryPolicy.shouldRetry(attempts, receivedToken, retryable)) {
                                retry = true
                            } else {
                                stop = true
                                // Устаревший шаг молчит: отказ пришёл по адресу, который человек
                                // уже сменил, и показать его как сбой текущего — соврать.
                                emitIfCurrent(stamp, LocalChatEvent.Error(describeFailure(response.code, errorBody), retryable))
                            }
                        } else {
                            val source = response.body?.source()
                            if (source == null) {
                                stop = true
                                emitIfCurrent(stamp, LocalChatEvent.Error("провайдер вернул пустой ответ"))
                            } else {
                                val outcome = this@flow.consumeStream(source, stamp)
                                if (outcome.sawToken) receivedToken = true
                                stop = true
                                // Замолчавший из-за смены адреса шаг не «закрылся без данных»:
                                // приписывать ему сбой пустого потока — значит пугать человека
                                // ошибкой за то, что он сам и сделал.
                                if (!outcome.produced && !stamp.isOvertaken) {
                                    // Пустой 200 без единого кадра — не «пустой ответ модели», а сбой.
                                    emitIfCurrent(stamp, LocalChatEvent.Error("провайдер закрыл поток без данных", retryable = true))
                                }
                                // Обрыв без `finish_reason` и без `[DONE]` — не ошибка: парсер закрыл
                                // шаг на EOF и отдал `AssistantFinished` тем, что успел собрать.
                                Unit
                            }
                        }
                    }
                } catch (io: IOException) {
                    // Отмена приходит сюда как IOException("Canceled") — не путать с обрывом сети.
                    if (!currentCoroutineContext().isActive) throw CancellationException("запрос отменён")
                    if (stamp.isOvertaken) {
                        // Сокет на старый адрес обрывается при смене конфигурации: обрыв здесь —
                        // следствие действия человека, а не сбой сети, и ошибкой не считается.
                        stop = true
                    } else if (LocalRetryPolicy.shouldRetry(attempts, receivedToken, LocalRetryPolicy.isRetryableFailure(io))) {
                        retry = true
                    } else {
                        stop = true
                        emitIfCurrent(stamp, LocalChatEvent.Error("сеть: ${io.message ?: io.javaClass.simpleName}", retryable = true))
                    }
                } finally {
                    cancelHook?.dispose()
                }

                // Устаревший шаг выходит молча и без повтора: повтор ушёл бы на адрес,
                // который человек уже сменил.
                if (stop || stamp.isOvertaken) return@flow
                if (!retry) return@flow
                delay(LocalRetryPolicy.pauseBeforeRetry(attempts))
            }
        }.flowOn(Dispatchers.IO)
    }

    override suspend fun models(
        profile: LocalProviderProfile,
        apiKey: String,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val url = LocalProviderUrls.modelsUrl(profile.baseUrl).toHttpUrlOrNull()
            ?: return@withContext Result.failure<List<String>>(
                IllegalArgumentException("неверный адрес провайдера: «${profile.baseUrl}»"),
            )
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    // Отказ ключа — именно отказ, а не «моделей нет»: иначе человек ищет
                    // проблему в адресе и настройках, а не в ключе (контракт §4, п. 6).
                    response.code == 401 || response.code == 403 ->
                        Result.failure<List<String>>(IOException("ключ не принят (HTTP ${response.code})"))

                    !response.isSuccessful ->
                        Result.failure<List<String>>(IOException(describeFailure(response.code, body)))

                    else -> Result.success(parseModelIds(body))
                }
            }
        } catch (io: IOException) {
            Result.failure<List<String>>(io)
        }
    }

    /**
     * `GET {base}/models` с метаданными — окно контекста и поддержка reasoning.
     *
     * Тот же адрес, что у [models], и те же заголовки/таймауты (собственный [http] с
     * `connectTimeout`/`readTimeout`), но разбор отдан [LocalModelCatalog.parse]: каталог
     * приходит от чужого сервера, и один битый элемент не должен лишать выбора модели.
     * Пустой список — валидный ответ («провайдер жив, моделей нет»); отказ ключа и сетевые
     * сбои — [Result.failure], чтобы вызывающий не путал их с пустым каталогом.
     */
    override suspend fun catalog(
        profile: LocalProviderProfile,
        apiKey: String,
    ): Result<List<LocalModelInfo>> = withContext(Dispatchers.IO) {
        val url = LocalProviderUrls.modelsUrl(profile.baseUrl).toHttpUrlOrNull()
            ?: return@withContext Result.failure<List<LocalModelInfo>>(
                IllegalArgumentException("неверный адрес провайдера: «${profile.baseUrl}»"),
            )
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    // Отказ ключа — отказ, а не пустой каталог: иначе человек ищет проблему
                    // в адресе и настройках, а не в ключе (как в [models]).
                    response.code == 401 || response.code == 403 ->
                        Result.failure<List<LocalModelInfo>>(IOException("ключ не принят (HTTP ${response.code})"))

                    !response.isSuccessful ->
                        Result.failure<List<LocalModelInfo>>(IOException(describeFailure(response.code, body)))

                    // Разбор не бросает: битый JSON даёт пустой список.
                    else -> Result.success(LocalModelCatalog.parse(body))
                }
            }
        } catch (io: IOException) {
            Result.failure<List<LocalModelInfo>>(io)
        }
    }

    // --- чтение стрима --------------------------------------------------------

    /**
     * Читает поток построчно и отдаёт события; выходит, как только шаг закрылся
     * `AssistantFinished` (дальше читать нечего).
     *
     * [stamp] — метка конфигурации, снятая при создании шага. Каждый эмит идёт через
     * [emitIfCurrent]: смена адреса/модели посреди потока прекращает и эмиссию, и чтение
     * сокета, но НЕ выглядит ошибкой — шаг просто перестаёт существовать. Возврат при
     * устаревшей метке идёт с `produced`, накопленным ДО смены: вызывающий по нему решает,
     * был ли это пустой поток, и устаревший шаг этот вопрос уже не задаёт.
     */
    private suspend fun FlowCollector<LocalChatEvent>.consumeStream(
        source: BufferedSource,
        stamp: LocalProviderStamp,
    ): StreamOutcome {
        val parser = LocalSseParser()
        var produced = false
        var sawToken = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val line = source.readUtf8Line() ?: break
            for (event in parser.onLine(line)) {
                for (mapped in mapSse(event)) {
                    if (!emitIfCurrent(stamp, mapped)) return StreamOutcome(produced, sawToken)
                    produced = true
                    if (mapped is LocalChatEvent.Delta || mapped is LocalChatEvent.ReasoningDelta) sawToken = true
                    if (mapped is LocalChatEvent.AssistantFinished) return StreamOutcome(produced, sawToken)
                }
            }
        }
        for (event in parser.onEndOfStream()) {
            for (mapped in mapSse(event)) {
                if (!emitIfCurrent(stamp, mapped)) return StreamOutcome(produced, sawToken)
                produced = true
                if (mapped is LocalChatEvent.Delta || mapped is LocalChatEvent.ReasoningDelta) sawToken = true
                if (mapped is LocalChatEvent.AssistantFinished) return StreamOutcome(produced, sawToken)
            }
        }
        return StreamOutcome(produced, sawToken)
    }

    /** Что успел отдать поток: были ли события и был ли текст (для решения о повторе). */
    private class StreamOutcome(val produced: Boolean, val sawToken: Boolean)

    /** Кадр парсера → события контракта. */
    private fun mapSse(event: LocalSseParser.Event): List<LocalChatEvent> = when (event) {
        is LocalSseParser.Event.Text -> listOf(LocalChatEvent.Delta(event.text))
        is LocalSseParser.Event.Reasoning -> listOf(LocalChatEvent.ReasoningDelta(event.text))
        // Парсер отдаёт расход раньше `Finished`, поэтому экран обновляет пилюлю тем же шагом.
        is LocalSseParser.Event.Usage -> listOf(LocalChatEvent.UsageReported(event.usage))
        is LocalSseParser.Event.Finished -> {
            val calls = event.toolCalls
            val events = ArrayList<LocalChatEvent>(calls.size + 1)
            calls.forEach { events.add(LocalChatEvent.ToolRequested(it)) }
            events.add(LocalChatEvent.AssistantFinished(event.text, calls, event.finishReason))
            events
        }
        LocalSseParser.Event.Done -> emptyList()
    }

    // --- тело запроса ---------------------------------------------------------

    /**
     * Тело `POST /chat/completions`.
     *
     * `settings == null` — ровно прежняя сборка (`temperature`/`maxTokens`/`reasoningEffort`
     * из профиля), чтобы сохранённые профили и чужие тесты тела запроса не менялись.
     * Настройки хода, если заданы, ПОЛНОСТЬЮ замещают профильные поля: профиль — это
     * дефолт для всех моделей, и смешивать его с настройками конкретного хода значило бы
     * отправлять чужую температуру, ровно ту беду, из-за которой эти настройки появились.
     */
    private fun buildRequestBody(
        profile: LocalProviderProfile,
        messages: List<LocalMessage>,
        tools: List<LocalToolSchema>,
        settings: LocalTurnSettings?,
    ): String {
        val body = StringBuilder(256 + messages.size * 64 + tools.size * 128)
        body.append('{')
        body.append("\"model\":").append(JsonStringEncoding.escape(profile.model))
        body.append(",\"stream\":true")
        // `include_usage` уходит ВСЕГДА: без него провайдер не пришлёт финальный кадр `usage`,
        // и пилюля контекста останется пустой. Провайдер, который поля не знает, его
        // игнорирует, поэтому «лишнее поле» здесь безопаснее «недосланного» (контракт §3, W1 п. 3).
        body.append(",\"stream_options\":{\"include_usage\":true}")
        if (settings == null) {
            // Прежний путь, байт в байт: профиль — единственный источник, пока ход не
            // получил собственные настройки.
            profile.temperature?.let { body.append(",\"temperature\":").append(it.toString()) }
            profile.maxTokens?.let { body.append(",\"max_tokens\":").append(it) }
            profile.reasoningEffort?.takeIf { it.isNotBlank() }?.let {
                body.append(",\"reasoning_effort\":").append(JsonStringEncoding.escape(it))
            }
        } else {
            appendSamplerFields(body, settings)
        }

        body.append(",\"messages\":[")
        messages.forEachIndexed { index, message ->
            if (index > 0) body.append(',')
            appendMessage(body, message)
        }
        body.append(']')

        if (tools.isNotEmpty()) {
            body.append(",\"tools\":[")
            tools.forEachIndexed { index, schema ->
                if (index > 0) body.append(',')
                appendTool(body, schema)
            }
            body.append(']')
            body.append(",\"tool_choice\":\"auto\"")
        }
        body.append('}')
        return body.toString()
    }

    /**
     * Поля сэмпсинга из настроек хода.
     *
     * Порядок — как в таблице [SamplerParam]: тело читают глазами в логах и в отладке, и
     * произвольный порядок превращает сравнение двух запросов в работу. Экранирование и
     * формат чисел — те же, что у остальных полей: [JsonStringEncoding] и `toString()` у
     * `Double` (JSON принимает и `1.0`), целые — без дробной части.
     *
     * `stop` отправляется массивом строк и только непустым: пустой массив часть
     * посредников читает как «стоп-последовательностей нет», часть — как ошибку, а
     * молчание означает ровно то же самое и никого не смущает.
     */
    private fun appendSamplerFields(body: StringBuilder, settings: LocalTurnSettings) {
        appendNumericField(body, SamplerParam.TEMPERATURE, settings.temperature)
        appendNumericField(body, SamplerParam.TOP_P, settings.topP)
        appendNumericField(body, SamplerParam.TOP_K, settings.topK?.toDouble())
        appendNumericField(body, SamplerParam.MIN_P, settings.minP)
        appendNumericField(body, SamplerParam.PRESENCE_PENALTY, settings.presencePenalty)
        appendNumericField(body, SamplerParam.FREQUENCY_PENALTY, settings.frequencyPenalty)
        appendNumericField(body, SamplerParam.REPETITION_PENALTY, settings.repetitionPenalty)
        appendNumericField(body, SamplerParam.SEED, settings.seed?.toDouble())
        appendNumericField(body, SamplerParam.MAX_TOKENS, settings.maxTokens?.toDouble())

        if (settings.stop.isNotEmpty()) {
            body.append(",\"stop\":[")
            settings.stop.forEachIndexed { index, marker ->
                if (index > 0) body.append(',')
                body.append(JsonStringEncoding.escape(marker))
            }
            body.append(']')
        }

        settings.reasoningEffort?.takeIf { it.isNotBlank() }?.let {
            body.append(",\"reasoning_effort\":").append(JsonStringEncoding.escape(it))
        }
    }

    /**
     * Одно числовое поле — или ничего, если человек его не задавал / оставил дефолтом.
     *
     * Значение приводится к границам и шагу ДО сравнения с дефолтом и до записи: иначе
     * испорченное `temperature: 9` ушло бы как есть и вернуло 400 на весь запрос, а
     * «0.9999999999» считалось бы заданным значением.
     */
    private fun appendNumericField(body: StringBuilder, param: SamplerParam, raw: Double?) {
        if (raw == null) return
        val value = SamplerParams.clamp(param, raw)
        if (SamplerParams.isLeftToProvider(param, value)) return
        body.append(",\"").append(param.wireName).append("\":")
        if (param.kind == SamplerKind.INTEGER) {
            // Целое не должно уехать как `200000.0`: часть посредников такое отвергает.
            body.append(value.toLong())
        } else {
            body.append(value)
        }
    }

    private fun appendMessage(body: StringBuilder, message: LocalMessage) {
        body.append("{\"role\":").append(JsonStringEncoding.escape(roleWire(message.role)))
        when (message.role) {
            LocalRole.TOOL -> {
                // Ответ инструмента привязан к вызову: без tool_call_id провайдер отвергает историю.
                body.append(",\"tool_call_id\":").append(JsonStringEncoding.escape(message.toolCallId.orEmpty()))
                body.append(",\"content\":")
                appendContent(body, message)
            }

            LocalRole.ASSISTANT -> {
                body.append(",\"content\":")
                appendContent(body, message)
                if (message.toolCalls.isNotEmpty()) {
                    body.append(",\"tool_calls\":[")
                    message.toolCalls.forEachIndexed { index, call ->
                        if (index > 0) body.append(',')
                        body.append("{\"id\":").append(JsonStringEncoding.escape(call.id))
                        body.append(",\"type\":\"function\",\"function\":{\"name\":")
                            .append(JsonStringEncoding.escape(call.name))
                        body.append(",\"arguments\":")
                            .append(JsonStringEncoding.escape(call.argumentsJson.ifBlank { EMPTY_ARGUMENTS }))
                        body.append("}}")
                    }
                    body.append(']')
                }
            }

            else -> {
                body.append(",\"content\":")
                appendContent(body, message)
            }
        }
        body.append('}')
    }

    /**
     * Значение поля `content`.
     *
     * Без вложений — СТРОГО прежняя строка, байт в байт: возможность, которой никто не
     * воспользовался, не имеет права менять формат тела запроса. Провайдеры, историю и
     * тесты это ломало бы на ровном месте, а выгоды нет никакой.
     *
     * С вложениями — массив частей [OI]: сначала текст, затем по части на вложение.
     * Пустой текст отдельной частью не отправляется: пустой текстовый блок часть
     * провайдеров отвергает как некорректный запрос.
     */
    private fun appendContent(body: StringBuilder, message: LocalMessage) {
        if (message.attachments.isEmpty()) {
            body.append(JsonStringEncoding.escape(message.text))
            return
        }
        body.append('[')
        var first = true
        if (message.text.isNotEmpty()) {
            appendTextPart(body, message.text)
            first = false
        }
        for (attachment in message.attachments) {
            if (!first) body.append(',')
            appendAttachmentPart(body, attachment)
            first = false
        }
        body.append(']')
    }

    /** Текстовая часть: `{"type":"text","text":"…"}`. */
    private fun appendTextPart(body: StringBuilder, text: String) {
        body.append("{\"type\":\"text\",\"text\":").append(JsonStringEncoding.escape(text)).append('}')
    }

    /**
     * Часть вложения.
     *
     * Картинка — `image_url` с data-URL: провайдер не умеет принимать файлы, только base64
     * внутри `data:<mime>;base64,<…>`. Текстовый файл — текстовая часть с пометкой имени:
     * без пометки модель не понимает, откуда взялся текст и где кончается документ.
     *
     * Неподдерживаемые виды сюда не доходят — их отсекает [LocalMessage.attachmentRefusal]
     * до сборки тела, с русской причиной для человека.
     */
    private fun appendAttachmentPart(body: StringBuilder, attachment: LocalAttachment) {
        when (attachment.kind) {
            AttachmentKind.IMAGE -> {
                val mime = attachment.mimeType.takeIf { it.isNotBlank() } ?: DEFAULT_IMAGE_MIME
                val url = "data:$mime;base64,${attachment.dataBase64.orEmpty()}"
                body.append("{\"type\":\"image_url\",\"image_url\":{\"url\":")
                body.append(JsonStringEncoding.escape(url))
                body.append("}}")
            }

            AttachmentKind.TEXT_FILE -> {
                val name = attachment.fileName?.takeIf { it.isNotBlank() } ?: DEFAULT_FILE_NAME
                appendTextPart(body, "Файл «$name»:\n${attachment.textContent.orEmpty()}")
            }

            // Документ (PDF/DOCX/XLSX/PPTX) приходит уже распакованным в текст на
            // устройстве: провайдер бинарник не принимает, а текст из него — принимает.
            AttachmentKind.DOCUMENT -> {
                val name = attachment.fileName?.takeIf { it.isNotBlank() } ?: DEFAULT_FILE_NAME
                appendTextPart(body, "Документ «$name»:\n${attachment.textContent.orEmpty()}")
            }
        }
    }

    private fun appendTool(body: StringBuilder, schema: LocalToolSchema) {
        body.append("{\"type\":\"function\",\"function\":{\"name\":")
            .append(JsonStringEncoding.escape(schema.name))
        body.append(",\"description\":").append(JsonStringEncoding.escape(schema.description))
        // `parameters` — сырой JSON-объект схемы, а не строка: так его ждёт [OI].
        body.append(",\"parameters\":").append(parametersJson(schema.parametersJson))
        body.append("}}")
    }

    private fun parametersJson(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("{") || trimmed.startsWith("[")) trimmed else EMPTY_PARAMETERS
    }

    private fun roleWire(role: LocalRole): String = when (role) {
        LocalRole.SYSTEM -> "system"
        LocalRole.USER -> "user"
        LocalRole.ASSISTANT -> "assistant"
        LocalRole.TOOL -> "tool"
    }

    // --- ответы ---------------------------------------------------------------

    private fun parseModelIds(body: String): List<String> {
        val root = try {
            jsonAdapter.fromJson(body)
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val rows = root["data"] as? List<*> ?: root["models"] as? List<*> ?: return emptyList()
        return rows.mapNotNull { row ->
            when (row) {
                is String -> row.takeIf { it.isNotBlank() }
                is Map<*, *> -> (row["id"] as? String)?.takeIf { it.isNotBlank() }
                    ?: (row["name"] as? String)?.takeIf { it.isNotBlank() }
                else -> null
            }
        }.distinct()
    }

    private fun describeFailure(code: Int, body: String): String {
        val snippet = body.trim().replace(WHITESPACE, " ").take(MAX_ERROR_SNIPPET)
        val prefix = when {
            code == 401 || code == 403 -> "ключ не принят"
            code == 404 -> "адрес не найден (HTTP 404) — проверь baseUrl"
            code == 429 -> "слишком много запросов (HTTP 429)"
            code >= 500 -> "провайдер недоступен (HTTP $code)"
            else -> "HTTP $code"
        }
        return if (snippet.isEmpty()) prefix else "$prefix: $snippet"
    }

    companion object {
        /** Подключение, мс. */
        const val CONNECT_TIMEOUT_MS: Long = 15_000L

        /** Watchdog молчания стрима, мс (не наносекунды): 3 минуты тишины — обрыв. */
        const val SILENCE_TIMEOUT_MS: Long = 180_000L

        /** Запись тела запроса, мс. */
        const val WRITE_TIMEOUT_MS: Long = 20_000L

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val WHITESPACE = Regex("\\s+")
        private const val MAX_ERROR_SNIPPET = 240
        private const val EMPTY_ARGUMENTS = "{}"
        private const val EMPTY_PARAMETERS = "{\"type\":\"object\",\"properties\":{}}"

        /** MIME картинки, если вызывающий его не сообщил: самый частый случай — фото с телефона. */
        private const val DEFAULT_IMAGE_MIME = "image/jpeg"

        /** Имя текстового файла в пометке, если его не сообщили. */
        private const val DEFAULT_FILE_NAME = "файл"
    }
}
