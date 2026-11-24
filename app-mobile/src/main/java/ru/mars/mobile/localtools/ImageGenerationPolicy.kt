package ru.mars.mobile.localtools

import org.json.JSONObject
import java.util.Locale

/**
 * Политика генерации картинки: что разрешено отправлять и что делать с ответом.
 *
 * Отдельно от [GenerateImageTool] и без `android.*` намеренно: ограничения площадки
 * проверены живыми запросами (§3 контракта, 30.10), и их проверяет JVM-тест, а не
 * телефон. Сеть и файлы — в инструменте, арифметика и разбор — здесь.
 *
 * Все числа ниже — не догадки:
 *  - `size` менее 921 600 пикселей площадка отвергает дословно:
 *    `image area must be at least 921600 pixels` (512×512 → отказ, 1024×1024 → JPEG);
 *  - лимит частоты даёт `TooManyRequests`, значит нужен повтор с паузой;
 *  - ссылка на JPEG живёт 24 часа (`X-Tos-Expires=86400`) — скачивать сразу.
 */
internal object ImageGenerationPolicy {

    /** Модель площадки по умолчанию: быстрая, проверена 30.10 на живом запросе. */
    const val DEFAULT_MODEL = "seedream-5.0-flash"

    /** Модели, которые платформа принимает на `/images/generations`. */
    val MODELS: List<String> = listOf(
        "seedream-5.0-pro",
        "seedream-5.0-flash",
        "seedream-5.0-lite",
        "seedream-4.5",
        "seedream-4.0",
        "seededit-3.0",
    )

    /** Минимальная площадь картинки: ниже — отказ площадки до всякой генерации. */
    const val MIN_AREA = 921_600L

    /** Верхняя граница стороны: больше площадка всё равно не отдаёт, а запрос весит больше. */
    const val MAX_SIDE = 4096

    /** Размер по умолчанию: 1024×1024 = 1 048 576 пикселей, проверено. */
    const val DEFAULT_SIZE = "1024x1024"

    /** Попыток всего, включая первую: 3 повтора закрывают короткий лимит частоты. */
    const val MAX_ATTEMPTS = 3

    /** Базовая пауза перед повтором; удваивается с каждой попыткой. */
    const val RETRY_BASE_MS = 1_500L

    /** Предел на скачивание JPEG: 20 МБ — с запасом для 4096×4096. */
    const val MAX_DOWNLOAD_BYTES = 20 * 1024 * 1024

    /** Предел длины промпта: площадка режет длинные, а 4000 символов хватает любой идеи. */
    const val MAX_PROMPT_CHARS = 4_000

    /**
     * Проверка и нормализация `size`.
     *
     * Возвращает `Result.success("ШxВ")` либо `Result.failure` с русской причиной для
     * модели: «почему нельзя» полезнее, чем повтор вызова с тем же размером.
     */
    fun normalizeSize(raw: String?): Result<String> {
        val value = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (value.isEmpty()) return Result.success(DEFAULT_SIZE)

        // Принимаем и `1024x1024`, и `1024×1024`: второе — как люди пишут в чате.
        val parts = value.replace('×', 'x').replace('*', 'x').split('x')
        if (parts.size != 2) {
            return Result.failure(IllegalArgumentException("$SIZE_REFUSAL (получено «$raw»)"))
        }
        val width = parts[0].trim().toIntOrNull()
        val height = parts[1].trim().toIntOrNull()
        if (width == null || height == null || width <= 0 || height <= 0) {
            return Result.failure(IllegalArgumentException("$SIZE_REFUSAL (получено «$raw»)"))
        }
        if (width > MAX_SIDE || height > MAX_SIDE) {
            return Result.failure(
                IllegalArgumentException(
                    "Сторона $width×$height больше предела $MAX_SIDE: используй ${DEFAULT_SIZE} " +
                        "или другой разумный размер",
                ),
            )
        }
        val area = width.toLong() * height.toLong()
        if (area < MIN_AREA) {
            return Result.failure(
                IllegalArgumentException(
                    "Слишком маленький размер $width×$height ($area пикселей). Площадка " +
                        "принимает от $MIN_AREA пикселей — бери ${DEFAULT_SIZE} или больше",
                ),
            )
        }
        return Result.success("${width}x$height")
    }

    /**
     * Промпт: одна строка без крайних пробелов, пустой — ошибка.
     *
     * Переводы строк не схлопываем, а нормализуем в пробел: тело запроса — JSON, и
     * многострочный промпт площадка принимает, но провайдеры одинаково плохо читают
     * пустые строки внутри подсказки.
     */
    fun normalizePrompt(raw: String?): String = raw.orEmpty().trim().replace(Regex("\\s+"), " ")

    /** Пауза перед повтором №[attempt] (1 — после первой неудачи): 1.5 с, 3 с. */
    fun retryDelayMs(attempt: Int): Long {
        val step = (attempt - 1).coerceAtLeast(0)
        return RETRY_BASE_MS shl step.coerceAtMost(4)
    }

    /**
     * Текст отказа площадки.
     *
     * Разбираем не только HTTP-код: при `400` тело несёт `error.message`, которую
     * модель должна увидеть дословно («image area must be at least 921600 pixels»),
     * иначе она будет повторять тот же запрос.
     */
    fun describeError(code: Int, body: String): String {
        val message = errorMessage(body)
        return when {
            message != null -> "Площадка отказала ($code): $message"
            code == 401 || code == 403 -> "Ключ Yeta не принят ($code): проверь его в настройках"
            code == 429 -> "Превышен лимит запросов Yeta ($code)"
            code in 500..599 -> "Площадка недоступна ($code): попробуй позже"
            else -> "Площадка ответила $code" + (body.trim().take(200).takeIf { it.isNotEmpty() }?.let { ": $it" } ?: "")
        }
    }

    /** Ошибка лимита частоты: её лечит повтор с паузой, остальные — нет. */
    fun isRetryable(code: Int, body: String): Boolean {
        if (code == 429) return true
        val lower = body.lowercase(Locale.ROOT)
        // Площадка на лимит иногда отдаёт 400/403 с текстом про rate limit.
        if (code in 400..499 && code != 401 && code != 403) {
            return lower.contains("too many requests") || lower.contains("rate limit")
        }
        return false
    }

    /** `error.message` из тела ответа; null — поле отсутствует или тело не JSON. */
    fun errorMessage(body: String): String? = runCatching {
        val error = JSONObject(body).opt("error")
        when (error) {
            is JSONObject -> error.optString("message").takeIf { it.isNotBlank() }
            is String -> error.takeIf { it.isNotBlank() }
            else -> JSONObject(body).optString("message").takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    /** Тело запроса генерации. `n` всегда 1: инструмент отдаёт одну картинку. */
    fun requestBody(prompt: String, size: String, model: String = DEFAULT_MODEL): String =
        JSONObject()
            .put("model", model)
            .put("prompt", prompt)
            .put("n", 1)
            .put("size", size)
            .toString()

    /**
     * URL картинки из ответа: `data[0].url`.
     *
     * null — ответ без данных (провайдер вернул `data: []` или ошибку в 200). Тогда
     * вызывающий отвечает «площадка не вернула картинку», а не сохраняет пустоту.
     */
    fun imageUrl(body: String): String? = runCatching {
        JSONObject(body).optJSONArray("data")
            ?.optJSONObject(0)
            ?.optString("url")
            ?.takeIf { it.startsWith("http") }
    }.getOrNull()

    /** Дословный текст отказа на неверный размер: одна формулировка на все случаи. */
    private const val SIZE_REFUSAL =
        "Нужен размер вида «1024x1024» (ширина x высота): площадка принимает только " +
            "WxH от 921600 пикселей"
}
