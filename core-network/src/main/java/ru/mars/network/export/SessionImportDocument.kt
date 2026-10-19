package ru.mars.network.export

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Подготовка JSON-документа сессии к `POST /api/session/import`.
 *
 * Контракт сервера (hermes-webui `api/routes.py::_handle_session_import`): тело
 * запроса — объект сессии, у которого ОБЯЗАТЕЛЕН массив `messages`; `title`,
 * `workspace`, `model`, `pinned`, `tool_calls` необязательны. Ровно такой объект
 * отдаёт `GET /api/session/export`, поэтому клиент не пересобирает документ
 * по полям, а отправляет файл как есть — сервер сам решает, что из него взять.
 *
 * Разбор отделён от сети: файл мог быть сохранён другой версией клиента или
 * обёрнут в `{"session": {...}}` (форма `GET /api/session`), поэтому подготовка
 * терпима к обеим формам, но НЕ терпима к документу без массива сообщений —
 * иначе сервер ответил бы 400 после лишнего запроса.
 */
sealed interface SessionImportDocument {
    /** Готовое тело запроса: ровно то, что уйдёт в `POST /api/session/import`. */
    data class Ready(val fields: Map<String, Any?>) : SessionImportDocument

    /** Файл не годится в импорт; [reason] показывается пользователю как есть. */
    data class Invalid(val reason: String) : SessionImportDocument

    companion object {
        private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        private val adapter = Moshi.Builder().build().adapter<Map<String, Any?>>(mapType)

        /**
         * Разбор текста файла. BOM и пробелы по краям снимаются: файл мог быть
         * сохранён Windows-редактором или переслан через мессенджер.
         */
        fun prepare(raw: String): SessionImportDocument {
            val text = raw.trim().removePrefix("\uFEFF").trim()
            if (text.isEmpty()) return Invalid("Файл пуст")
            val root = try {
                adapter.fromJson(text)
            } catch (_: Exception) {
                null
            } ?: return Invalid("Файл не является JSON-объектом")

            // Форма экспорта плоская; форма detail — с обёрткой "session". Пустой
            // объект-обёртка не должен заслонять плоские поля, поэтому вложенная
            // форма берётся только когда в ней есть сообщения.
            val nested = asStringMap(root["session"] as? Map<*, *>)
            val fields = if (nested != null && nested["messages"] is List<*>) nested else root

            if (fields["messages"] !is List<*>) return Invalid("В файле нет массива messages")
            if (fields.containsKey("tool_calls") && fields["tool_calls"] !is List<*>) {
                return Invalid("Поле tool_calls должно быть массивом")
            }
            if (fields.containsKey("title") && fields["title"] != null && fields["title"] !is String) {
                return Invalid("Поле title должно быть строкой")
            }
            return Ready(fields)
        }

        /** Объект Moshi → типизированная карта (ключи JSON всегда строки). */
        private fun asStringMap(value: Map<*, *>?): Map<String, Any?>? {
            value ?: return null
            val out = LinkedHashMap<String, Any?>(value.size)
            value.forEach { (key, entry) -> if (key is String) out[key] = entry }
            return out
        }
    }
}
