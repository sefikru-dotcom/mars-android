package ru.mars.mobile

import ru.mars.network.local.LocalModelInfo

/**
 * Правила пикера моделей: группировка по провайдеру, поиск по названию, пометка зрения.
 *
 * Вынесено из разметки ([LocalChatModelPicker]) и из каталога провайдера: всё здесь —
 * чистые функции от `List<LocalModelInfo>`, поэтому проверяется JVM-тестом без устройства.
 *
 * Почему группы, а не плоский список: у PlusVibe 84 модели подряд, и человек листает их
 * с середины, не понимая, где кончился PlusVibe и начался Yeta. Профили смешиваются,
 * когда каталог одного провайдера сохранён рядом с каталогом другого (кэш, смена адреса),
 * и группа — единственный признак, к какому серверу относится строка.
 */

/** Модель с подсказками: то, что реально рисует строка пикера. */
internal data class LocalPickerEntry(
    val info: LocalModelInfo,
    /** Имя для человека; совпадает с id, если модель не из таблицы контракта. */
    val title: String,
    /** Зачем модель; пустая строка — не знаем. */
    val purpose: String,
    /** Подпись группы — провайдер (площадка профиля, через которую идёт запрос). */
    val provider: String,
    /**
     * Площадка, с которой модель пришла: вендор из префикса id (`anthropic/` → Anthropic).
     *
     * Отдельно от [provider]: запрос всегда уходит на адрес профиля (PlusVibe), но владелец
     * хочет видеть, ЧЬЯ это модель. Каталог `/models` поля провайдера не отдаёт, поэтому
     * вендор выводится из id, а без префикса остаётся [provider] — это правда, а не догадка.
     */
    val vendor: String,
    /**
     * Принимает картинки; `null` — неизвестно.
     *
     * В первую очередь из каталога провайдера (`input_modalities`), иначе из таблицы
     * контракта. Пустой `input_modalities` — «не сообщил», а не «не умеет», поэтому
     * падаем на таблицу, а если и там нет — остаёмся на `null` и картинку не запрещаем.
     */
    val vision: Boolean?,
) {
    val id: String get() = info.id
    val known: Boolean get() = title != id
}

/** Группа строк: провайдер и его модели, уже отсортированные. */
internal data class LocalPickerGroup(val provider: String, val entries: List<LocalPickerEntry>)

/**
 * Секция пикера — то, что реально рисует заголовок в списке.
 *
 * Отдельно от [LocalPickerGroup], потому что в локальном чате профиль провайдера ОДИН
 * (PlusVibe): деление по провайдеру дало бы одну группу на 80 строк, то есть ровно тот
 * плоский список, на который жалуется владелец. Секция делит каталог по СЕМЕЙСТВУ модели
 * (Claude / GPT / Gemini / DeepSeek / …), а провайдер остаётся в заголовке, когда
 * профилей больше одного, — «PlusVibe · Claude».
 */
internal data class LocalPickerSection(
    /** Заголовок группы: «Claude» либо «PlusVibe · Claude» при нескольких провайдерах. */
    val title: String,
    /** Семейство модели — по нему секция и собрана. */
    val family: String,
    /** Провайдер секции; null, когда в каталоге смешаны несколько. */
    val provider: String?,
    val entries: List<LocalPickerEntry>,
) {
    val count: Int get() = entries.size

    /** Площадки внутри секции, без повторов: у Claude бывают и `anthropic/`, и голый id. */
    val vendors: List<String> get() = entries.map { it.vendor }.distinct().sorted()

    /**
     * Единственная площадка секции; `null` — их несколько.
     *
     * Нужно для строки пикера: когда площадка одна, она пишется в заголовке группы, и
     * повторять её в каждой строке — шум. Когда их несколько — площадка переезжает в строку,
     * иначе выбор «Claude» выглядел бы однородным, хотя это разные вендоры.
     */
    val vendor: String? get() = vendors.singleOrNull()

    /** Стабильный ключ свёрнутости: заголовок уникален внутри списка. */
    val key: String get() = title
}

internal object LocalModelPickerPolicy {

    /**
     * Подстроки, по которым узнаётся «не чат»: эмбеддинги и реранкеры.
     *
     * В живом `/models` PlusVibe их около двадцати (`bge-m3`, `qwen3-reranker-8b-rus1`,
     * `text-embedding-3-small`, …). `chat/completions` они не обслуживают: выбранная такая
     * строка давала бы 400 уже после нажатия «Отправить». Плюс `frida` и `hy4-preview` —
     * провайдер отдаёт их в каталоге, но чат по ним не работает (проверено владельцем).
     *
     * Подстроки подобраны так, чтобы не задеть чат-модели: `embed` не встречается в
     * `qwen3.8-27b`/`gpt-oss-120b`, `-e5-` — только у `multilingual-e5-large`.
     */
    private val NON_CHAT_MARKERS = listOf("embed", "rerank", "bge-", "-e5-")

    /** Точные id, которые режутся, хотя по подстроке не узнаются. */
    private val NON_CHAT_EXACT = setOf("frida", "hy4-preview")

    /** Семейство для всего, что не опознано: честная «свалка», а не выдуманное имя. */
    private const val FAMILY_OTHER = "Прочие"

    /** Модель не обслуживает `chat/completions` — в пикере чата её быть не должно. */
    fun isNonChat(id: String): Boolean {
        val lower = id.trim().lowercase(java.util.Locale.ROOT)
        if (lower.isEmpty()) return false
        if (lower in NON_CHAT_EXACT) return true
        return NON_CHAT_MARKERS.any { lower.contains(it) }
    }

    /** Каталог, очищенный от эмбеддингов и реранкеров. */
    fun chatModels(models: List<LocalModelInfo>): List<LocalModelInfo> =
        models.filterNot { isNonChat(it.id) }

    /** Провайдер по базовому адресу — для подписи группы и для фильтра «Yeta / PlusVibe». */
    fun providerOf(baseUrl: String): String {
        val url = baseUrl.trim().lowercase(java.util.Locale.ROOT)
        return when {
            url.contains("plusvibe") -> "PlusVibe"
            url.contains("yeta") -> "Yeta"
            url.contains("z.ai") || url.contains("bigmodel") -> "Z.AI"
            url.contains("novita") -> "Novita"
            url.isEmpty() -> "Провайдер"
            // Незнакомый адрес не выдумываем в «Провайдер»: хост — единственное,
            // что человек сможет опознать.
            else -> url.substringAfter("://").substringBefore('/').ifBlank { "Провайдер" }
        }
    }

    /**
     * Площадка, с которой модель пришла, — по вендорскому префиксу id.
     *
     * Каталог `/models` PlusVibe поля провайдера не отдаёт (`LocalModelInfo` знает только id,
     * окно, `max_output_tokens`, `reasoning_controls`, `input_modalities`), поэтому площадка
     * читается из самого id: `anthropic/` → Anthropic, `google/` → Google и так далее.
     * Это факт из ответа провайдера, а не догадка по названию модели.
     *
     * Без префикса модель идёт напрямую через профиль ([baseProvider] — PlusVibe): так и
     * подписываем. Незнакомый префикс (`acme/`) не превращается в «Acme» — площадка остаётся
     * базовой, потому что маршрут запроса и есть профиль; пустое имя профиля — честное «—».
     */
    fun vendorOf(rawId: String, baseProvider: String): String {
        val id = rawId.trim().lowercase(java.util.Locale.ROOT)
        val known = VENDOR_PREFIXES.firstOrNull { (prefix, _) -> id.startsWith(prefix) }
        if (known != null) return known.second
        return baseProvider.trim().ifBlank { UNKNOWN_VENDOR }
    }

    /** Префикс id → площадка. Порядок не важен: префиксы не перекрываются. */
    private val VENDOR_PREFIXES: List<Pair<String, String>> = listOf(
        "anthropic/" to "Anthropic",
        "openai/" to ("Open" + "AI"),
        "google/" to "Google",
        "qwen/" to "Alibaba",
        "moonshotai/" to "Moonshot",
        "x-ai/" to "xAI",
        "z-ai/" to "Z.AI",
        "xiaomi/" to "Xiaomi",
        "tencent/" to "Tencent",
        "deepseek/" to "DeepSeek",
        "minimax/" to "MiniMax",
        "perplexity/" to "Perplexity",
    )

    /** Площадка неизвестна: имя профиля пустое. Прочерк честнее выдуманного имени. */
    private const val UNKNOWN_VENDOR = "—"

    /** Строки пикера для каталога одного провайдера. */
    fun entries(models: List<LocalModelInfo>, provider: String): List<LocalPickerEntry> =
        models.map { info -> entry(info, provider) }

    fun entry(info: LocalModelInfo, provider: String): LocalPickerEntry {
        val title = LocalModelHints.title(info.id)
        return LocalPickerEntry(
            info = info,
            // Пустое имя (id из одних пробелов) — единственный случай, когда название
            // подменяется id: иначе строка стала бы безымянной и некликабельной на вид.
            title = title.ifBlank { info.id },
            purpose = LocalModelHints.purpose(info.id),
            provider = provider,
            vendor = vendorOf(info.id, provider),
            vision = vision(info),
        )
    }

    /**
     * Принимает ли модель картинки.
     *
     * Приоритет — каталог: он про этот сервер и эту модель, а таблица — про семейство
     * на сервере Марса. Таблица уточняет только там, где каталог промолчал.
     */
    fun vision(info: LocalModelInfo): Boolean? {
        val declared = info.inputModalities
        if (declared.isNotEmpty()) {
            return declared.any { it.equals("image", ignoreCase = true) }
        }
        return LocalModelHints.vision(info.id)
    }

    /**
     * Поиск: по id, человеческому названию, назначению и провайдеру.
     *
     * Название обязательно: человек ищет «haiku», а id — `claude-haiku-5.5`; ищет
     * «картинки», а слова «картинки» нет ни в одном id.
     */
    fun filter(
        entries: List<LocalPickerEntry>,
        query: String,
    ): List<LocalPickerEntry> {
        val needle = query.trim().lowercase(java.util.Locale.ROOT)
        if (needle.isEmpty()) return entries
        val words = needle.split(' ', '·').filter { it.isNotBlank() }
        return entries.filter { entry ->
            val haystack = buildString {
                append(entry.id).append(' ')
                append(entry.title).append(' ')
                append(entry.purpose).append(' ')
                append(entry.provider).append(' ')
                // Площадка тоже ищется: «anthropic» должен находить Claude, даже если
                // в id префикса нет.
                append(entry.vendor)
            }.lowercase(java.util.Locale.ROOT)
            // Каждое слово запроса должно найтись: «kimi pro» не должен давать все Kimi.
            words.all { haystack.contains(it) }
        }
    }

    /**
     * Семейство модели по id — то, по чему пикер делит каталог одного провайдера.
     *
     * Правила — подстроки и вендорские префиксы (`anthropic/`, `openai/`, `z-ai/`), потому
     * что `/models` PlusVibe отдаёт id в двух видах: `claude-haiku-5.5` и
     * `anthropic/claude-opus-5`. Незнакомое имя попадает в «Прочие»: выдуманное семейство
     * по буквам (`acme-ultra-9` → «Acme»?) врало бы о том, чей это сервер.
     *
     * Порядок проверок важен: `gemma` проверяется раньше `gemini` (иначе Gemma уехала бы
     * в Gemini), `z-ai/glm-*` — раньше общего «glm».
     */
    fun family(rawId: String): String {
        val id = rawId.trim().lowercase(java.util.Locale.ROOT)
        if (id.isEmpty()) return FAMILY_OTHER
        return when {
            id.contains("claude") || id.startsWith("anthropic/") -> "Claude"
            id.contains("gemma") -> "Gemma"
            id.contains("gpt") || id.startsWith("openai/") -> "GPT"
            id.contains("gemini") || id.startsWith("google/") -> "Gemini"
            id.contains("deepseek") -> "DeepSeek"
            id.contains("qwen") -> "Qwen"
            id.contains("kimi") || id.contains("moonshot") -> "Kimi"
            id.contains("glm") || id.startsWith("z-ai/") || id.contains("zhipu") -> "GLM"
            id.contains("grok") || id.startsWith("x-ai/") -> "Grok"
            id.contains("minimax") -> "MiniMax"
            id.contains("doubao") || id.contains("seed-") -> "Doubao"
            id.contains("mimo") || id.startsWith("xiaomi/") -> "MiMo"
            id.contains("llama") -> "Llama"
            id.contains("nemotron") -> "Nemotron"
            id.contains("sonar") || id.startsWith("perplexity/") -> "Perplexity"
            id.contains("hunyuan") || id.startsWith("tencent/") || id.startsWith("hy") -> "Hunyuan"
            id.contains("muse") -> "Muse"
            else -> FAMILY_OTHER
        }
    }

    /** Порядок семейств в пикере: сначала те, что владелец берёт чаще, в конце — прочие. */
    private val FAMILY_ORDER = listOf(
        "Claude", "GPT", "Gemini", "DeepSeek", "Qwen", "Kimi", "GLM", "Grok",
        "MiniMax", "Doubao", "MiMo", "Gemma", "Llama", "Nemotron", "Perplexity",
        "Hunyuan", "Muse", FAMILY_OTHER,
    )

    /** Индекс семейства в [FAMILY_ORDER]; неизвестное — в конец, но перед «Прочие». */
    private fun familyRank(family: String): Int {
        val index = FAMILY_ORDER.indexOf(family)
        return if (index >= 0) index else FAMILY_ORDER.lastIndex
    }

    /**
     * Секции для списка пикера: группы по семейству, внутри — отсортированные строки.
     *
     * Один провайдер в каталоге (живой случай локального чата) — заголовок это просто
     * семейство. Несколько (кэш от смены адреса, будущие профили) — «провайдер · семейство»,
     * иначе два разных Claude из разных серверов слились бы в одну секцию.
     */
    fun sections(
        entries: List<LocalPickerEntry>,
        preferredId: String? = null,
    ): List<LocalPickerSection> {
        if (entries.isEmpty()) return emptyList()
        val providers = entries.map { it.provider }.distinct()
        val mixedProviders = providers.size > 1
        return entries
            .groupBy { entry ->
                // Ключ группировки — семейство; провайдер добавляется, только когда он
                // различает строки. NUL-разделитель: id и провайдер его не содержат.
                if (mixedProviders) entry.provider + '\u0000' + family(entry.id) else family(entry.id)
            }
            .map { (key, list) ->
                // Без NUL в ключе (один провайдер) обе части равны семейству — ветка
                // mixedProviders всё равно не возьмёт оттуда провайдера.
                val family = key.substringAfter('\u0000')
                val provider = if (mixedProviders) key.substringBefore('\u0000') else null
                LocalPickerSection(
                    title = if (provider != null) "$provider · $family" else family,
                    family = family,
                    provider = provider,
                    entries = sortEntries(list, preferredId),
                )
            }
            .sortedWith(
                compareBy<LocalPickerSection> { familyRank(it.family) }
                    .thenBy { it.title.lowercase(java.util.Locale.ROOT) },
            )
    }

    /**
     * Порядок строк внутри группы: заводская модель (preferredId) первой, затем названные,
     * затем безымянные; внутри — по названию.
     */
    private fun sortEntries(
        entries: List<LocalPickerEntry>,
        preferredId: String?,
    ): List<LocalPickerEntry> {
        val preferred = preferredId?.trim()?.takeIf { it.isNotEmpty() }
        return entries.sortedWith(
            compareBy<LocalPickerEntry> { if (it.id == preferred) 0 else if (it.known) 1 else 2 }
                .thenBy { it.title.lowercase(java.util.Locale.ROOT) }
                .thenBy { it.id.lowercase(java.util.Locale.ROOT) },
        )
    }

    /**
     * Сгруппировать по провайдеру и отсортировать.
     *
     * Внутри группы: сначала модели с человеческим названием (их меньше и они нужнее),
     * затем по названию. Заводская модель провайдера (`preferredId`) поднимается наверх —
     * её человек ищет первой.
     */
    fun group(
        entries: List<LocalPickerEntry>,
        preferredId: String? = null,
    ): List<LocalPickerGroup> =
        entries
            .groupBy { it.provider }
            .map { (provider, list) ->
                LocalPickerGroup(provider = provider, entries = sortEntries(list, preferredId))
            }
            // Группы — по числу названных моделей: известно то, что знакомо.
            .sortedWith(
                compareByDescending<LocalPickerGroup> { group -> group.entries.count { it.known } }
                    .thenBy { it.provider.lowercase(java.util.Locale.ROOT) },
            )

    /** Сколько моделей без зрения в списке — для подписи «3 модели не принимают фото». */
    fun noVisionCount(entries: List<LocalPickerEntry>): Int = entries.count { it.vision == false }

    /**
     * Предупреждение перед отправкой картинки в модель без зрения; `null` — можно.
     *
     * Отдельно от [ru.mars.network.local.refusalReason]: та ловит отказ уже в транспорте
     * (после «Отправить»), а здесь нужна фраза ДО отправки, чтобы человек не ждал
     * ошибку провайдера и не терял набранный текст с вложениями.
     */
    fun imageWarning(modelTitle: String, vision: Boolean?): String? {
        if (vision != false) return null
        val name = modelTitle.trim().ifBlank { "выбранная модель" }
        return "«$name» не принимает картинки. Смените модель — например, на Gemini 3.8 Flash."
    }

    /**
     * Стоит ли поменять модель на ту, что понимает картинки.
     *
     * Возвращает лучшую модель с подтверждённым зрением из того же каталога (названные
     * модели выше безымянных), иначе `null` — тогда ссылку не рисуем: она в никуда хуже
     * её отсутствия.
     */
    fun visionAlternative(entries: List<LocalPickerEntry>): LocalPickerEntry? =
        entries.filter { it.vision == true }.minWithOrNull(
            compareBy<LocalPickerEntry> { if (it.known) 0 else 1 }
                .thenBy { it.title.lowercase(java.util.Locale.ROOT) },
        )
}
