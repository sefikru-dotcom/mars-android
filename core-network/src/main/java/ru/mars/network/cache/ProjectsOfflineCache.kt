package ru.mars.network.cache

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Офлайн-кэш списка проектов: хранит сырой JSON-ответ (`GET /api/projects`) как есть.
 *
 * Назначение то же, что у [SessionOfflineCache]: при сбое сети репозиторий отвечает
 * пустым результатом, и без кэша сбой неотличим от «проектов нет». Кэш отдаёт последний
 * сохранённый JSON, а [getSavedTimestamp] позволяет подписать возраст данных.
 *
 * Почему сырой JSON, а не разобранные модели: контракт списка проектов ещё устоявается,
 * и парсинг живёт в репозитории/UI-слое; кэш лишь сохраняет последний успешный ответ,
 * не теряя полей, которые он не знает.
 *
 * Контракт (повторяет [SessionOfflineCache]):
 * - Класс чистый: зависит только от порта [KeyValueStore], без Android-фреймворка и сети —
 *   полностью покрыт JVM unit-тестами.
 * - Ничего не бросает: битый JSON, чужая версия формата или падающее хранилище дают
 *   «данных нет» (null/false), а не crash; следующая успешная запись перетирает мусор.
 * - Пустое/пробельное содержимое отклоняется (false): «сохранено ничего» неотличимо
 *   от сбоя сети и не должно вытеснять живой снимок.
 * - Объём ограничен: JSON длиннее [MAX_JSON_CHARS] отклоняется целиком (усечение
 *   превратило бы его в битый JSON), SharedPreferences не место для мегабайтов.
 * - Данные приватные: кэш стирается при выходе из аккаунта ([clear]) вместе с остальными
 *   локальными plaintext-хранилищами.
 *
 * Не потокобезопасен: вызывать из одного места (экран/репозиторий-координатор), как
 * и [SessionOfflineCache].
 */
class ProjectsOfflineCache(
    private val store: KeyValueStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Сохраняет JSON списка проектов. Отклоняет (false) пустое/пробельное содержимое
     * и строки длиннее [MAX_JSON_CHARS]; false также означает «хранилище не приняло запись».
     */
    fun saveProjectsJson(json: String): Boolean {
        if (json.isBlank() || json.length > MAX_JSON_CHARS) return false
        return runCatching {
            store.putString(
                KEY_PROJECTS,
                mapAdapter.toJson(
                    mapOf("v" to CACHE_VERSION, "saved_at" to clock(), "json" to json),
                ),
            )
            true
        }.getOrDefault(false)
    }

    /** Сохранённый JSON проектов; null — кэша нет или служебная запись бита. */
    fun loadProjectsJson(): String? = runCatching {
        val body = readEnvelope(store.getString(KEY_PROJECTS) ?: return null) ?: return null
        (body["json"] as? String)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Момент сохранения снимка (epoch millis) для расчёта возраста кэша;
     * null — кэша нет или служебная запись бита.
     */
    fun getSavedTimestamp(): Long? = runCatching {
        val body = readEnvelope(store.getString(KEY_PROJECTS) ?: return null) ?: return null
        (body["saved_at"] as? Number)?.toLong()
    }.getOrNull()

    /** Полное стирание кэша — вызывается на всех путях выхода из авторизации. */
    fun clear() {
        runCatching { store.clearAll() }
    }

    /** Разбор конверта; null — мусор, чужая версия или не-объект (данные считаются отсутствующими). */
    private fun readEnvelope(raw: String): Map<String, Any?>? {
        val parsed = mapAdapter.fromJson(raw) ?: return null
        val version = (parsed["v"] as? Number)?.toInt()
        if (version != CACHE_VERSION) return null
        return parsed
    }

    private companion object {
        const val CACHE_VERSION = 1
        const val KEY_PROJECTS = "projects_json"

        /** Потолок длины сохраняемого JSON (превышение отклоняется, не усекается). */
        const val MAX_JSON_CHARS = 200_000

        val MAP_TYPE = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        val moshi = Moshi.Builder().build()
        val mapAdapter = moshi.adapter<Map<String, Any?>>(MAP_TYPE)
    }
}
