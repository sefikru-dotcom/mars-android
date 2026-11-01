package ru.mars.network.local

import kotlinx.coroutines.flow.Flow

/**
 * Транспорт к OpenAI-совместимому провайдеру напрямую, без сервера Марса.
 *
 * Ключ передаётся параметром вызова, а не хранится в клиенте: клиент — синглтон на
 * приложение, и держать в нём секрет значит продлить его жизнь до конца процесса без
 * необходимости. Хранилище ключей вызывающий закрывает сам.
 */
interface LocalChatClient {

    /**
     * Один шаг: отправить [messages] и отдать стрим событий.
     *
     * Гарантии контракта: поток НЕ бросает исключений — любая ошибка приходит
     * [LocalChatEvent.Error]; после [LocalChatEvent.AssistantFinished] поток завершается;
     * отмена корутины закрывает соединение.
     */
    fun stream(
        profile: LocalProviderProfile,
        apiKey: String,
        messages: List<LocalMessage>,
        tools: List<LocalToolSchema> = emptyList(),
    ): Flow<LocalChatEvent>

    /**
     * `GET {base}/models` — проверка адреса и ключа.
     *
     * Возвращает отказ, а не пустой список, если сервер ответил 401/403: пустой список
     * выглядит как «провайдер жив, но моделей нет», и человек ищет проблему не там.
     */
    suspend fun models(profile: LocalProviderProfile, apiKey: String): Result<List<String>>

    /**
     * `GET {base}/models` с метаданными: окно контекста и поддержка reasoning.
     *
     * Отдельно от [models], потому что вызывающих двое с разными нуждами: настройки
     * проверяют ключ и им хватает имён, а пилюле контекста и выбору модели нужен размер
     * окна. Расширять [models] новым типом значило бы ломать проверку ключа на ровном
     * месте. Реализация по умолчанию — отказ, чтобы фейки в тестах не обязывались
     * поддерживать метод, которого они не касаются.
     */
    suspend fun catalog(
        profile: LocalProviderProfile,
        apiKey: String,
    ): Result<List<LocalModelInfo>> = models(profile, apiKey)
        .map { ids -> ids.map { LocalModelInfo(id = it) } }
}
