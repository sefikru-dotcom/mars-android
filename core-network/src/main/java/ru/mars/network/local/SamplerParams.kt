package ru.mars.network.local

import java.util.Locale

/**
 * Вид значения параметра: от него зависит и приведение к границам, и текстовый вид.
 *
 * Почему не «всё Double»: `seed`, `top_k` и `max_tokens` провайдер ждёт целыми, и
 * `"max_tokens":200000.0` у части посредников отвергается как некорректный запрос, а у
 * части молча усекается. Вид живёт рядом с границами, чтобы тело запроса и ползунок в UI
 * не разошлись в том, что считать допустимым значением.
 */
enum class SamplerKind { DECIMAL, INTEGER, STRING_LIST }

/**
 * Единая таблица параметров сэмпсинга: границы, шаг, дефолт приложения и правило отправки.
 *
 * Почему таблица, а не поля по месту: до неё `temperature` и `maxTokens` жили в
 * [LocalProviderProfile] — одно значение на все модели, и при смене модели температура
 * прежней модели переезжала на новую. Плюс каждый экран и каждый транспорт решал сам,
 * какие поля отправлять, и «пустое» значение уезжало на провод нулём или единицей.
 * Здесь один источник правды: UI берёт из таблицы границы и шаг, транспорт — wire-имя и
 * правило «дефолт не отправляем».
 *
 * `defaultValue == null` означает «дефолта приложения нет»: пока человек не задал
 * значение, поле на провод НЕ уходит, и решает провайдер. Это не то же самое, что ноль:
 * ноль у `top_k` недопустим, а у `presence_penalty` — осмысленное «без штрафа».
 *
 * Параметры, которых нет у OpenAI-совместимых посредников (mirostat, xtc, typical_p),
 * сюда намеренно не попали: поле, которое никто не понимает, — это лишний шум в теле и
 * ложное обещание в интерфейсе.
 */
enum class SamplerParam(
    /** Имя поля в теле `POST /chat/completions`, как его ждёт OpenAI-совместимый API. */
    val wireName: String,
    val min: Double,
    val max: Double,
    val step: Double,
    /** Значение по умолчанию у приложения; null — «не задано человеком, не отправляем». */
    val defaultValue: Double?,
    val kind: SamplerKind = SamplerKind.DECIMAL,
    /**
     * Отправлять даже в значении по умолчанию.
     *
     * Нужно там, где наш дефолт может отличаться от дефолта посредника: наш `temperature`
     * 1.0 — это наше решение, а не гарантия того же у провайдера, и промолчать о нём
     * значит получить не тот режим, который человек видит на ползунке.
     */
    val alwaysSent: Boolean = false,
    /** Единица для подписи в UI; пусто — безразмерная величина. */
    val unit: String = "",
    /** Подсказка для человека: что параметр меняет в ответе, без формул. */
    val hint: String = "",
) {
    TEMPERATURE(
        wireName = "temperature",
        min = 0.0,
        max = 2.0,
        step = 0.01,
        defaultValue = 1.0,
        alwaysSent = true,
        hint = "выше — живее и неожиданнее, ниже — суше и повторяемее",
    ),

    TOP_P(
        wireName = "top_p",
        min = 0.0,
        max = 1.0,
        step = 0.01,
        defaultValue = 1.0,
        alwaysSent = true,
        hint = "ядро вероятностей: 1.0 — модель не отсекает редкие варианты",
    ),

    // Дефолта нет намеренно: поле понимают не все посредники (OpenAI его не принимает),
    // и отправлять «1» молча значит получить отказ запроса целиком у части провайдеров.
    TOP_K(
        wireName = "top_k",
        min = 1.0,
        max = 128.0,
        step = 1.0,
        defaultValue = null,
        kind = SamplerKind.INTEGER,
        hint = "сколько вариантов модель вообще рассматривает; поле знают не все провайдеры",
    ),

    MIN_P(
        wireName = "min_p",
        min = 0.0,
        max = 1.0,
        step = 0.01,
        defaultValue = null,
        hint = "отсекает варианты реже заданной доли от самого вероятного",
    ),

    PRESENCE_PENALTY(
        wireName = "presence_penalty",
        min = -2.0,
        max = 2.0,
        step = 0.01,
        defaultValue = 0.0,
        hint = "штраф за уже затронутые темы; 0 — без штрафа, минус — поощрение повторов",
    ),

    FREQUENCY_PENALTY(
        wireName = "frequency_penalty",
        min = -2.0,
        max = 2.0,
        step = 0.01,
        defaultValue = 0.0,
        hint = "штраф пропорционально числу повторов; 0 — без штрафа",
    ),

    // Дефолта нет: это параметр из мира llama.cpp, и у посредников, которые его не знают,
    // любое значение — отказ запроса, а не «мягкое» игнорирование.
    REPETITION_PENALTY(
        wireName = "repetition_penalty",
        min = 0.0,
        max = 2.0,
        step = 0.01,
        defaultValue = null,
        hint = "штраф за повторы в духе llama.cpp; понимают не все посредники",
    ),

    // Диапазон — весь Int: seed произвольный, а не «номер от 0». null = случайный выбор,
    // и именно поэтому здесь нет дефолта: фиксировать зерно без просьбы человека нельзя.
    SEED(
        wireName = "seed",
        min = Int.MIN_VALUE.toDouble(),
        max = Int.MAX_VALUE.toDouble(),
        step = 1.0,
        defaultValue = null,
        kind = SamplerKind.INTEGER,
        hint = "фиксирует выбор токенов: одинаковый seed даёт воспроизводимый ответ",
    ),

    // Потолок 200000, а не 4096: у современных моделей ответ бывает в десятки тысяч
    // токенов, а настоящий лимит всё равно знает только провайдер (см. max_output_tokens).
    MAX_TOKENS(
        wireName = "max_tokens",
        min = 1.0,
        max = 200_000.0,
        step = 1.0,
        defaultValue = null,
        kind = SamplerKind.INTEGER,
        alwaysSent = true,
        unit = "токенов",
        hint = "потолок длины ответа; пусто — лимит провайдера",
    ),

    // Значение — список строк, поэтому числовых границ нет (min = max = step = 0).
    // Дефолт приложения — пустой список: пусто значит «не отправляем», и это тот же
    // смысл, что null у числовых параметров.
    STOP(
        wireName = "stop",
        min = 0.0,
        max = 0.0,
        step = 0.0,
        defaultValue = null,
        kind = SamplerKind.STRING_LIST,
        hint = "последовательности, на которых ответ обрывается; пусто — не отправляем",
    ),
}

/**
 * Приведение значений и правило «дефолт не отправляем».
 *
 * Здесь же [ALWAYS_SENT] — но не отдельным списком, а выводом из [SamplerParam.alwaysSent]:
 * два независимых списка разъезжаются при первой же правке таблицы, а расхождение
 * «в UI поле помечено обязательным, а на провод не ушло» ищется долго.
 */
object SamplerParams {

    /** Параметры, которые уходят на провод даже в значении по умолчанию. */
    val ALWAYS_SENT: Set<SamplerParam> = SamplerParam.entries.filter { it.alwaysSent }.toSet()

    /**
     * Привести значение к границам и шагу параметра.
     *
     * Зачем приводить, а не отправлять как есть: значение приходит из UI и из сохранённой
     * меты сессии, то есть может быть старым (границы менялись) или испорченным. `NaN` и
     * бесконечность в JSON невыразимы вовсе — такое тело провайдер отвергнет целиком, а не
     * «поправит» одно поле, поэтому нечисло заменяется дефолтом (или нижней границей).
     *
     * Округление к шагу нужно не для красоты: сравнение с дефолтом в [isLeftToProvider]
     * идёт по значению, и `0.9999999999` после сериализации выглядел бы «заданным»
     * значением, хотя человек ползунок не двигал.
     */
    fun clamp(param: SamplerParam, value: Double): Double {
        if (!value.isFinite()) return param.defaultValue ?: param.min
        val bounded = value.coerceIn(param.min, param.max)
        return when (param.kind) {
            SamplerKind.INTEGER -> Math.round(bounded).toDouble()
            SamplerKind.DECIMAL -> roundToDecimals(bounded, decimalsOf(param.step))
            // Список строк числом не приводится: сюда попадает только сам список.
            SamplerKind.STRING_LIST -> bounded
        }
    }

    /**
     * Поле НЕ уходит на провод: `true` — параметр оставлен провайдеру, `false` — отправляем.
     *
     * Правила ровно два, и оба про честность тела запроса:
     *  - `null` — человек значение не задавал, подставлять своё нельзя;
     *  - значение равно дефолту приложения — оно ничего не сообщает о намерении человека,
     *    а провайдер в этом случае применит свой дефолт, который может быть другим.
     *
     * Исключение — [ALWAYS_SENT]: эти параметры отправляются и в дефолте, потому что наш
     * дефолт (`temperature` 1.0) — наше решение, а не гарантия того же у посредника.
     * У `max_tokens` своего дефолта нет вовсе, поэтому «отправляем всегда» здесь означает
     * «отправляем, как только задан».
     *
     * Сравнение идёт по приведённому значению — тому же, что уйдёт в тело: иначе
     * UI-значение с плавающей пылью считалось бы заданным.
     */
    fun isLeftToProvider(param: SamplerParam, value: Double?): Boolean {
        if (value == null) return true
        if (param.alwaysSent) return false
        val appDefault = param.defaultValue ?: return false
        return clamp(param, value) == appDefault
    }

    /**
     * Значение для подписи в UI.
     *
     * `null` показывается словами, а не пустой строкой: «провайдер сам» — это ответ, а
     * пустое место человек читает как «сломалось». Для [SamplerParam.SEED] формулировка
     * другая: отсутствие зерна — не «провайдер решит», а заведомо случайный выбор.
     */
    fun formatValue(param: SamplerParam, value: Double?): String {
        if (value == null) {
            return if (param == SamplerParam.SEED) "случайный" else "провайдер сам"
        }
        val clamped = clamp(param, value)
        return when (param.kind) {
            SamplerKind.INTEGER -> clamped.toLong().toString()
            SamplerKind.DECIMAL -> decimalText(clamped, decimalsOf(param.step))
            // Список строк рисуется в UI отдельными метками, числом его не показать.
            SamplerKind.STRING_LIST -> "—"
        }
    }

    /** Сколько знаков после запятой даёт шаг (`0.01` → 2, `1.0` → 0). */
    private fun decimalsOf(step: Double): Int {
        if (!step.isFinite() || step <= 0.0) return 0
        var scaled = step
        var decimals = 0
        while (decimals < 6 && scaled % 1.0 != 0.0) {
            scaled *= 10.0
            decimals += 1
        }
        return decimals
    }

    private fun roundToDecimals(value: Double, decimals: Int): Double {
        if (decimals <= 0) return Math.round(value).toDouble()
        var factor = 1.0
        repeat(decimals) { factor *= 10.0 }
        return Math.round(value * factor).toDouble() / factor
    }

    /**
     * Число без хвостовых нулей, но с одним знаком после точки у целых: «1.0», а не «1» —
     * у непрерывного ползунка единица без дроби читается как другой, целочисленный параметр.
     */
    private fun decimalText(value: Double, decimals: Int): String {
        if (decimals <= 0) return Math.round(value).toString()
        val text = String.format(Locale.US, "%.${decimals}f", value).trimEnd('0')
        return if (text.endsWith('.')) text + "0" else text
    }
}
