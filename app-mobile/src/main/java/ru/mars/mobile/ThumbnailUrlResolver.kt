package ru.mars.mobile

/**
 * Чистое преобразование серверных thumbnail-URL в абсолютные.
 *
 * Сервер музыки отдаёт относительные пути вида `/music/api/thumb?v=ID`, которые уже
 * содержат префикс `/music`. Резолвить их нужно от корня сайта (origin), а НЕ от
 * baseUrl музыкального API — иначе получается двойной `/music/music/api/thumb`.
 *
 * Контракт:
 *  - null/пусто -> null;
 *  - абсолютные http(s) сохраняются как есть;
 *  - протокол-относительные `//host/path` получают схему origin;
 *  - относительные пути приклеиваются к origin (с нормализацией слэша);
 *  - любые прочие схемы (javascript:, data:, file:, content: и т.п.) отвергаются -> null.
 *
 * Класс не зависит от Android и тестируется на чистой JVM.
 */
object ThumbnailUrlResolver {

    const val DEFAULT_ORIGIN: String = "https://marscore.ru"

    private val ALLOWED_SCHEME = Regex("^https?://", RegexOption.IGNORE_CASE)
    private val ANY_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    fun resolve(raw: String?, origin: String = DEFAULT_ORIGIN): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (ALLOWED_SCHEME.containsMatchIn(value)) return value
        if (ANY_SCHEME.containsMatchIn(value)) return null
        val base = normalizeOrigin(origin) ?: return null
        return if (value.startsWith("//")) {
            base.substringBefore("://") + ":" + value
        } else {
            base + if (value.startsWith("/")) value else "/$value"
        }
    }

    private fun normalizeOrigin(origin: String): String? {
        val trimmed = origin.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        if (!ALLOWED_SCHEME.containsMatchIn(trimmed)) return null
        return trimmed
    }
}
