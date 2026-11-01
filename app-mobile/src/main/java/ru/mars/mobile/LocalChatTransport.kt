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

    /**
     * Единственный клиент на процесс.
     *
     * Раньше [clientOrUnavailable] создавал НОВЫЙ `OkHttpLocalChatClient` на каждый экран,
     * и метка конфигурации ([ru.mars.network.local.LocalProviderSeq]) у чата и у настроек
     * оказывалась разной: смена адреса поднимала счётчик в одном объекте, а поток сверялся
     * с другим и о смене не узнавал. Дефект невидим в тестах — там клиент один, — но на
     * телефоне защита от гонки не работала вовсе. Один экземпляр делает метку общей.
     *
     * Отдельного `bump()` рядом с фабрикой: экран настроек и экран чата меняют профиль в
     * разных активностях, и обеим нужна одна и та же метка. Создаётся лениво и без
     * исключений: недоступный конструктор не должен ронять навигацию.
     */
    private val shared: LocalChatClient? by lazy { runCatching { OkHttpLocalChatClient() }.getOrNull() }

    /**
     * Общая метка конфигурации провайдера.
     *
     * `null` — транспорт не собрался; тогда и инвалидировать нечего. Вызывающие зовут
     * [noteConfigurationChanged], а не лезут в клиент напрямую: экран не обязан знать,
     * как устроен транспорт.
     */
    private val providerSeq: ru.mars.network.local.LocalProviderSeq?
        get() = (shared as? OkHttpLocalChatClient)?.providerSeq

    /**
     * Отметить смену конфигурации: профиль, адрес, тип API или модель.
     *
     * Зовётся экранами в момент записи профиля. Все шаги, начатые до этого, замолкают
     * ([OkHttpLocalChatClient.stream] сверяет метку перед каждым эмитом) — ответ по
     * старому адресу не попадёт в диалог, который уже ведётся с новой моделью.
     */
    fun noteConfigurationChanged() {
        providerSeq?.bump()
    }

    /** Клиент или `null`, если реализация не собралась. Исключений не бросает. */
    fun clientOrNull(): LocalChatClient? = shared

    /**
     * Клиент, который всегда отвечает «транспорт недоступен».
     *
     * Остаётся на случай, если конструктор реализации начнёт требовать настроек:
     * экран тогда не падает, а честно сообщает причину, и лента с историей работают.
     */
    fun clientOrUnavailable(): LocalChatClient = shared ?: UnavailableClient

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
