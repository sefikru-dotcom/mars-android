package ru.mars.mobile.localtools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import ru.mars.network.local.LocalProviderUrls
import ru.mars.network.local.LocalToolResult
import ru.mars.network.local.LocalToolSchema
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ключ и адрес провайдера, снятые с АКТИВНОГО профиля на момент вызова.
 *
 * Отдельный тип, а не два параметра-строки: ключ не должен случайно оказаться в тексте
 * ошибки, логе или `toString()` профиля. Здесь он живёт ровно до первого заголовка
 * `Authorization` и никуда больше не передаётся.
 */
internal class ProviderCredentials(val baseUrl: String, val apiKey: String)

/**
 * `web_search` — модель ищет в интернете через ту же площадку, что даёт ей саму себя.
 *
 * **Почему так, а не свой HTTP-краулер.** У провайдера (PlusVibe) есть модели с поиском:
 * `perplexity/sonar`, `perplexity/sonar-pro`, `perplexity/sonar-reasoning-pro`. Они ищут
 * сами и возвращают готовый ответ со ссылками, поэтому инструменту не нужны ни новый
 * ключ, ни разбор чужих поисковых API, ни прокси против капчи. Тот же `baseUrl`, тот же
 * ключ профиля, что и у чата, — только другое поле `model`.
 *
 * **Как устроено:**
 *  1. Адрес и ключ приходят в конструктор поставщиком [credentials], а не читаются
 *     здесь: каталог инструментов собирается на JVM-тестах без Android, и обращение
 *     к AndroidKeyStore из конструктора сломало бы сборку каталога.
 *  2. `POST {baseUrl}/chat/completions` с моделью [MODEL] и коротким системным
 *     промптом «ищи и отвечай кратко со ссылками».
 *  3. Тело — **байты UTF-8**: `toByteArray(Charsets.UTF_8)` и заголовок
 *     `charset=utf-8`. Иначе кириллица в запросе уезжает как мусор (на десктопе это
 *     и воспроизводилось: модель получала «битую кодировку» вместо вопроса).
 *  4. Источники достаются из `choices[0].message.annotations[].url_citation` — там
 *     их отдаёт эта площадка; дополнительно понимаются `citations[]` и
 *     `search_results[]` (другие шлюзы кладут туда же) и, в последнюю очередь,
 *     голые URL из текста.
 *  5. Провайдер молчит дольше [SILENCE_MS] — вызов снимается (`Call.cancel()`), и
 *     модель получает честное «Поиск не удался». Выдуманных данных инструмент не
 *     возвращает никогда: пустой ответ — это ошибка, а не «примерно так».
 *
 * Подтверждения нет: поиск ничего не меняет на устройстве, а задержка на диалог
 * «разрешить поиск?» раздражала бы сильнее, чем экономила.
 *
 * [client] подменяем для JVM-тестов на MockWebServer: настоящая сеть в тесте невозможна.
 */
internal class WebSearchTool(
    private val credentials: () -> ProviderCredentials?,
    private val client: OkHttpClient? = null,
) : LocalDeviceTool {

    private companion object {
        /** Имя инструмента: уходит провайдеру и ищется по нему же в каталоге. */
        const val NAME = "web_search"

        /** Модель с собственным поиском. `sonar-pro` — компромисс скорости и качества. */
        const val MODEL = "perplexity/sonar-pro"

        /** Медиатип тела запроса. `charset=utf-8` — явно: кириллица не должна зависеть от догадок сервера. */
        const val JSON = "application/json; charset=utf-8"

        /** Потолок запроса: длиннее — это уже не поиск, а пересказ документа. */
        const val MAX_QUERY_CHARS = 400

        /** Потолок текста ответа: история чата не должна съедать окно контекста поиском. */
        const val MAX_ANSWER_CHARS = 6000

        /** Сколько источников показывать модели: больше десяти она всё равно не цитирует. */
        const val MAX_SOURCES = 10

        /** Терпение к молчанию провайдера: поиск идёт секунды, минута — уже сбой. */
        const val SILENCE_MS = 60_000L

        /** Системный промпт: короткий, потому что он уходит в каждый вызов. */
        const val SYSTEM_PROMPT =
            "Ты ищешь информацию в интернете и отвечаешь по актуальным данным. " +
                "Отвечай кратко, по делу, на языке вопроса. " +
                "Каждый факт, который ты берёшь из поиска, помечай номером источника в квадратных скобках — [1], [2]."

        /** Голые ссылки в тексте — последний шанс, если структурированных источников нет. */
        val URL_REGEX = Regex("""https?://[^\s)\]"'<>,]+""")
    }

    /**
     * Клиент — свой: у поиска свой профиль ожидания. Картинка и поток чата не должны
     * делить с ним таймауты: генерация ждёт минутами, ответ чата — секундами.
     */
    private val http: OkHttpClient by lazy { client ?: defaultClient() }

    override val schema: LocalToolSchema = LocalToolSchema(
        name = NAME,
        description = "Найти информацию в интернете и получить ответ со ссылками на источники. " +
            "Используй для всего, что зависит от текущего момента и чего нет в твоих знаниях: " +
            "погода, курсы валют, новости, цены, расписания, свежие события, факты после твоего обучения. " +
            "Возвращает текст ответа и список URL — ссылайся на них номерами [1], [2]. " +
            "Не нужен для вычислений, переписывания текста и вопросов о самом собеседнике.",
        parametersJson = """{"type":"object","properties":{"query":{"type":"string","description":"Запрос для поиска в интернете"}},"required":["query"]}""",
    )

    override val requiresConfirmation: Boolean = false

    override suspend fun execute(argumentsJson: String): LocalToolResult = guardedSuspend(NAME) {
        val args = arguments(argumentsJson) ?: return@guardedSuspend badArguments()

        val query = args.optString("query").trim()
        if (query.isEmpty()) {
            return@guardedSuspend toolError("Не указан обязательный аргумент query — нечего искать.")
        }
        if (query.length > MAX_QUERY_CHARS) {
            return@guardedSuspend toolError(
                "Запрос слишком длинный: ${query.length} символов при пределе $MAX_QUERY_CHARS. " +
                    "Сформулируй короче.",
            )
        }

        // Ключ спрашивается на каждый вызов: человек мог сменить профиль или стереть ключ
        // прямо посреди сессии, и поиск обязан это увидеть, а не работать старым.
        val active = credentials()
        if (active == null || active.apiKey.isBlank()) {
            return@guardedSuspend toolError(
                "Поиск не удался: у активного профиля нет ключа API. " +
                    "Настройки → Провайдер → ключ PlusVibe.",
            )
        }

        val request = Request.Builder()
            .url(LocalProviderUrls.chatCompletionsUrl(active.baseUrl))
            .header("Authorization", "Bearer ${active.apiKey}")
            .header("Content-Type", JSON)
            .header("Accept", JSON)
            .post(requestBody(query))
            .build()

        val body = try {
            callProvider(request)
        } catch (cancel: CancellationException) {
            // Отмену хода нельзя превращать в результат: guardedSuspend её пробросит дальше.
            throw cancel
        } catch (failure: SearchFailure) {
            return@guardedSuspend toolError(failure.message ?: "Поиск не удался")
        } catch (io: IOException) {
            return@guardedSuspend toolError(
                "Поиск не удался: сеть недоступна (${io.message ?: io.javaClass.simpleName}).",
            )
        } catch (error: Exception) {
            return@guardedSuspend toolError("Поиск не удался: ${error.message ?: error.javaClass.simpleName}")
        }

        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return@guardedSuspend toolError(
                "Поиск не удался: провайдер ответил не JSON — ${body.trim().take(200)}",
            )

        val message = root.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
        val answer = message?.optString("content").orEmpty().trim()
        if (answer.isEmpty()) {
            return@guardedSuspend toolError(
                "Поиск не удался: провайдер вернул пустой ответ (модель $MODEL). " +
                    "Переформулируй запрос или скажи человеку, что поиск сейчас не отвечает.",
            )
        }

        toolOk(resultText(answer, sourcesOf(root, answer)))
    }

    /** Тело запроса: строки уходят байтами UTF-8, а не в кодировке платформы. */
    private fun requestBody(query: String): okhttp3.RequestBody {
        val payload = JSONObject().apply {
            put("model", MODEL)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", SYSTEM_PROMPT) })
                    put(JSONObject().apply { put("role", "user"); put("content", query) })
                },
            )
            // Ноль, а не «как получится»: поиск должен отвечать по фактам, а не фантазировать.
            put("temperature", 0.2)
        }
        return payload.toString().toByteArray(Charsets.UTF_8).toRequestBody(JSON.toMediaType())
    }

    /**
     * Вызов провайдера с потолком ожидания.
     *
     * `Call.execute()` — блокирующий, и отмена корутины его не прерывает. Поэтому рядом
     * висит сторож: через [SILENCE_MS] он снимает вызов, `execute()` падает
     * `IOException("Canceled")`, и это единственный случай, когда мы говорим
     * «провайдер не ответил», а не «сеть недоступна».
     */
    private suspend fun callProvider(request: Request): String = withContext(Dispatchers.IO) {
        val call = http.newCall(request)
        val silent = AtomicBoolean(false)
        coroutineScope {
            val watchdog = launch {
                delay(SILENCE_MS)
                silent.set(true)
                call.cancel()
            }
            try {
                call.execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw SearchFailure(
                            "Поиск не удался: площадка ответила HTTP ${response.code} — " +
                                text.trim().take(300).ifEmpty { "тело ответа пустое" },
                        )
                    }
                    text
                }
            } catch (io: IOException) {
                if (silent.get()) {
                    throw SearchFailure(
                        "Поиск не удался: провайдер не ответил за ${SILENCE_MS / 1000} с. " +
                            "Скажи человеку, что поиск сейчас недоступен, и не выдумывай данные.",
                    )
                }
                throw io
            } finally {
                watchdog.cancel()
            }
        }
    }

    /**
     * Источники в порядке приоритета: `annotations` (так отдаёт эта площадка), затем
     * `citations`, затем `search_results`, затем голые URL из самого текста.
     *
     * Дубликаты по URL схлопываются: одна и та же страница часто приходит и аннотацией,
     * и цитатой, и модели незачем видеть её дважды.
     */
    private fun sourcesOf(root: JSONObject, answer: String): List<String> {
        val found = LinkedHashMap<String, String>()

        fun add(url: String, title: String) {
            val clean = url.trim().trimEnd('.', ',', ')', ']')
            if (clean.isEmpty() || clean in found) return
            found[clean] = title.trim()
        }

        root.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optJSONArray("annotations")
            ?.let { array ->
                for (index in 0 until array.length()) {
                    val citation = array.optJSONObject(index)?.optJSONObject("url_citation")
                        ?: array.optJSONObject(index)
                        ?: continue
                    add(citation.optString("url"), citation.optString("title"))
                }
            }

        root.optJSONArray("citations")?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.opt(index)
                if (item is JSONObject) {
                    add(item.optString("url"), item.optString("title"))
                } else {
                    add(item?.toString().orEmpty(), "")
                }
            }
        }

        root.optJSONArray("search_results")?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(item.optString("url"), item.optString("title"))
            }
        }

        if (found.isEmpty()) {
            URL_REGEX.findAll(answer).forEach { match -> add(match.value, "") }
        }

        return found.entries.take(MAX_SOURCES).map { (url, title) ->
            if (title.isEmpty()) url else "$title — $url"
        }
    }

    /** Текст для модели: ответ, затем нумерованные источники. Пустых источников не выдумываем. */
    private fun resultText(answer: String, sources: List<String>): String {
        val text = buildString {
            append("Ответ (поиск в интернете, $MODEL):\n")
            append(if (answer.length > MAX_ANSWER_CHARS) answer.take(MAX_ANSWER_CHARS) + "…" else answer)
            append("\n\n")
            if (sources.isEmpty()) {
                append("Источники: провайдер не вернул ссылок — опирайся на текст ответа, ")
                append("но помни, что проверить его по ссылке нельзя.")
            } else {
                append("Источники:\n")
                sources.forEachIndexed { index, source -> append("${index + 1}. $source\n") }
            }
        }
        return text.trimEnd()
    }

    /** Внутренний сбой с готовым текстом для модели (не исключение сети). */
    private class SearchFailure(message: String) : Exception(message)

    /**
     * Свой клиент: поиск идёт долго, но не бесконечно.
     *
     * connect 20 с — у площадки быстрый TLS; read 120 с — запас под длинный ответ
     * `sonar-reasoning-pro`; потолок ожидания всё равно режет сторож на [SILENCE_MS].
     */
    private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}