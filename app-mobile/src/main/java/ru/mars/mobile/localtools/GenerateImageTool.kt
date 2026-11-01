package ru.mars.mobile.localtools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.mars.mobile.YetaImageKeyStore
import ru.mars.network.local.LocalToolResult
import ru.mars.network.local.LocalToolSchema
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * `generate_image` — модель сама рисует картинку через Yeta Platform и кладёт её на диск.
 *
 * Почему отдельный инструмент, а не «просто текст»: модели картинок **не работают** через
 * `/chat/completions` (`seedream-4.5` отвечает `does not support this api`), поэтому звать
 * их может только инструмент, ходящий на `/images/generations` напрямую.
 *
 * Как устроено:
 *  1. Проверка ключа Yeta ([YetaImageKeyStore], отдельный алиас AndroidKeyStore). Ключа
 *     нет — понятная ошибка для модели, а не падение и не пустой запрос.
 *  2. Проверка размера ([ImageGenerationPolicy.normalizeSize]): площадка принимает только
 *     от 921 600 пикселей, и отказ дешевле показать до вызова API.
 *  3. `POST {base}/images/generations` с повтором на лимит частоты (3 попытки, пауза растёт).
 *  4. Немедленное скачивание JPEG в `cacheDir/generated/`: ссылка живёт 24 часа, хранить
 *     её вместо файла — значит потерять картинку через сутки.
 *
 * Что возвращается модели: путь к файлу и размер. Путь, а не base64: файл нужен ещё
 * экрану (показ и «Сохранить в галерею»), а base64 в истории съел бы окно контекста.
 *
 * Каталог — `cacheDir`, как требует контракт: сгенерированное — временное, и система
 * вправе его вычистить. Именно поэтому история хранит **копию** байтов (битая ссылка на
 * вычищенный файл честно говорит «картинка удалена», а не рисует битую иконку).
 *
 * Подтверждения нет: генерация ничего не меняет на устройстве и обратима (файл в кэше).
 *
 * [client] и [directory] подменяемы для тестов: настоящая сеть в JVM-тесте невозможна.
 */
private val GENERATE_IMAGE_PARAMETERS: String = """
{"type":"object","properties":{
"prompt":{"type":"string","description":"Описание картинки: сюжет, стиль, свет. На языке запроса человека"},
"size":{"type":"string","description":"Необязательно. «ширинаxвысота», например 1024x1024. Минимум 921600 пикселей, 512x512 площадка отвергает"}},
"required":["prompt"]}
""".trimIndent()


class GenerateImageTool(
    private val context: Context,
    private val client: OkHttpClient? = null,
    private val directory: File? = null,
) : LocalDeviceTool {

    private companion object {
        /** Путь эндпоинта относительно базы. */
        const val PATH = "/images/generations"

        /** Каталог картинок внутри `cacheDir`. */
        const val DIR = "generated"

        /** Медиатип запроса: тело — JSON. */
        const val JSON = "application/json"
    }

    /** Ключ Yeta читается на вызов, а не в конструкторе: каталог собирают без Android. */
    private val keys: YetaImageKeyStore by lazy { YetaImageKeyStore(context) }

    /**
     * Клиент — свой, а не общий: у генерации свой таймаут. Картинка на 4096×4096 может
     * рендериться десятки секунд, и общий клиент чата с 30-секундным read таймаутом
     * обрывал бы вызов посреди генерации.
     */
    private val http: OkHttpClient by lazy { client ?: defaultClient() }

    override val schema: LocalToolSchema = LocalToolSchema(
        name = "generate_image",
        description = "Сгенерировать картинку по текстовому описанию и показать её человеку. " +
            "Используй, когда просят нарисовать/сгенерировать изображение. Промпт — на языке " +
            "запроса, подробный: сюжет, стиль, свет. Размер необязателен, по умолчанию 1024x1024 " +
            "(минимум 921600 пикселей; 512x512 площадка отвергает).",
        parametersJson = GENERATE_IMAGE_PARAMETERS,
    )

    override val requiresConfirmation: Boolean = false

    override suspend fun execute(argumentsJson: String): LocalToolResult = guardedSuspend("generate_image") {
        val args = arguments(argumentsJson) ?: return@guardedSuspend badArguments()

        val prompt = ImageGenerationPolicy.normalizePrompt(args.optString("prompt"))
        if (prompt.isEmpty()) {
            return@guardedSuspend toolError("Не указан обязательный аргумент prompt — нечего рисовать.")
        }
        if (prompt.length > ImageGenerationPolicy.MAX_PROMPT_CHARS) {
            return@guardedSuspend toolError(
                "Промпт слишком длинный: ${prompt.length} символов при пределе " +
                    "${ImageGenerationPolicy.MAX_PROMPT_CHARS}. Опиши короче.",
            )
        }

        val size = ImageGenerationPolicy.normalizeSize(args.optString("size"))
            .getOrElse { error -> return@guardedSuspend toolError(error.message ?: "Неверный размер") }

        val apiKey = keys.apiKey()
        if (apiKey.isNullOrBlank()) {
            return@guardedSuspend toolError(
                "Нужен ключ Yeta в настройках: без него картинки не генерируются. " +
                    "Настройки → Провайдер → «Ключ Yeta (картинки)».",
            )
        }

        val model = args.optString("model").trim()
            .takeIf { it in ImageGenerationPolicy.MODELS }
            ?: keys.model()

        val url = try {
            requestImage(apiKey, prompt, size, model)
        } catch (tooLate: ImageGenerationCancelled) {
            return@guardedSuspend toolError(tooLate.message ?: "Генерация прервана")
        }

        val file = try {
            download(url, prompt)
        } catch (error: Exception) {
            return@guardedSuspend toolError(
                "Картинка сгенерирована, но не скачалась: ${error.message ?: error.javaClass.simpleName}. " +
                    "Ссылка живёт сутки — попробуй ещё раз.",
            )
        }
        toolOk(
            "Картинка готова: ${file.name} · ${size} · ${file.length() / 1024} КБ\n" +
                "Файл: ${file.absolutePath}",
        )
    }

    /** Вызов генерации с повтором: лимит частоты площадки лечится паузой, остальное — нет. */
    private suspend fun requestImage(
        apiKey: String,
        prompt: String,
        size: String,
        model: String,
    ): String = withContext(Dispatchers.IO) {
        val body = ImageGenerationPolicy.requestBody(prompt, size, model)
            .toRequestBody(JSON.toMediaType())

        var lastError = "площадка не ответила"
        for (attempt in 1..ImageGenerationPolicy.MAX_ATTEMPTS) {
            val request = Request.Builder()
                .url(YetaImageKeyStore.BASE_URL.trimEnd('/') + PATH)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", JSON)
                .post(body)
                .build()

            val (code, text) = try {
                http.newCall(request).execute().use { response ->
                    response.code to (response.body?.string().orEmpty())
                }
            } catch (io: java.io.IOException) {
                lastError = "сеть недоступна: ${io.message ?: io.javaClass.simpleName}"
                if (attempt == ImageGenerationPolicy.MAX_ATTEMPTS) break
                delay(ImageGenerationPolicy.retryDelayMs(attempt))
                continue
            }

            if (code in 200..299) {
                return@withContext ImageGenerationPolicy.imageUrl(text)
                    ?: throw ImageGenerationCancelled(
                        "Площадка ответила $code, но картинки в ответе нет: " +
                            text.trim().take(200),
                    )
            }

            lastError = ImageGenerationPolicy.describeError(code, text)
            // Повтор — только на лимит частоты: 400 на размер повторится так же 400,
            // и три попытки лишь растянули бы ответ модели.
            if (!ImageGenerationPolicy.isRetryable(code, text) ||
                attempt == ImageGenerationPolicy.MAX_ATTEMPTS
            ) {
                break
            }
            delay(ImageGenerationPolicy.retryDelayMs(attempt))
        }
        throw ImageGenerationCancelled(lastError)
    }

    /**
     * Скачать JPEG сразу: ссылка живёт 24 часа. Файл с уникальным именем — старые
     * генерации не перезаписываются, и открытый на экране файл не мутирует под пальцем.
     */
    private suspend fun download(url: String, prompt: String): File = withContext(Dispatchers.IO) {
        val dir = directory ?: File(context.cacheDir, DIR).apply { mkdirs() }
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName(prompt))

        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("скачивание картинки не удалось: HTTP ${response.code}")
            }
            val body = response.body ?: error("скачивание картинки: пустой ответ")
            if (body.contentLength() > ImageGenerationPolicy.MAX_DOWNLOAD_BYTES) {
                error("картинка больше ${ImageGenerationPolicy.MAX_DOWNLOAD_BYTES / 1024 / 1024} МБ")
            }
            body.byteStream().use { input ->
                file.outputStream().use { output -> copyBounded(input, output) }
            }
        }
        if (file.length() == 0L) {
            file.delete()
            error("скачивание картинки: got 0 байт")
        }
        file
    }

    /** Копия с потолком: `Content-Length` может врать (или отсутствовать при gzip). */
    private fun copyBounded(input: InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > ImageGenerationPolicy.MAX_DOWNLOAD_BYTES) {
                error("картинка больше ${ImageGenerationPolicy.MAX_DOWNLOAD_BYTES / 1024 / 1024} МБ")
            }
            output.write(buffer, 0, read)
        }
    }

    /**
     * Имя файла: время + усечённый промпт латиницей.
     *
     * Латиница, а не кириллица: файл открывается сторонними приложениями «Сохранить в
     * галерею» и проводником, и не-ASCII имя там превращается в мусор. Промпт остаётся
     * читаемой подсказкой, но из него берутся только `[a-z0-9]`.
     */
    private fun fileName(prompt: String): String {
        val slug = prompt.lowercase(Locale.ROOT)
            .map { if (it in 'a'..'z' || it in '0'..'9') it else ' ' }
            .joinToString("")
            .trim()
            .split(Regex("\\s+"))
            .take(6)
            .joinToString("-")
            .take(40)
            .trim('-')
        val stamp = System.currentTimeMillis()
        return if (slug.isEmpty()) "image-$stamp.jpeg" else "image-$stamp-$slug.jpeg"
    }

    /** Внутренний сбой с готовым текстом для модели (не исключение сети). */
    private class ImageGenerationCancelled(message: String) : Exception(message)

    /** Схема параметров — строго JSON-объект: провайдеры отвергают не-объект целиком. */


    /** Свой клиент: таймауты под долгую генерацию, а не под поток текста. */
    private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}
