package ru.mars.network.local

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import ru.mars.network.repository.JsonStringEncoding

/**
 * Откуда взялись значения хода — для подписи в интерфейсе.
 *
 * Зачем это видно человеку: одинаковые числа означают разное, если одни пришли из каталога
 * модели, а другие он выставил сам. Без пометки «почему у меня 0.3» превращается в поиск
 * виноватого, а не в настройку.
 */
enum class SettingsSource { DEFAULT, MODEL, SESSION }

/**
 * Настройки одного хода локального диалога.
 *
 * Почему явные поля, а не `Map<SamplerParam, Double>`: мапа принимает что угодно и молчит
 * об опечатке до момента отправки, а `topK: Int?` и `maxTokens: Int?` ловят «дробный
 * макс-токен» на компиляции. Плюс к каждой паре «параметр — значение» привязан тип, и
 * приведение к шагу остаётся в одном месте ([SamplerParams.clamp]).
 *
 * `null` значит «провайдер сам», а НЕ ноль и НЕ единицу: `presence_penalty = 0.0` — это
 * осознанное «без штрафа», а `null` — «мы об этом не просили». Разница видна в теле
 * запроса: `null` не добавляет поле вовсе.
 *
 * [stop] — не `null`, а пустой список: пустой набор стоп-последовательностей и «человек
 * их не задавал» дают одно и то же тело, и различать их нечем и незачем.
 */
data class LocalTurnSettings(
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val minP: Double? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val repetitionPenalty: Double? = null,
    /** `null` — зерно не зафиксировано, ответ на одном и том же запросе может отличаться. */
    val seed: Int? = null,
    val maxTokens: Int? = null,
    val stop: List<String> = emptyList(),
    /**
     * `reasoning_effort` (`low`/`medium`/`high`), если модель его поддерживает.
     *
     * Остаётся строкой, а не enum: набор значений у посредников разный, и закрытый список
     * в ядре отбрасывал бы значение, которое провайдер как раз понимает.
     */
    val reasoningEffort: String? = null,
    val source: SettingsSource = SettingsSource.DEFAULT,
) {

    /**
     * Переопределение сессии поверх дефолтов модели, поле за полем.
     *
     * Почему не «взять объект целиком»: сессия хранит только то, что человек реально
     * менял, и незаданные поля обязаны остаться дефолтами модели. Иначе смена модели
     * внутри сессии приносила бы с собой чужие значения — ровно та беда, из-за которой
     * параметры и переехали из профиля.
     *
     * `over.stop` пустой список считается «не переопределено»: пустой список неотличим от
     * «человек стёр все стоп-последовательности», и трактовать его как очистку значило бы
     * молча терять настройку модели при каждом merge. Очистка делается явной записью в
     * настройки сессии, а не через merge.
     */
    fun merge(over: LocalTurnSettings?): LocalTurnSettings {
        if (over == null) return this
        return copy(
            temperature = over.temperature ?: temperature,
            topP = over.topP ?: topP,
            topK = over.topK ?: topK,
            minP = over.minP ?: minP,
            presencePenalty = over.presencePenalty ?: presencePenalty,
            frequencyPenalty = over.frequencyPenalty ?: frequencyPenalty,
            repetitionPenalty = over.repetitionPenalty ?: repetitionPenalty,
            seed = over.seed ?: seed,
            maxTokens = over.maxTokens ?: maxTokens,
            stop = over.stop.takeIf { it.isNotEmpty() } ?: stop,
            reasoningEffort = over.reasoningEffort ?: reasoningEffort,
            source = SettingsSource.SESSION,
        )
    }

    /**
     * JSON для меты сессии.
     *
     * Пишется руками через [JsonStringEncoding], а не Moshi-адаптером data-класса:
     * `moshi-kotlin` тянет рефлексию и в этом модуле не подключён к тестам, а формат
     * простой и обязан оставаться читаемым в логах. Схема — поля этого класса как есть,
     * чтобы при чтении не понадобилась таблица соответствий.
     */
    fun toJson(): String {
        val body = StringBuilder(160)
        body.append('{')
        var first = true

        fun field(name: String, text: String) {
            if (!first) body.append(',')
            body.append('"').append(name).append("\":").append(text)
            first = false
        }

        temperature?.let { field("temperature", it.toString()) }
        topP?.let { field("topP", it.toString()) }
        topK?.let { field("topK", it.toString()) }
        minP?.let { field("minP", it.toString()) }
        presencePenalty?.let { field("presencePenalty", it.toString()) }
        frequencyPenalty?.let { field("frequencyPenalty", it.toString()) }
        repetitionPenalty?.let { field("repetitionPenalty", it.toString()) }
        seed?.let { field("seed", it.toString()) }
        maxTokens?.let { field("maxTokens", it.toString()) }
        if (stop.isNotEmpty()) {
            val array = StringBuilder("[")
            stop.forEachIndexed { index, marker ->
                if (index > 0) array.append(',')
                array.append(JsonStringEncoding.escape(marker))
            }
            array.append(']')
            field("stop", array.toString())
        }
        reasoningEffort?.let { field("reasoningEffort", JsonStringEncoding.escape(it)) }
        // Источник пишется всегда: без него разбор не отличит «настройки по умолчанию» от
        // мусора в мете, а сам объект по умолчанию иначе сериализовался бы в пустой `{}`.
        field("source", JsonStringEncoding.escape(source.name))
        body.append('}')
        return body.toString()
    }

    companion object {

        /**
         * Поля, которые умеет читать [fromJson]. Нужны, чтобы отличить наши настройки от
         * чужого JSON в той же мете сессии: объект без единого знакомого поля — не
         * «настройки по умолчанию», а мусор, и вернуть по нему пустые настройки значит
         * соврать, что человек ничего не задавал.
         */
        private val KNOWN_KEYS = setOf(
            "temperature", "topP", "topK", "minP", "presencePenalty", "frequencyPenalty",
            "repetitionPenalty", "seed", "maxTokens", "stop", "reasoningEffort", "source",
        )

        private val mapAdapter: JsonAdapter<Map<String, Any?>> = Moshi.Builder().build()
            .adapter<Map<String, Any?>>(
                Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
            )
            .lenient()

        /**
         * Прочитать настройки из меты сессии; `null` — читать нечего или там не наши данные.
         *
         * Почему `null`, а не исключение и не пустой объект: мета сессии переживает
         * обновления приложения, и её JSON может быть обрезан, перезаписан другой версией
         * или испорчен. Исключение уронило бы открытие диалога из-за настроек, которые
         * можно молча взять по умолчанию; пустой объект соврал бы, что настроек нет, и
         * стёр бы их при следующем сохранении. `null` внятно говорит «здесь ничего
         * пригодного» — вызывающий берёт `defaultsFor(...)`.
         */
        fun fromJson(raw: String?): LocalTurnSettings? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            val root = runCatching { mapAdapter.fromJson(text) }.getOrNull() ?: return null
            if (root.keys.none { it in KNOWN_KEYS }) return null
            return LocalTurnSettings(
                temperature = doubleOf(root["temperature"]),
                topP = doubleOf(root["topP"]),
                topK = intOf(root["topK"]),
                minP = doubleOf(root["minP"]),
                presencePenalty = doubleOf(root["presencePenalty"]),
                frequencyPenalty = doubleOf(root["frequencyPenalty"]),
                repetitionPenalty = doubleOf(root["repetitionPenalty"]),
                seed = intOf(root["seed"]),
                maxTokens = intOf(root["maxTokens"]),
                // Строки НЕ фильтруются по isNotBlank: «\n\n» (пустая строка как конец
                // абзаца) — самый частый стоп в диалоге, и фильтр молча съедал его, делая
                // круг JSON необратимым. Поймано тестом на круг, а не на глаз.
                stop = (root["stop"] as? List<*>).orEmpty().mapNotNull { it as? String },
                reasoningEffort = (root["reasoningEffort"] as? String)?.takeIf(String::isNotBlank),
                source = (root["source"] as? String)
                    ?.let { name -> SettingsSource.entries.firstOrNull { it.name == name } }
                    ?: SettingsSource.DEFAULT,
            )
        }

        /**
         * Дефолты хода для выбранной модели и профиля.
         *
         * Порядок слоёв: профиль — то, что человек уже настроил руками (старые профили
         * хранят `temperature`/`maxTokens`), каталог модели — то, что сообщил провайдер.
         * Каталог перекрывает профиль только там, где он действительно что-то сообщил:
         * `maxOutputTokens` из `max_output_tokens`/`max_completion_tokens`.
         *
         * Почему «ничего не выдумывать»: подставить `maxTokens` «по семейству модели»
         * значит обрезать ответ там, где провайдер разрешал больше, или получить отказ там,
         * где модель такого потолка не принимает. Молчание каталога — это `null`, то есть
         * «лимит провайдера», а не наше предположение.
         *
         * `topP`/`topK`/`minP` и прочие не заполняются дефолтами таблицы намеренно: дефолт
         * из [SamplerParams] — это положение ползунка в UI, а не утверждение о том, какой
         * режим нужен модели. Пока человек их не трогал, о них молчим.
         */
        fun defaultsFor(model: LocalModelInfo?, profile: LocalProviderProfile?): LocalTurnSettings {
            val fromCatalog = model?.maxOutputTokens?.takeIf { it > 0 }
            return LocalTurnSettings(
                temperature = profile?.temperature,
                maxTokens = fromCatalog ?: profile?.maxTokens,
                reasoningEffort = profile?.reasoningEffort?.takeIf { it.isNotBlank() },
                source = if (fromCatalog != null) SettingsSource.MODEL else SettingsSource.DEFAULT,
            )
        }

        private fun doubleOf(value: Any?): Double? = when (value) {
            is Number -> value.toDouble().takeIf { it.isFinite() }
            else -> null
        }

        /**
         * Целое из JSON. Moshi не различает целые и дробные, поэтому `42.0` из его разбора —
         * нормальный seed; строка и мусор дают `null`, а не ноль: ноль — это значение.
         */
        private fun intOf(value: Any?): Int? = when (value) {
            is Number -> value.toDouble().takeIf { it.isFinite() }?.toInt()
            else -> null
        }
    }
}
