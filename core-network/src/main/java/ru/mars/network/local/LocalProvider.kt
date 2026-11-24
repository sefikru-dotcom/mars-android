package ru.mars.network.local

/**
 * Профиль прямого провайдера: адрес, модель и параметры хода.
 *
 * Ключ API здесь НЕ живёт: он хранится отдельно ([LocalProviderStore.apiKey]) в
 * шифрованном хранилище приложения, потому что профиль сериализуется в открытом
 * виде (для экрана настроек и для диагностики) и не должен содержать секрет.
 */
data class LocalProviderProfile(
    val id: String,
    val title: String,
    /** Базовый адрес OpenAI-совместимого API без хвостового слэша, напр. `https://plusvibeapi.ru/v1`. */
    val baseUrl: String,
    /** Имя модели для поля `model` в теле запроса. */
    val model: String,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    /** `reasoning_effort`, если провайдер его поддерживает; null — поле не отправляется. */
    val reasoningEffort: String? = null,
    /**
     * Размер окна контекста модели, как его сообщил `/models`; null — неизвестен.
     *
     * Хранится в профиле, а не вычисляется на месте, потому что каталог тянется по сети
     * и не всегда доступен (офлайн, смена адреса). Сохранённое окно позволяет пилюле
     * контекста работать сразу после запуска, без ожидания ответа каталога.
     */
    val contextWindow: Int? = null,
)

/**
 * Состояние профилей. `activeId == null` — провайдер ещё не настроен, локальный чат
 * обязан честно сказать об этом, а не отправлять запрос в никуда.
 */
data class LocalProviderState(
    val profiles: List<LocalProviderProfile> = emptyList(),
    val activeId: String? = null,
) {
    val active: LocalProviderProfile? get() = profiles.firstOrNull { it.id == activeId }
}

/**
 * Хранилище профилей и ключей.
 *
 * Разделение намеренное: профили — обычные предпочтения, ключ — только через
 * [saveApiKey] (шифрование в AndroidKeyStore). Ни один метод не возвращает ключ
 * в UI-поток автоматически; вызывающий сам решает, куда его девать.
 */
interface LocalProviderStore {
    fun state(): LocalProviderState
    fun saveProfile(profile: LocalProviderProfile)
    fun removeProfile(id: String)
    fun setActive(id: String?)
    /** Ключ API профиля; `null` — не сохранён или хранилище недоступно. */
    fun apiKey(profileId: String): String?
    fun saveApiKey(profileId: String, key: String)
    fun clearApiKey(profileId: String)
}

/**
 * Сборка адресов провайдера — единственное место, где из базового URL получаются пути.
 *
 * Почему отдельный объект: и `chat/completions`, и `models` строятся из одного и того же
 * нормализованного адреса, а расхождение «в чате один слэш, в проверке ключа два» даёт
 * 404 на ровном месте и выглядит как неверный ключ.
 */
object LocalProviderUrls {

    /**
     * Приводит ввод человека к рабочему адресу.
     *
     * Правила: пустая строка остаётся пустой (не `https://`); схема по умолчанию `https`;
     * хвостовые слэши срезаются; уже заданная схема (`http`/`https`) сохраняется.
     */
    fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val withScheme = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            else -> "https://$trimmed"
        }
        return withScheme.trimEnd('/')
    }

    /** `{base}/chat/completions`. */
    fun chatCompletionsUrl(baseUrl: String): String = normalizeBaseUrl(baseUrl) + "/chat/completions"

    /** `{base}/models`. */
    fun modelsUrl(baseUrl: String): String = normalizeBaseUrl(baseUrl) + "/models"
}
