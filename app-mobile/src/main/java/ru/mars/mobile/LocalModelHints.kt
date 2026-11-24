package ru.mars.mobile

import java.util.Locale

/**
 * Человеческое название модели: короткое имя, назначение, принимает ли картинки.
 *
 * Зачем на телефоне, если есть каталог провайдера: `GET /models` отдаёт 84 строки
 * вида `doubao-seed-2.1-turbo` без единого слова о том, зачем модель. На сервере у Марса
 * свой каталог с подписями, здесь — его телефонная проекция (контракт волны 9, §2).
 *
 * Правила, которые нельзя нарушать:
 *  - Ключи — только id, реально присутствующие в живом `/models` (проверено 30.10).
 *    `claude-sonnet-5.5:kiro`, `kimi-k2.8`, `kimi-k3.1-pro` из первой редакции контракта
 *    в каталоге провайдера ОТСУТСТВУЮТ, и подсказка для них была бы обещанием
 *    несуществующей модели.
 *  - [title] — только там, где имя читается однозначно. Незнакомый или непонятный id
 *    показывается как есть (`title == null`): сочинить «умную» подпись значит соврать
 *    о цене, окне и назначении.
 *  - [purpose] — только для моделей с подтверждённой ролью (конфиг сервера). Для
 *    остальных назначение пустое: «не знаю» честнее правдоподобной догадки.
 *  - [vision] — `null` значит «не знаем». Запрещать картинку по догадке нельзя: пустой
 *    `input_modalities` у провайдера — это «не сообщил», а не «не умеет».
 *  - Сравнение id — после [normalizeId]: регистр, пробелы и префикс посредника
 *    (`anthropic/claude-haiku-5.5`) не должны превращать знакомую модель в незнакомую.
 */
internal data class LocalModelHint(
    /** Человеческое название, напр. «Claude Haiku 5.5». */
    val title: String,
    /** Зачем модель: «быстрый основной», «код». Пусто — не знаем. */
    val purpose: String = "",
    /** Принимает картинки; `null` — неизвестно, тогда не блокируем. */
    val vision: Boolean? = null,
)

/**
 * Каталог подсказок: id модели → человеческое название, назначение, зрение.
 *
 * Источник названий: таблица §2 контракта, отфильтрованная по живому `/models`
 * (модели, которых провайдер не отдаёт, в каталог не попадают), плюс чат-модели
 * из живого ответа. Vision помечен только у подтверждённых: `google/gemini-3.8-flash`
 * и `qwen3.8-omni-flash`.
 */
internal object LocalModelHints {

    private fun hint(title: String, purpose: String = "", vision: Boolean? = null) =
        LocalModelHint(title = title, purpose = purpose, vision = vision)

    private val BY_ID: Map<String, LocalModelHint> = mapOf(
        // --- PlusVibe · из таблицы §2 контракта (проверены в /models) -------------------
        "claude-haiku-5.5" to hint("Claude Haiku 5.5", "быстрый основной"),
        "qwen/qwen3.8-max" to hint("Qwen 3.8 Max", "большой Qwen, длинные задачи"),
        "gpt-6.1-sol" to hint("GPT-6.1 Sol"),
        "google/gemini-3.8-flash" to hint(
            "Gemini 3.8 Flash",
            "быстрый, смотрит картинки",
            vision = true,
        ),
        "gpt-6-luna" to hint("GPT-6 Luna"),
        "claude-sonnet-5.5" to hint("Claude Sonnet 5.5", "средний Claude, кодинг"),
        "deepseek-v4.1-flash" to hint("DeepSeek V4.1 Flash", "дешёвый и быстрый"),
        "deepseek-v4.1-flash-1017" to hint("DeepSeek V4.1 Flash (1017)", "дешёвый и быстрый"),
        "deepseek-v4-flash-0731:free" to hint("DeepSeek V4 Flash 0731", "бесплатный, на пробу"),
        "qwen3.8-omni-flash" to hint("Qwen 3.8 Omni Flash", "быстрый, смотрит картинки", vision = true),
        "minimax/minimax-m3.1-flash" to hint("MiniMax M3.1 Flash", "быстрый универсал"),

        // --- PlusVibe · остальные чат-модели из живого /models --------------------------
        "anthropic/claude-haiku-4.5" to hint("Claude Haiku 4.5"),
        "anthropic/claude-opus-4.8" to hint("Claude Opus 4.8"),
        "anthropic/claude-opus-5" to hint("Claude Opus 5", "тяжёлая, сложные задачи"),
        "anthropic/claude-sonnet-4.6" to hint("Claude Sonnet 4.6"),
        "anthropic/claude-sonnet-5" to hint("Claude Sonnet 5"),
        "claude-fable-5" to hint("Claude Fable 5"),
        "claude-fable-5-1" to hint("Claude Fable 5.1"),
        "claude-opus-4.6" to hint("Claude Opus 4.6"),
        "claude-opus-4.7" to hint("Claude Opus 4.7"),
        "claude-opus-5.5" to hint("Claude Opus 5.5"),
        "deepseek/deepseek-v4-flash-0731" to hint("DeepSeek V4 Flash 0731"),
        "deepseek/deepseek-v4-pro-0813" to hint("DeepSeek V4 Pro 0813"),
        "doubao-seed-2.0-code" to hint("Doubao Seed 2.0 Code", "код"),
        "doubao-seed-2.0-lite" to hint("Doubao Seed 2.0 Lite"),
        "doubao-seed-2.0-mini" to hint("Doubao Seed 2.0 Mini"),
        "doubao-seed-2.1-turbo" to hint("Doubao Seed 2.1 Turbo"),
        "gemma-4" to hint("Gemma 4"),
        "glm-5.1" to hint("GLM 5.1"),
        "glm-5.3-flashx" to hint("GLM 5.3 FlashX"),
        "google/gemini-3.1-flash-lite" to hint("Gemini 3.1 Flash Lite"),
        "google/gemini-3.1-pro-preview" to hint("Gemini 3.1 Pro (preview)"),
        "google/gemini-3.5-flash" to hint("Gemini 3.5 Flash"),
        "google/gemini-4-argon" to hint("Gemini 4 Argon"),
        "gpt-6-astra" to hint("GPT-6 Astra"),
        "gpt-6-sol" to hint("GPT-6 Sol"),
        "gpt-6.1-terra" to hint("GPT-6.1 Terra"),
        "grok-4.3" to hint("Grok 4.3", "свежая и быстрая"),
        "grok-4.7" to hint("Grok 4.7", "свежая и быстрая"),
        "llama-4-maverick" to hint("Llama 4 Maverick"),
        "mimo-v2.6-flash" to hint("MiMo 2.6 Flash"),
        "mimo-v2.6-pro" to hint("MiMo 2.6 Pro"),
        "minimax/minimax-m2.5" to hint("MiniMax M2.5"),
        "minimax/minimax-m2.7" to hint("MiniMax M2.7"),
        "minimax/minimax-m3" to hint("MiniMax M3"),
        "moonshotai/kimi-k2.7-code" to hint("Kimi K2.7 Code", "код"),
        "moonshotai/kimi-k3" to hint("Kimi K3"),
        "muse-spark-1.3" to hint("Muse Spark 1.3"),
        "nemotron-3-super-120b-a12b" to hint("Nemotron 3 Super 120B"),
        "nemotron-3.5-lightning" to hint("Nemotron 3.5 Lightning"),
        "openai/gpt-5.6-luna" to hint("GPT-5.6 Luna"),
        "openai/gpt-5.6-sol" to hint("GPT-5.6 Sol"),
        "openai/gpt-5.6-terra" to hint("GPT-5.6 Terra"),
        "openai/gpt-oss-120b" to hint("GPT-OSS 120B"),
        "perplexity/sonar" to hint("Sonar"),
        "perplexity/sonar-pro" to hint("Sonar Pro", "поиск в вебе с источниками"),
        "perplexity/sonar-reasoning-pro" to hint("Sonar Reasoning Pro", "поиск в вебе с рассуждением"),
        "qwen/qwen3.7-plus" to hint("Qwen 3.7 Plus"),
        "qwen3.6-35b" to hint("Qwen 3.6 35B"),
        "qwen3.8-2.4t-a95b" to hint("Qwen 3.8 2.4T", "тяжёлая, сложные задачи"),
        "qwen3.8-27b" to hint("Qwen 3.8 27B"),
        "qwen3.8-flash" to hint("Qwen 3.8 Flash", "быстрый"),
        "tencent/hy3" to hint("Hunyuan 3"),
        "x-ai/grok-4.6" to hint("Grok 4.6"),
        "x-ai/grok-build-0.1" to hint("Grok Build 0.1"),
        "xiaomi/mimo-v2.5" to hint("MiMo 2.5"),
        "xiaomi/mimo-v2.5-pro" to hint("MiMo 2.5 Pro"),
        "z-ai/glm-5.2" to hint("GLM 5.2"),
        "z-ai/glm-5.3" to hint("GLM 5.3"),
        "z-ai/glm-5.3-flash" to hint("GLM 5.3 Flash", "быстрый Zhipu, дешёвый"),

        // --- Yeta Platform (https://platform.yeta.ai/v1) --------------------------------
        "deepseek-v4.1-flash-uncensored" to hint(
            "DeepSeek V4.1 Flash (без фильтров)",
            "дешёвый и быстрый · без цензуры",
        ),
        "glm-5.3-flash" to hint("GLM 5.3 Flash", "быстрый Zhipu, дешёвый"),

        // --- Z.ai / GLM (https://api.z.ai/api/paas/v4) ---------------------------------
        "glm-4.5-flash" to hint("GLM 4.5 Flash", "бесплатный тариф Z.ai"),
    )

    /**
     * Привести id к виду каталога.
     *
     * Регистр и пробелы — очевидно; префикс `vendor/` срезается, только если без него id
     * есть в каталоге (`anthropic/claude-sonnet-4.6` → ключ не найден, id остаётся целым;
     * а вот `x-ai/grok-4.7` не срезается в `grok-4.7` — это разные записи, и подменять
     * одну другой значит показывать не ту модель).
     */
    internal fun normalizeId(raw: String): String {
        val trimmed = raw.trim().lowercase(Locale.ROOT)
        if (trimmed.isEmpty()) return trimmed
        if (BY_ID.containsKey(trimmed)) return trimmed
        val slash = trimmed.lastIndexOf('/')
        if (slash >= 0 && slash < trimmed.length - 1) {
            val tail = trimmed.substring(slash + 1)
            if (BY_ID.containsKey(tail)) return tail
        }
        return trimmed
    }

    /** Подсказка для id; `null` — модель не из каталога подсказок. */
    fun forId(rawId: String): LocalModelHint? = BY_ID[normalizeId(rawId)]

    /**
     * Название для показа. Неизвестный id отдаётся как есть — честнее пустого места.
     * Пустой/пробельный id — пустая строка, а не «?».
     */
    fun title(rawId: String): String {
        val raw = rawId.trim()
        if (raw.isEmpty()) return ""
        return forId(raw)?.title ?: raw
    }

    /** Назначение модели; пустая строка — не знаем (в UI строку не рисуем). */
    fun purpose(rawId: String): String = forId(rawId)?.purpose.orEmpty()

    /** Принимает ли модель картинки по таблице: `true` — знаем, `null` — смотри каталог. */
    fun vision(rawId: String): Boolean? = forId(rawId)?.vision

    /** Сколько моделей с человеческим названием (подпись в пикере и проверка в тесте). */
    val knownCount: Int get() = BY_ID.size
}
