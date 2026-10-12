package ru.mars.network.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Shared tolerant JSON field access for the Kanban/Insights/Logs repositories.
 *
 * The WebUI payloads are hand-serialised Python dicts: optional columns may be
 * absent OR null, "empty string is null" kanban columns may arrive as strings,
 * numbers may arrive as numeric strings (epoch), and map value shapes differ
 * between server builds (`by_assignee` nested vs flat). Every accessor here
 * therefore degrades to a safe default instead of throwing — a partially filled
 * payload must still produce a usable model, never a parse crash.
 *
 * Same convention as the per-repository private helpers elsewhere in the module
 * ([CronRepository], [ModelsRepository], [ReasoningRepository]); extracted into
 * one internal object because three new repositories would otherwise triplicate it.
 */
internal object TolerantJson {

    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val listType = Types.newParameterizedType(List::class.java, Any::class.java)
    private val moshi = Moshi.Builder().build()

    /** Parses a JSON object; null on malformed/absent body or a non-object root. */
    fun parseMap(json: String): Map<String, Any?>? =
        try {
            moshi.adapter<Map<String, Any?>>(mapType).fromJson(json)
        } catch (_: Exception) {
            null
        }

    /** Parses a JSON array; null on malformed body or a non-array root. */
    fun parseList(json: String): List<Any?>? =
        try {
            moshi.adapter<List<Any?>>(listType).fromJson(json)
        } catch (_: Exception) {
            null
        }

    fun toJson(value: Map<String, Any?>): String =
        moshi.adapter<Map<String, Any?>>(mapType).toJson(value)

    /**
     * Non-blank string field; null when absent, null, or blank. Numbers render without
     * a trailing `.0` (Moshi decodes every JSON number as Double, and a kanban id like
     * `7` must stringify as `"7"`, not `"7.0"`).
     */
    fun string(map: Map<*, *>?, key: String): String? = renderString(map?.get(key))

    /** Number-aware `toString()` shared by [string] and [stringListOrNull]; null stays null. */
    fun renderString(value: Any?): String? = when (value) {
        null -> null
        is Double, is Float -> {
            val d = (value as Number).toDouble()
            if (d.isFinite() && d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
        }
        else -> value.toString()
    }?.takeIf(String::isNotBlank)

    /** Boolean field tolerant to `true/false`, `1/0` and `"true"/"1"` spellings. */
    fun bool(map: Map<*, *>?, key: String, default: Boolean = false): Boolean =
        when (val value = map?.get(key)) {
            is Boolean -> value
            is Number -> value.toDouble() != 0.0
            is String -> value.trim().equals("true", ignoreCase = true) || value.trim() == "1"
            else -> default
        }

    /**
     * Integer field tolerant to JSON numbers AND numeric strings (kanban stores
     * `goal_max_turns` as text where "" means null). Fractional numbers truncate.
     */
    fun int(map: Map<*, *>?, key: String, default: Int = 0): Int = longOrNull(map?.get(key))?.toInt() ?: default

    fun intOrNull(map: Map<*, *>?, key: String): Int? = longOrNull(map?.get(key))?.toInt()

    /** Long field tolerant to JSON numbers and numeric strings; absent/null/garbage → [default]. */
    fun long(map: Map<*, *>?, key: String, default: Long = 0L): Long = longOrNull(map?.get(key)) ?: default

    fun longOrNull(map: Map<*, *>?, key: String): Long? = longOrNull(map?.get(key))

    fun doubleOrNull(map: Map<*, *>?, key: String): Double? =
        when (val value = map?.get(key)) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }

    private fun longOrNull(value: Any?): Long? =
        when (value) {
            is Boolean -> if (value) 1L else 0L
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull() ?: value.trim().toDoubleOrNull()?.toLong()
            else -> null
        }

    /** List of non-blank strings; absent/non-list degrades to null (NOT empty — see `stringListOrEmpty`). */
    fun stringListOrNull(map: Map<*, *>?, key: String): List<String>? =
        (map?.get(key) as? List<*>)?.mapNotNull { renderString(it) }

    fun stringListOrEmpty(map: Map<*, *>?, key: String): List<String> =
        stringListOrNull(map, key).orEmpty()

    /** Nested object field; null when absent or not an object. */
    @Suppress("UNCHECKED_CAST")
    fun mapField(map: Map<*, *>?, key: String): Map<String, Any?>? = map?.get(key) as? Map<String, Any?>

    /** Nested array field; empty when absent or not an array. */
    fun listField(map: Map<*, *>?, key: String): List<Any?> =
        (map?.get(key) as? List<*>).orEmpty()

    /**
     * `{key: int}` counter map (board `by_status`-style payloads).
     * Values tolerant to numbers and numeric strings; unparsable entries are dropped.
     */
    fun intCounts(map: Map<*, *>?, key: String): Map<String, Int> =
        (mapField(map, key)).orEmpty().entries
            .mapNotNull { (k, v) ->
                val name = k.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val count = longOrNull(v)?.toInt() ?: return@mapNotNull null
                name to count
            }
            .toMap()

    /** Raw `{key: value}` string map for flat alias/label objects. */
    fun stringMap(map: Map<*, *>?, key: String): Map<String, String> =
        (mapField(map, key)).orEmpty().entries
            .mapNotNull { (k, v) ->
                val name = k.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val value = v?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                name to value
            }
            .toMap()
}
