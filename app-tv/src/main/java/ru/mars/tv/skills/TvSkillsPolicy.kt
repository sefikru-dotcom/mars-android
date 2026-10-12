package ru.mars.tv.skills

import org.json.JSONObject
import ru.mars.network.model.SkillItem

/** Счётчики одного скилла из `GET /api/skills/usage` (`usage.<name>`). */
data class TvSkillUsage(
    val useCount: Int = 0,
    val viewCount: Int = 0,
    val patchCount: Int = 0,
) {
    /** Суммарное число обращений — ключ сортировки «по использованиям». */
    val invocations: Int get() = useCount + viewCount + patchCount
}

/** Агрегат статистики использования скиллов (серверные итоги + поимённо). */
data class TvSkillsUsage(
    val bySkill: Map<String, TvSkillUsage> = emptyMap(),
    val totalInvocations: Int = 0,
    val uniqueSkillsUsed: Int = 0,
)

/**
 * Данные экрана «Скиллы» как единый снимок офлайн-кэша: каталог
 * (`GET /api/skills`) обязателен, статистика использования
 * (`GET /api/skills/usage`) — nullable (см. [HttpTvSkillsGateway]).
 */
data class TvSkillsData(
    val skills: List<SkillItem> = emptyList(),
    val usage: TvSkillsUsage? = null,
)

/** Режим сортировки карточек каталога. */
enum class TvSkillsSort { NAME, USAGE }

/**
 * Pure presentation policy of the TV «Скиллы» screen (no Android framework
 * beyond org.json, no I/O — JVM-testable, [ru.mars.tv.spaces.TvSpacesPolicy]
 * parity). Раздел строго read-only: `GET /api/skills` входит в
 * `NATIVE_BEARER_EXACT_PATHS` белого списка нативного device-токена, а
 * мутация `/api/skills/save` — нет. Фильтрация по категориям и подписи —
 * mobile `SkillsFilterPolicy` parity.
 *
 * Все решения — здесь; [TvSkillsActivity] только рисует.
 */
object TvSkillsPolicy {

    /** [restoreSelection] result when there is nothing to focus. */
    const val NO_SELECTION = -1

    /** Идентификатор чипа «Все» — не путать с отображаемой подписью. */
    const val ALL = "all"

    /** Сервер не вернул ни одного скилла (валидный пустой каталог). */
    const val EMPTY_LIST = "На сервере пока нет скиллов"

    /** Каталог непустой, но под выбранный фильтр ничего не подошло. */
    const val FILTER_EMPTY = "Ничего не найдено"

    /** Заголовок-префикс каталога. */
    const val CATALOG_PREFIX = "Скиллов: "

    /** Максимальный возраст кэшированного каталога — редкие изменения (24 ч). */
    const val CACHE_MAX_AGE_MS = 24L * 60 * 60 * 1000

    /** Одна отрисованная карточка каталога. */
    data class SkillRow(
        /** Стабильный ключ фокуса между перерисовками: имя скилла (уникально). */
        val key: String,
        val name: String,
        /** Категория как есть (пустая строка — без категории); подпись строит Activity. */
        val category: String,
        val description: String,
        /** «использован N раз» при N > 0; null когда статистики нет/нулевая. */
        val usageBadge: String?,
        val invocations: Int,
    )

    /**
     * Парсер каталога `GET /api/skills`: записи без имени отбрасываются;
     * не-JSON — `null` (транспортный слой озвучит это ошибкой, а не «пустым
     * каталогом» — честность «сбой != пусто»).
     */
    fun parseCatalog(payload: String): List<SkillItem>? = try {
        val json = JSONObject(payload)
        val array = json.optJSONArray("skills") ?: return null
        (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            val name = entry.text("name").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            SkillItem(
                name = name,
                description = entry.text("description").trim(),
                category = entry.text("category").trim(),
            )
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Парсер статистики `GET /api/skills/usage`; `null` когда payload не-JSON
     * или объекта `usage` нет (статистика недоступна — экран работает без неё).
     */
    fun parseUsage(payload: String): TvSkillsUsage? = try {
        val json = JSONObject(payload)
        val usageObject = json.optJSONObject("usage") ?: return null
        val bySkill = mutableMapOf<String, TvSkillUsage>()
        val keys = usageObject.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val entry = usageObject.optJSONObject(name) ?: continue
            bySkill[name] = TvSkillUsage(
                useCount = entry.optInt("use_count", 0).coerceAtLeast(0),
                viewCount = entry.optInt("view_count", 0).coerceAtLeast(0),
                patchCount = entry.optInt("patch_count", 0).coerceAtLeast(0),
            )
        }
        TvSkillsUsage(
            bySkill = bySkill,
            totalInvocations = json.optInt("total_invocations", 0).coerceAtLeast(0),
            uniqueSkillsUsed = json.optInt("unique_skills_used", 0).coerceAtLeast(0),
        )
    } catch (_: Exception) {
        null
    }

    /** Чипы категорий: [ALL] первым, затем уникальные непустые категории по алфавиту. */
    fun categories(skills: List<SkillItem>): List<String> =
        listOf(ALL) + skills.asSequence()
            .map { it.category.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
            .toList()

    /** Подпись чипа: «Все» для [ALL], иначе категория с заглавной буквы. */
    fun categoryLabel(raw: String): String =
        if (raw == ALL) "Все" else raw.replaceFirstChar(Char::uppercaseChar)

    /** Подпись кнопки сортировки. */
    fun sortLabel(sort: TvSkillsSort): String = when (sort) {
        TvSkillsSort.NAME -> "По имени"
        TvSkillsSort.USAGE -> "По использованиям"
    }

    /** Подходит ли скилл под выбранную категорию (ALL/пустая — подходят все). */
    fun inCategory(skill: SkillItem, category: String): Boolean {
        val normalized = category.trim()
        if (normalized.isEmpty() || normalized == ALL) return true
        return skill.category.equals(normalized, ignoreCase = true)
    }

    /** Суммарные обращения скилла; без статистики — 0. */
    fun invocationsOf(name: String, usage: TvSkillsUsage?): Int =
        usage?.bySkill?.get(name)?.invocations ?: 0

    /**
     * Склонение «использован N раз/раза»: 1 раз, 2 раза, 5 раз, 11 раз,
     * 21 раз, 22 раза.
     */
    fun timesWord(n: Int): String {
        val mod100 = n % 100
        val mod10 = n % 10
        val word = when {
            mod100 in 11..14 -> "раз"
            mod10 == 1 -> "раз"
            mod10 in 2..4 -> "раза"
            else -> "раз"
        }
        return "$n $word"
    }

    /** Бейдж использования: `null` при нуле обращений (нечего объявлять). */
    fun usageBadge(invocations: Int): String? =
        if (invocations <= 0) null else "использован ${timesWord(invocations)}"

    /**
     * Строки каталога: фильтр по категории (записи без имени отбрасываются —
     * карточка без имени нефункциональна), затем сортировка:
     * - [TvSkillsSort.NAME] — категория, затем имя (обе регистронезависимо,
     *   mobile `SkillsFilterPolicy.sorted` parity);
     * - [TvSkillsSort.USAGE] — сумма обращений по убыванию, затем имя;
     *   без статистики все нули и порядок вырождается в именной.
     */
    fun rows(
        skills: List<SkillItem>,
        usage: TvSkillsUsage?,
        category: String,
        sort: TvSkillsSort,
    ): List<SkillRow> {
        val filtered = skills.filter { it.name.isNotBlank() && inCategory(it, category) }
        val ordered = when (sort) {
            TvSkillsSort.NAME -> filtered.sortedWith(
                compareBy({ it.category.trim().lowercase() }, { it.name.trim().lowercase() })
            )
            TvSkillsSort.USAGE -> filtered.sortedWith(
                compareByDescending<SkillItem> { invocationsOf(it.name, usage) }
                    .thenBy { it.name.trim().lowercase() }
            )
        }
        return ordered.map { skill ->
            val invocations = invocationsOf(skill.name, usage)
            SkillRow(
                key = skill.name,
                name = skill.name,
                category = skill.category.trim(),
                description = skill.description.trim(),
                usageBadge = usageBadge(invocations),
                invocations = invocations,
            )
        }
    }

    /** Live-регион: «Скиллов: N» по отфильтрованным строкам (+ плашку источника собирает Activity). */
    fun summaryLine(rows: List<SkillRow>): String = "$CATALOG_PREFIX${rows.size}"

    /** Строка статистики для live-региона; `null` когда статистика недоступна. */
    fun usageSummaryLine(usage: TvSkillsUsage?): String? = usage?.let {
        "Вызовов: ${it.totalInvocations} · задействовано скиллов: ${it.uniqueSkillsUsed}"
    }

    /**
     * Пустое состояние: `null` когда строки есть; [EMPTY_LIST] для пустого
     * каталога; [FILTER_EMPTY] когда каталог непустой, но фильтр всё отсёк.
     */
    fun emptyLine(skills: List<SkillItem>, rows: List<SkillRow>): String? = when {
        rows.isNotEmpty() -> null
        skills.none { it.name.isNotBlank() } -> EMPTY_LIST
        else -> FILTER_EMPTY
    }

    /**
     * Focus restoration after a re-render (auto-refresh/filter): the previous
     * index clamped into the new list — edges STAY, never wrap;
     * [NO_SELECTION] when empty.
     */
    fun restoreSelection(previous: Int, size: Int): Int =
        if (size <= 0) NO_SELECTION else previous.coerceIn(0, size - 1)

    /** Текст из JSON-поля: строка как есть, всё прочее (NULL/число/отсутствие) — "". */
    private fun JSONObject.text(key: String): String = (opt(key) as? String).orEmpty()
}
