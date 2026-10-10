package ru.mars.network.cache

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import ru.mars.network.model.MemoryContent
import ru.mars.network.model.SkillDetail
import ru.mars.network.model.SkillItem

/**
 * Офлайн-кэш базы знаний и скиллов: снимок списка скиллов (`GET /api/skills`), тела
 * отдельных скиллов (`GET /api/skills/content?name=…`) и секции памяти (`GET /api/memory`).
 *
 * Назначение то же, что у [SessionOfflineCache]: репозитории на неудачном HTTP отвечают
 * пустым результатом, и без кэша сбой сети неотличим от «знаний нет» — экран показывает
 * пустоту как норму. Кэш отдаёт последний снимок, а локальный поиск [searchOffline]
 * позволяет пользоваться базой знаний без сети.
 *
 * Контракт (повторяет [SessionOfflineCache]):
 * - Класс чистый: зависит только от порта [KeyValueStore], без Android-фреймворка и сети —
 *   полностью покрыт JVM unit-тестами.
 * - Ничего не бросает: битый JSON, чужая версия формата или падающее хранилище дают
 *   «данных нет» (null/false/пустой список), а не crash; следующая успешная запись
 *   перетирает мусор.
 * - Объём ограничен: не более [MAX_CACHED_SKILLS] скиллов в снимке, имена/описания и тела
 *   скиллов, секции памяти усекаются — SharedPreferences не место для мегабайтов.
 * - Кэшей тел скиллов хранится не более [MAX_CACHED_DETAILS] штук: порядок использования
 *   ведёт LRU-индекс (сохранение и открытие скилла продвигают его в голову), самый давно
 *   не используемый кэш вытесняется вместе с данными.
 * - Локальный поиск [searchOffline]: регистронезависимый подстрочный запрос по названиям
 *   и описаниям скиллов плюс секции памяти; тела скиллов в поиске НЕ участвуют (и поиск
 *   не продвигает LRU). Сниппет нормализован и ограничен [MAX_SNIPPET_CHARS] символами,
 *   запрос — [MAX_SEARCH_QUERY_CHARS].
 * - Данные приватные: кэш стирается при выходе из аккаунта ([clear]) вместе с остальными
 *   локальными plaintext-хранилищами.
 *
 * Не потокобезопасен: вызывать из одного места (экран/репозиторий-координатор), как
 * и [SessionOfflineCache].
 */
class KnowledgeOfflineCache(
    private val store: KeyValueStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    // --- список скиллов ------------------------------------------------------------------------

    /** Сохраняет снимок списка скиллов; false — хранилище не приняло запись. */
    fun saveSkills(skills: List<SkillItem>): Boolean = runCatching {
        val rows = skills.take(MAX_CACHED_SKILLS).mapNotNull { skillToRow(it) }
        store.putString(KEY_SKILLS, mapAdapter.toJson(envelope(mapOf("skills" to rows))))
        true
    }.getOrDefault(false)

    /** Сохранённый список скиллов; null — кэша нет или данные битые. */
    fun loadSkills(): List<SkillItem>? = runCatching {
        val body = readEnvelope(store.getString(KEY_SKILLS) ?: return null) ?: return null
        val rows = body["skills"] as? List<*> ?: return null
        rows.mapNotNull { rowToSkill(it) }
    }.getOrNull()

    // --- тело скилла ---------------------------------------------------------------------------

    /**
     * Сохраняет тело скилла (SKILL.md) под именем [SkillDetail.name] и продвигает запись
     * в голову LRU; имя без непробельных символов отклоняется (false) — ключ кэша обязан
     * быть значимым.
     */
    fun saveSkillDetail(detail: SkillDetail): Boolean {
        val name = sanitizeName(detail.name)
        if (name.isEmpty()) return false
        return runCatching {
            store.putString(
                detailKey(name),
                mapAdapter.toJson(
                    envelope(
                        mapOf(
                            "name" to name,
                            "content" to detail.content.take(MAX_DETAIL_CONTENT_CHARS),
                        ),
                    ),
                ),
            )
            touchDetailLru(name)
            true
        }.getOrDefault(false)
    }

    /** Сохранённое тело скилла; null — кэша нет или данные битые. Успешное чтение продвигает LRU. */
    fun loadSkillDetail(name: String): SkillDetail? {
        val key = sanitizeName(name)
        if (key.isEmpty()) return null
        val detail = runCatching {
            val body = readEnvelope(store.getString(detailKey(key)) ?: return null) ?: return null
            val storedName = (body["name"] as? String)?.trim().orEmpty()
            if (storedName.isEmpty()) return null
            SkillDetail(name = storedName, content = body["content"] as? String ?: "")
        }.getOrNull()
        // Успешное чтение — признак «скилл недавно открывали»: продвигаем в LRU.
        if (detail != null) touchDetailLru(key)
        return detail
    }

    // --- секции памяти -------------------------------------------------------------------------

    /** Сохраняет снимок секций памяти; каждая секция усекается до [MAX_MEMORY_SECTION_CHARS]. */
    fun saveMemory(content: MemoryContent): Boolean = runCatching {
        store.putString(
            KEY_MEMORY,
            mapAdapter.toJson(
                envelope(
                    mapOf(
                        "memory" to content.memory.take(MAX_MEMORY_SECTION_CHARS),
                        "user" to content.user.take(MAX_MEMORY_SECTION_CHARS),
                        "soul" to content.soul.take(MAX_MEMORY_SECTION_CHARS),
                        "project_context" to content.projectContext.take(MAX_MEMORY_SECTION_CHARS),
                    ),
                ),
            ),
        )
        true
    }.getOrDefault(false)

    /** Сохранённые секции памяти; null — кэша нет или данные битые. */
    fun loadMemory(): MemoryContent? = runCatching {
        val body = readEnvelope(store.getString(KEY_MEMORY) ?: return null) ?: return null
        MemoryContent(
            memory = body["memory"] as? String ?: "",
            user = body["user"] as? String ?: "",
            soul = body["soul"] as? String ?: "",
            projectContext = body["project_context"] as? String ?: "",
        )
    }.getOrNull()

    // --- локальный офлайн-поиск ----------------------------------------------------------------

    /**
     * Регистронезависимый подстрочный поиск по кэшированным знаниям: названия и описания
     * скиллов плюс секции памяти. Тела скиллов не сканируются (контракт класса), LRU
     * поиск не продвигает.
     *
     * Контракт:
     * - запрос тримится, приводится к нижнему регистру и усекается до [MAX_SEARCH_QUERY_CHARS];
     * - пустой/пробельный запрос, отсутствие кэшей и падающее хранилиство дают пустой список;
     * - порядок результатов: скиллы в порядке снимка, затем секции памяти в порядке
     *   memory → user → soul → project_context; суммарно не более [MAX_SEARCH_RESULTS] строк;
     * - скилл, совпавший названием И описанием, — одна запись с обоими источниками;
     * - ничего не бросает.
     */
    fun searchOffline(query: String): List<OfflineKnowledgeMatch> {
        val needle = query.trim().lowercase().take(MAX_SEARCH_QUERY_CHARS)
        if (needle.isEmpty()) return emptyList()

        val matches = mutableListOf<OfflineKnowledgeMatch>()
        for (skill in loadSkills().orEmpty()) {
            val sources = LinkedHashSet<OfflineKnowledgeSource>()
            var snippet: String? = null
            if (skill.name.lowercase().contains(needle)) {
                sources += OfflineKnowledgeSource.SKILL_NAME
                snippet = snippetAround(skill.name, needle)
            }
            if (skill.description.lowercase().contains(needle)) {
                sources += OfflineKnowledgeSource.SKILL_DESCRIPTION
                if (snippet == null) snippet = snippetAround(skill.description, needle)
            }
            if (sources.isNotEmpty()) {
                matches += OfflineKnowledgeMatch(sources = sources, name = skill.name, snippet = snippet)
            }
        }

        val memory = loadMemory()
        if (memory != null) {
            for ((section, text) in memorySections(memory)) {
                if (text.lowercase().contains(needle)) {
                    matches += OfflineKnowledgeMatch(
                        sources = setOf(OfflineKnowledgeSource.MEMORY_SECTION),
                        name = section,
                        snippet = snippetAround(text, needle),
                    )
                }
            }
        }
        return matches.take(MAX_SEARCH_RESULTS)
    }

    // --- очистка -------------------------------------------------------------------------------

    /** Полное стирание кэша — вызывается на всех путях выхода из авторизации. */
    fun clear() {
        runCatching { store.clearAll() }
    }

    // --- LRU-индекс кэшей тел --------------------------------------------------------------------

    /**
     * Продвигает скилл в голову LRU-индекса; кэши сверх [MAX_CACHED_DETAILS] вытесняются
     * (данные удаляются из хранилища, начиная с самых давно не используемых).
     */
    private fun touchDetailLru(name: String) {
        val order = listOf(name) + readDetailLru().filter { it != name }
        order.drop(MAX_CACHED_DETAILS).forEach { evicted ->
            runCatching { store.remove(detailKey(evicted)) }
        }
        writeDetailLru(order.take(MAX_CACHED_DETAILS))
    }

    /**
     * Порядок использования кэшей тел (свежий первым). Битая или отсутствующая служебная
     * запись не ошибка и не потеря данных: индекс восстанавливается перечислением ключей
     * хранилища ([KeyValueStore.keys]) по префиксу.
     */
    private fun readDetailLru(): List<String> {
        val parsed = runCatching { store.getString(KEY_DETAIL_LRU)?.let { listAdapter.fromJson(it) } }.getOrNull()
        if (parsed != null) return parsed.filter { it.isNotBlank() }
        return runCatching {
            store.keys()
                .filter { it.startsWith(KEY_DETAIL_PREFIX) }
                .map { it.removePrefix(KEY_DETAIL_PREFIX) }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun writeDetailLru(order: List<String>) {
        runCatching { store.putString(KEY_DETAIL_LRU, listAdapter.toJson(order)) }
    }

    private fun detailKey(name: String): String = KEY_DETAIL_PREFIX + name

    // --- сериализация и сниппеты -----------------------------------------------------------------

    private fun envelope(extra: Map<String, Any?>): Map<String, Any?> =
        mapOf("v" to CACHE_VERSION, "saved_at" to clock()) + extra

    /** Разбор конверта; null — мусор, чужая версия или не-объект (данные считаются отсутствующими). */
    private fun readEnvelope(raw: String): Map<String, Any?>? {
        val parsed = mapAdapter.fromJson(raw) ?: return null
        val version = (parsed["v"] as? Number)?.toInt()
        if (version != CACHE_VERSION) return null
        return parsed
    }

    /** Строка скилла; имя без непробельных символов не сохраняется (строка отбрасывается). */
    private fun skillToRow(skill: SkillItem): Map<String, Any?>? {
        val name = sanitizeName(skill.name)
        if (name.isEmpty()) return null
        return mapOf(
            "name" to name,
            "description" to sanitizeText(skill.description, MAX_SKILL_TEXT_CHARS),
            "category" to sanitizeText(skill.category, MAX_SKILL_TEXT_CHARS),
        )
    }

    /** Строка без имени не восстанавливается в [SkillItem] и отбрасывается. */
    private fun rowToSkill(row: Any?): SkillItem? {
        val map = row as? Map<*, *> ?: return null
        val name = (map["name"] as? String)?.trim().orEmpty()
        if (name.isEmpty()) return null
        return SkillItem(
            name = name,
            description = map["description"] as? String ?: "",
            category = map["category"] as? String ?: "",
        )
    }

    /** Непробельные секции памяти в фиксированном порядке; ключ — имя секции на проводе. */
    private fun memorySections(content: MemoryContent): List<Pair<String, String>> = listOf(
        "memory" to content.memory,
        "user" to content.user,
        "soul" to content.soul,
        "project_context" to content.projectContext,
    ).filter { it.second.isNotBlank() }

    /**
     * Имя скилла — ключ кэша: серии пробельных символов схлопываются в одиночный пробел,
     * длина усекается, края тримятся. Пустой результат = имя недопустимо.
     */
    private fun sanitizeName(raw: String): String = raw
        .replace(WHITESPACE_RUN, " ")
        .take(MAX_SKILL_NAME_CHARS)
        .trim()

    /** Однострочный текст (описание/категория): пробельные серии схлопываются, длина усекается. */
    private fun sanitizeText(raw: String, limit: Int): String = raw
        .replace(WHITESPACE_RUN, " ")
        .take(limit)
        .trim()

    /**
     * Нормализованная выдержка вокруг первого вхождения [needle] (регистр уже нормализован
     * вызывающим кодом): переводы строк схлопываются в пробелы, вокруг совпадения остаётся
     * [SNIPPET_CONTEXT_CHARS] символов, обрезка помечается «…», итог не длиннее
     * [MAX_SNIPPET_CHARS].
     */
    private fun snippetAround(text: String, needle: String): String {
        val normalized = text.replace(WHITESPACE_RUN, " ").trim()
        if (normalized.isEmpty()) return ""
        val index = normalized.lowercase().indexOf(needle)
        val body = if (index < 0) {
            normalized
        } else {
            val start = maxOf(0, index - SNIPPET_CONTEXT_CHARS)
            val end = minOf(normalized.length, index + needle.length + SNIPPET_CONTEXT_CHARS)
            (if (start > 0) "…" else "") +
                normalized.substring(start, end) +
                (if (end < normalized.length) "…" else "")
        }
        return if (body.length > MAX_SNIPPET_CHARS) body.take(MAX_SNIPPET_CHARS - 1) + "…" else body
    }

    private companion object {
        const val CACHE_VERSION = 1
        const val KEY_SKILLS = "knowledge_skills"
        const val KEY_MEMORY = "knowledge_memory"
        const val KEY_DETAIL_PREFIX = "knowledge_skill:"

        /** Служебный ключ LRU-индекса кэшей тел (JSON-список имён, свежий первым). */
        const val KEY_DETAIL_LRU = "knowledge_skill_lru"

        /** Сколько скиллов хранится в снимке списка. */
        const val MAX_CACHED_SKILLS = 200

        /** Сколько кэшей тел РАЗНЫХ скиллов хранится одновременно (LRU-вытеснение). */
        const val MAX_CACHED_DETAILS = 20

        /** Потолки локального поиска: число результатов, длина запроса и сниппета. */
        const val MAX_SEARCH_RESULTS = 50
        const val MAX_SEARCH_QUERY_CHARS = 200
        const val MAX_SNIPPET_CHARS = 200

        /** Контекст (в символах) вокруг совпадения, оставляемый в сниппете. */
        const val SNIPPET_CONTEXT_CHARS = 60

        /** Потолки длины полей — приватное хранилище не должно раздуваться. */
        const val MAX_SKILL_NAME_CHARS = 128
        const val MAX_SKILL_TEXT_CHARS = 300
        const val MAX_DETAIL_CONTENT_CHARS = 16_000
        const val MAX_MEMORY_SECTION_CHARS = 8_000

        val WHITESPACE_RUN = Regex("\\s+")
        val MAP_TYPE = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        val LIST_TYPE = Types.newParameterizedType(List::class.java, String::class.java)
        val moshi = Moshi.Builder().build()
        val mapAdapter = moshi.adapter<Map<String, Any?>>(MAP_TYPE)
        val listAdapter = moshi.adapter<List<String>>(LIST_TYPE)
    }
}

/** Источник совпадения при офлайн-поиске знаний ([KnowledgeOfflineCache.searchOffline]). */
enum class OfflineKnowledgeSource { SKILL_NAME, SKILL_DESCRIPTION, MEMORY_SECTION }

/**
 * Совпадение офлайн-поиска знаний ([KnowledgeOfflineCache.searchOffline]).
 *
 * [sources] перечисляет все поля элемента, давшие совпадение (скилл может совпасть
 * названием И описанием — это одна запись), [name] — имя скилла или ключ секции памяти
 * (`memory`/`user`/`soul`/`project_context`), [snippet] — короткая нормализованная
 * выдержка вокруг первого совпавшего текста (для совпадения только названием —
 * выдержка из названия).
 */
data class OfflineKnowledgeMatch(
    val sources: Set<OfflineKnowledgeSource>,
    val name: String,
    val snippet: String?,
)
