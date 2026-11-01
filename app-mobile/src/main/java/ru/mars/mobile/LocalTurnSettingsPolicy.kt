package ru.mars.mobile

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import ru.mars.network.local.LocalTurnSettings
import ru.mars.network.local.SamplerParam
import ru.mars.network.local.SamplerParams
import ru.mars.network.local.SettingsSource

/**
 * Логика шторки «Параметры хода» — целиком и без Android.
 *
 * Почему отдельным объектом, а не внутри шторки: у параметров сэмплирования есть ровно
 * три места, где легко разойтись, — границы, формат подписи и перевод «пустое поле».
 * Если они живут в UI-коде, то проверяются только руками на телефоне, а «0,7» и «0.7»
 * начинают вести себя по-разному в поле ввода и в пилюле. Здесь всё это — чистые
 * функции над [LocalTurnSettings] и [SamplerParam], поэтому их можно прогнать
 * JVM-тестом без эмулятора (см. `LocalTurnSettingsPolicyTest`).
 *
 * Контракт значений: `null` — «провайдер решает сам», поле в запрос не уходит. Это не
 * «ноль» и не «дефолт модели»: ноль у температуры — валидное значение и означает
 * детерминированный выбор, а `null` означает «мы не вмешиваемся вовсе». Смешивать их
 * нельзя, поэтому и в UI пустое поле показывается как «провайдер сам», а не как «0».
 */
object LocalTurnSettingsPolicy {

    /** Подпись состояния «мы не отправляем поле, решает провайдер». */
    const val PROVIDER_SELF = "провайдер сам"

    /** Подпись состояния «значение совпадает с дефолтом приложения/модели». */
    const val APP_DEFAULT = "по умолчанию"

    /**
     * Сколько стоп-последовательностей принимаем.
     *
     * Восемь — предел, который держат OpenAI-совместимые площадки; больше провайдер
     * всё равно отклонит запрос целиком, и человек потеряет ход из-за одного лишнего
     * чипа. Предел проверяется здесь, чтобы шторка не обещала того, что не уйдёт.
     */
    const val MAX_STOPS = 8

    /**
     * Порядок строк в шторке — явный, а не порядок объявления [SamplerParam].
     *
     * Порядок объявления enum'а принадлежит core-network и может поменяться при
     * добавлении параметра; от этого шторка не должна перетасовываться. Здесь —
     * порядок «от вкуса к технике»: сначала то, что меняет характер ответа, потом
     * жёсткие пределы.
     *
     * [SamplerParam.STOP] в списке отсутствует намеренно: это не число, у него в
     * шторке отдельный блок с чипами.
     */
    private val NUMERIC_ORDER = listOf(
        SamplerParam.TEMPERATURE,
        SamplerParam.TOP_P,
        SamplerParam.TOP_K,
        SamplerParam.MIN_P,
        SamplerParam.PRESENCE_PENALTY,
        SamplerParam.FREQUENCY_PENALTY,
        SamplerParam.REPETITION_PENALTY,
        SamplerParam.SEED,
        SamplerParam.MAX_TOKENS,
    )

    /**
     * Одна строка шторки: параметр, подпись, границы и текущее значение.
     *
     * [min]/[max]/[step] уже приведены к тому, что реально можно ввести: для
     * `max_tokens` верхняя граница урезана потолком модели из каталога (см. [rows]),
     * иначе человек вводил бы число, которое провайдер молча обрежет.
     *
     * [providerDefault] — отформатированный дефолт приложения/модели, если он известен
     * ([SamplerParams.defaultValue]); `null` — дефолт неизвестен, и подпись «по
     * умолчанию» показывать нельзя (врала бы).
     */
    data class ParamRow(
        val param: SamplerParam,
        val label: String,
        val hint: String,
        val min: Double,
        val max: Double,
        val step: Double,
        val value: Double?,
        val providerDefault: String?,
    )

    /**
     * Строки для UI в фиксированном порядке [NUMERIC_ORDER].
     *
     * [modelMaxTokens] — окно/потолок модели из каталога (`/models`), если провайдер его
     * сообщил: `max_tokens` ограничивается им, потому что запрос с большим числом
     * провайдер либо обрежет, либо отклонит — в обоих случаях человек введёт значение,
     * которое не выполнится. Когда каталог молчит (`null`), остаётся широкий диапазон
     * провайдера: выдумывать потолок «на глаз» хуже, чем дать ввести и получить честную
     * ошибку от площадки.
     *
     * Параметр без числового диапазона ([SamplerParams.range] == null) в список не
     * попадает: числовое поле без границ нечем валидировать, а «любое число» — не
     * диапазон.
     */
    fun rows(settings: LocalTurnSettings, modelMaxTokens: Int?): List<ParamRow> {
        val current = numbers(settings)
        val result = ArrayList<ParamRow>(NUMERIC_ORDER.size)
        for (param in NUMERIC_ORDER) {
            val range = SamplerParams.range(param) ?: continue
            var min = range.start
            var max = range.endInclusive
            if (param == SamplerParam.MAX_TOKENS && modelMaxTokens != null && modelMaxTokens > 0) {
                max = minOf(max, modelMaxTokens.toDouble())
            }
            // Модель с окном меньше нижней границы параметра — вырожденный случай
            // (каталог отдал мусор). Оставляем одну точку, а не перевёрнутый диапазон:
            // перевёрнутый сделал бы любое значение «вне границ» и шторку несохраняемой.
            if (max < min) max = min
            val step = SamplerParams.step(param).takeIf { it > 0.0 } ?: FALLBACK_STEP
            val value = current[param]
            val default = SamplerParams.defaultValue(param)?.let { format(it, step) }
            result += ParamRow(
                param = param,
                label = SamplerParams.label(param),
                hint = SamplerParams.hint(param),
                min = min,
                max = max,
                step = step,
                value = value,
                providerDefault = default,
            )
        }
        return result
    }

    /**
     * Новые настройки с ОДНИМ изменённым полем.
     *
     * `null` в [newValue] — «провайдер сам»: поле становится `null` и в запрос не уходит.
     * Остальные поля (включая [LocalTurnSettings.reasoningEffort] и
     * [LocalTurnSettings.source]) переносятся как есть: источник настройки меняет тот,
     * кто сохраняет, а не правка одного числа — иначе «сбросить к модели» и «поменять
     * температуру» стали бы неотличимы по источнику.
     */
    fun apply(row: ParamRow, newValue: Double?, current: LocalTurnSettings): LocalTurnSettings =
        fromNumbers(
            values = numbers(current) + (row.param to newValue),
            stop = current.stop,
            reasoningEffort = current.reasoningEffort,
            source = current.source,
        )

    /**
     * Сброс к дефолтам модели/провайдера: все числовые поля `null`, стоп-лист пуст,
     * `reasoning_effort` не задан, источник — [SettingsSource.MODEL].
     *
     * Почему не «подставить дефолты приложения в поля»: подставленное число уйдёт в
     * запрос как наше решение и перебьёт провайдерский дефолт, который может отличаться
     * от нашего. Сброс обязан вернуть ровно то состояние, в котором запрос уходит
     * «как есть».
     *
     * [current] не читается намеренно: сброшенное состояние не зависит от того, что было
     * настроено, — иначе «сброс» протащил бы в результат какую-нибудь из старых правок.
     * Параметр оставлен ради симметрии с [apply] и ради будущих полей, которые сброс
     * может сохранять (например, выбор модели).
     */
    @Suppress("UNUSED_PARAMETER")
    fun resetToModelDefaults(current: LocalTurnSettings): LocalTurnSettings =
        fromNumbers(
            values = emptyMap(),
            stop = emptyList(),
            reasoningEffort = null,
            source = SettingsSource.MODEL,
        )

    /**
     * Подпись состояния для пилюли рядом с полем.
     *
     * `null` в значении → [PROVIDER_SELF]. Значение, совпадающее с дефолтом приложения,
     * → [APP_DEFAULT]: человеку важно видеть «я ничего не менял» даже когда число
     * вписано, иначе любое сохранение выглядит как ручная настройка.
     *
     * Сравнение идёт по отформатированным строкам, а не по `Double`: обе стороны проходят
     * через один и тот же [format] с одним и тем же шагом строки, поэтому равные числа
     * печатаются одинаково, а `0.7000000001` из JSON-эха не притворится дефолтом.
     *
     * Формально возвращаемый тип nullable — по контракту вызывающего; пустой подписи
     * не бывает, «нет состояния» здесь не существует.
     */
    fun badge(row: ParamRow): String? {
        val value = row.value ?: return PROVIDER_SELF
        val text = format(value, row.step)
        val default = row.providerDefault ?: return text
        return if (text == default) APP_DEFAULT else text
    }

    /**
     * Значение для поля ввода: пустая строка у «провайдер сам», иначе число в том же
     * формате, что и подпись состояния.
     *
     * Почему не `value.toString()`: поле обязано показывать «0,7», а не «0.7» — иначе
     * человек, стерев цифру и вписав её обратно по образцу, получит другой вид того же
     * числа и решит, что шторка его переписала.
     */
    fun valueText(row: ParamRow): String = row.value?.let { format(it, row.step) } ?: ""

    /**
     * Проверка ввода по параметру: границы и шаг берутся у провайдера.
     *
     * Пустая строка — валидный «провайдер сам» ([Result.success] с `null`), а не ошибка:
     * очистить поле — штатный способ вернуть параметр провайдеру.
     *
     * Точка и запятая равноправны: клавиатуры на русской раскладке ставят запятую, а
     * провайдеры в документации пишут точку — человек не обязан помнить, какая из них
     * «правильная».
     */
    fun validate(param: SamplerParam, raw: String): Result<Double?> {
        val range = SamplerParams.range(param)
            ?: return Result.failure(IllegalArgumentException("У этого параметра нет числового диапазона"))
        val step = SamplerParams.step(param).takeIf { it > 0.0 } ?: FALLBACK_STEP
        return validate(range.start, range.endInclusive, step, raw)
    }

    /**
     * То же самое, но по конкретной строке — с учётом потолка `max_tokens`, который
     * сообщил каталог модели.
     *
     * Отдельная перегрузка, а не параметр в [validate]: шторка знает модель, а
     * `LocalTurnSettingsPolicy.validate(param, raw)` обязан остаться пригодным там, где
     * каталога под рукой нет. Без этой перегрузки ввод 40000 при окне 8192 проходил бы
     * проверку и падал уже на провайдере.
     */
    fun validate(row: ParamRow, raw: String): Result<Double?> =
        validate(row.min, row.max, row.step, raw)

    /**
     * Стоп-лист после добавления [raw]: обрезка пробелов, без пустых, без дублей,
     * не больше [MAX_STOPS].
     *
     * Дубли отбрасываются, потому что две одинаковые последовательности в запросе — это
     * один и тот же стоп, только занимающий место в лимите; человеку же важно видеть,
     * что он уже добавил, а не две неотличимые пилюли.
     */
    fun addStop(stop: List<String>, raw: String): List<String> {
        val value = raw.trim()
        if (value.isEmpty()) return stop
        if (stop.size >= MAX_STOPS) return stop
        if (stop.any { it == value }) return stop
        return stop + value
    }

    /**
     * Целочисленный ли параметр.
     *
     * Один ответ на два вопроса — какую клавиатуру поднимать и можно ли вводить дробь:
     * если правило разъедется, `top_k` получит цифровую клавиатуру с запятой, а проверка
     * начнёт принимать 2,5 и округлять его молча.
     */
    fun isInteger(row: ParamRow): Boolean = decimalsFor(row.step) == 0

    /** Настройки с заменённым стоп-листом; остальные поля не трогаются. */
    fun withStop(current: LocalTurnSettings, stop: List<String>): LocalTurnSettings =
        fromNumbers(
            values = numbers(current),
            stop = stop,
            reasoningEffort = current.reasoningEffort,
            source = current.source,
        )

    // --- внутреннее ---------------------------------------------------------------------

    private fun validate(min: Double, max: Double, step: Double, raw: String): Result<Double?> {
        val text = raw.trim()
        if (text.isEmpty()) return Result.success(null)
        val value = text.replace(',', '.').toDoubleOrNull()
        if (value == null || !value.isFinite()) {
            return Result.failure(IllegalArgumentException("Это не число. Пример: ${sample(min, max, step)}"))
        }
        // Целые параметры (top_k, seed, max_tokens) с дробью провайдер молча усечёт или
        // отвергнет — лучше сказать сразу, чем гадать, почему 0,5 сея стало 0.
        if (isIntegerStep(step) && value != value.roundToLong().toDouble()) {
            return Result.failure(IllegalArgumentException("Только целое число"))
        }
        if (value < min || value > max) {
            return Result.failure(
                IllegalArgumentException("Допустимо от ${format(min, step)} до ${format(max, step)}"),
            )
        }
        return Result.success(value)
    }

    /**
     * Единственное чтение настроек в числовую карту: одна точка правды о том, какое поле
     * соответствует какому [SamplerParam].
     *
     * Без неё пришлось бы держать третий `when` рядом с [fromNumbers] — и первое же
     * добавление параметра разошлось бы между чтением и записью.
     */
    private fun numbers(settings: LocalTurnSettings): Map<SamplerParam, Double?> =
        LinkedHashMap<SamplerParam, Double?>(9).apply {
            put(SamplerParam.TEMPERATURE, settings.temperature)
            put(SamplerParam.TOP_P, settings.topP)
            put(SamplerParam.TOP_K, settings.topK?.toDouble())
            put(SamplerParam.MIN_P, settings.minP)
            put(SamplerParam.PRESENCE_PENALTY, settings.presencePenalty)
            put(SamplerParam.FREQUENCY_PENALTY, settings.frequencyPenalty)
            put(SamplerParam.REPETITION_PENALTY, settings.repetitionPenalty)
            put(SamplerParam.SEED, settings.seed?.toDouble())
            put(SamplerParam.MAX_TOKENS, settings.maxTokens?.toDouble())
        }

    /**
     * Единственная сборка [LocalTurnSettings]: отсутствующий в [values] параметр — `null`.
     *
     * Собирается конструктором, а не `copy`: полный список полей здесь служит проверкой,
     * что новый параметр не забыт ни в чтении, ни в записи. Дроби в целые поля
     * округляются, а не усекаются: усечение превращало бы 1,9 в 1 незаметно для человека.
     */
    private fun fromNumbers(
        values: Map<SamplerParam, Double?>,
        stop: List<String>,
        reasoningEffort: String?,
        source: SettingsSource,
    ): LocalTurnSettings {
        fun count(param: SamplerParam): Int? = values[param]?.roundToInt()
        return LocalTurnSettings(
            temperature = values[SamplerParam.TEMPERATURE],
            topP = values[SamplerParam.TOP_P],
            topK = count(SamplerParam.TOP_K),
            minP = values[SamplerParam.MIN_P],
            presencePenalty = values[SamplerParam.PRESENCE_PENALTY],
            frequencyPenalty = values[SamplerParam.FREQUENCY_PENALTY],
            repetitionPenalty = values[SamplerParam.REPETITION_PENALTY],
            seed = count(SamplerParam.SEED),
            maxTokens = count(SamplerParam.MAX_TOKENS),
            stop = stop,
            reasoningEffort = reasoningEffort,
            source = source,
        )
    }

    /**
     * Формат значения для пилюли и подсказок: число знаков после запятой задаёт шаг
     * параметра, хвостовые нули срезаются.
     *
     * Почему от шага, а не «до двух знаков всегда»: у `min_p` шаг 0,01, и «0,5» вместо
     * «0,50» — то же значение, а у `top_k` дробных знаков нет вовсе, и «40,0» читалось
     * бы как дробный параметр. Запятая — потому что весь интерфейс русский; ввод при
     * этом принимает и точку ([validate]).
     */
    private fun format(value: Double, step: Double): String {
        val decimals = decimalsFor(step)
        val text = if (decimals == 0) {
            value.roundToLong().toString()
        } else {
            String.format(Locale.ROOT, "%.${decimals}f", value)
        }
        // «0,70» подсказывало бы точность, которой у параметра нет.
        val trimmed = if (text.contains('.')) text.trimEnd('0').trimEnd('.') else text
        return trimmed.replace('.', ',')
    }

    /** Сколько знаков после запятой несёт шаг (не больше четырёх — дальше уже шум double). */
    private fun decimalsFor(step: Double): Int {
        if (step <= 0.0) return 0
        var scaled = step
        var decimals = 0
        while (decimals < 4 && abs(scaled - scaled.roundToLong().toDouble()) > EPSILON) {
            scaled *= 10.0
            decimals++
        }
        return decimals
    }

    private fun isIntegerStep(step: Double): Boolean = decimalsFor(step) == 0

    /** Пример корректного ввода для сообщения об ошибке — всегда внутри границ строки. */
    private fun sample(min: Double, max: Double, step: Double): String {
        val candidate = if (isIntegerStep(step)) 1.0 else 0.5
        return format(candidate.coerceIn(min, max), step)
    }

    /**
     * Аварийный шаг, если провайдерский пришёл нулевым или отрицательным: без шага нечем
     * ни отформатировать значение, ни проверить целочисленность, а падать из-за пустых
     * метаданных провайдера шторка не должна.
     */
    private const val FALLBACK_STEP = 0.01

    /** Допуск при сверке шага с целым числом — хвост double после умножения на 10. */
    private const val EPSILON = 1e-9
}
