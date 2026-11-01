package ru.mars.mobile

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import ru.mars.network.local.LocalChatClient
import ru.mars.network.local.LocalChatEvent
import ru.mars.network.local.LocalMessage
import ru.mars.network.local.LocalProviderProfile
import ru.mars.network.local.LocalToolSchema
import ru.mars.network.local.OkHttpLocalChatClient

/**
 * Точка сборки транспорта локального чата.
 *
 * Реализация подключена прямой ссылкой на
 * `ru.mars.network.local.OkHttpLocalChatClient`, а не через `Class.forName`.
 *
 * Раньше здесь стояло отражение: экран писали параллельно с ядром, и прямая ссылка не
 * давала собраться, пока чужой файл не появился. Цена оказалась неочевидной — R8 в
 * release считает класс, упомянутый только строкой, недостижимым и **вырезает его**.
 * На POCO F5 это выглядело как «транспорт недоступен: клиент провайдера не собран в
 * этой сборке» при живом коде и верном ключе. Прямая ссылка делает класс достижимым
 * по построению и ломает сборку при изменении контракта — это лучше молчаливого
 * отказа в бою.
 *
 * Ключ API через этот объект не проходит и нигде не логируется.
 */
internal object LocalChatTransport {

    /** Текст для человека: пустой провайдер и отсутствующий транспорт — разные причины. */
    const val UNAVAILABLE = "транспорт недоступен: клиент провайдера не собран в этой сборке"

    /** Клиент или `null`, если реализация не собралась. Исключений не бросает. */
    fun clientOrNull(): LocalChatClient? = runCatching { OkHttpLocalChatClient() }.getOrNull()

    /**
     * Клиент, который всегда отвечает «транспорт недоступен».
     *
     * Остаётся на случай, если конструктор реализации начнёт требовать настроек:
     * экран тогда не падает, а честно сообщает причину, и лента с историей работают.
     */
    fun clientOrUnavailable(): LocalChatClient = clientOrNull() ?: UnavailableClient

    /** Заглушка: транспорт ещё не собран. Ошибка приходит событием, а не исключением. */
    private object UnavailableClient : LocalChatClient {
        override fun stream(
            profile: LocalProviderProfile,
            apiKey: String,
            messages: List<LocalMessage>,
            tools: List<LocalToolSchema>,
        ): Flow<LocalChatEvent> = flowOf(LocalChatEvent.Error(UNAVAILABLE))

        override suspend fun models(
            profile: LocalProviderProfile,
            apiKey: String,
        ): Result<List<String>> = Result.failure(IllegalStateException(UNAVAILABLE))
    }
}
