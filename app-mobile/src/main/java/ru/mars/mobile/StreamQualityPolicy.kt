package ru.mars.mobile

/**
 * Ручной профиль качества аудиопотока Mars Music (паритет с WebUI: сервер принимает
 * явный query-параметр `quality` в `GET /api/stream/{id}`).
 *
 * Контракт «обратного дефолта»: [DEFAULT] НЕ добавляет параметр в URL — запрос
 * байт-в-байт совпадает с историческим `{base}/api/stream/{id}`
 * ([ru.mars.network.repository.MusicRepository.getStreamUrl]), и сервер отдаёт поток
 * своего стандартного качества. Приложение никогда не ухудшает качество принудительно:
 * профиль выбирает только пользователь, а неизвестное/пустое значение деградирует
 * в [DEFAULT] ([fromQueryValue]), а не в «экономию».
 *
 * Чистая модель (Android-free), JVM-тесты: StreamUrlPolicyTest.
 */
enum class StreamQuality(val queryValue: String?) {
    /** Серверный дефолт: параметр `quality` отсутствует в URL (обратная совместимость). */
    DEFAULT(null),

    /** Исходное качество без пережатия. */
    ORIGINAL("original"),

    /** Стандартный профиль сервера. */
    STANDARD("standard"),

    /** Экономный профиль (минимальный битрейт). */
    ECONOMY("economy");

    /** true, когда профиль явно передаётся серверу query-параметром. */
    val isExplicit: Boolean get() = queryValue != null

    companion object {
        /**
         * Разбор значения query-параметра `quality`: точные (регистронезависимые)
         * `original`/`standard`/`economy`; всё остальное (null, пустая строка, мусор,
         * `default`, `auto`) — обратный дефолт [DEFAULT], т.е. URL без параметра.
         */
        fun fromQueryValue(raw: String?): StreamQuality {
            val key = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return DEFAULT
            return entries.firstOrNull { it.queryValue == key } ?: DEFAULT
        }
    }
}

/**
 * Чистый конструктор URL аудиопотока: `{base}/api/stream/{id}` + опциональный
 * `?quality=…` для явного профиля [StreamQuality].
 *
 * Паритет с [ru.mars.network.repository.MusicRepository.getStreamUrl] (тот же путь,
 * то же percent-кодирование id, нормализация завершающего слэша base) — контракт
 * закрепляет StreamUrlPolicyTest, включая сравнение с живым маппером репозитория.
 * Android-free: только строки, без Uri/HttpUrl, чтобы policy была тестируемой на JVM.
 */
object StreamUrlPolicy {
    /** Имя query-параметра качества (контракт сервера `/api/stream`). */
    const val QUALITY_PARAM = "quality"

    /** Путь стримингового эндпоинта относительно корня сервиса. */
    private const val STREAM_PATH = "/api/stream/"

    /**
     * Полный URL потока для [trackId] на [baseUrl] с профилем [quality]:
     * - [StreamQuality.DEFAULT] → параметр отсутствует (исторический URL);
     * - явный профиль → `?quality=original|standard|economy`;
     * - завершающий слэш base отбрасывается (защита от `music//api/stream`,
     *   как в MusicRepository.getStreamUrl);
     * - id percent-кодируется как path-сегмент (пробел → `%20`, `/` → `%2F`),
     *   поэтому «кривой» id не может согнуть путь или протащить свой query.
     */
    fun streamUrl(
        baseUrl: String,
        trackId: String,
        quality: StreamQuality = StreamQuality.DEFAULT,
    ): String {
        val base = baseUrl.trimEnd('/')
        val url = StringBuilder(base)
            .append(STREAM_PATH)
            .append(encodePathSegment(trackId))
        quality.queryValue?.let { url.append('?').append(QUALITY_PARAM).append('=').append(it) }
        return url.toString()
    }

    /**
     * Percent-кодирование path-сегмента (паритет с OkHttp `addPathSegment` для
     * типичных id): unreserved-символы RFC 3986 и безопасные sub-delims проходят
     * как есть, всё прочее (пробел, `/`, `?`, `#`, `&`, `=`, не-ASCII) кодируется
     * побайтово в UTF-8. Пробел → `%20` (не `+`: это путь, а не form-encoding).
     */
    fun encodePathSegment(value: String): String {
        val out = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt().toChar()
            val safe = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
                c == '-' || c == '.' || c == '_' || c == '~' ||
                c == '!' || c == '$' || c == '&' || c == '\'' || c == '(' || c == ')' ||
                c == '*' || c == '+' || c == ',' || c == ';' || c == '=' || c == ':' || c == '@'
            if (safe) {
                out.append(c)
            } else {
                out.append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }
        return out.toString()
    }
}
