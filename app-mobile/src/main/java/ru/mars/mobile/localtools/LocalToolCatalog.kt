package ru.mars.mobile.localtools

import android.content.Context
import ru.mars.mobile.LocalProviderDefaults
import ru.mars.mobile.LocalProviderPrefsStore

/**
 * Каталог инструментов телефона — единственная точка сборки списка.
 *
 * Порядок **стабильный и осмысленный**: сначала то, что меняет устройство (будильник,
 * таймер, событие), потом чтение (расписание, контакты, буфер, батарея, железо), затем
 * запись в память (`remember`), затем два «наружу»: генерация картинки (`generate_image`)
 * и поиск в интернете (`web_search`).
 * Менять порядок нельзя: список уходит провайдеру как есть, а схемы инструментов —
 * часть префикса запроса. Перестановка двух элементов меняет байты запроса и обнуляет
 * кэш промпта у провайдера, то есть удорожает каждый следующий ход.
 *
 * Новые инструменты добавляются **только в конец** по той же причине: схема наверху
 * сдвинула бы все схемы ниже.
 *
 * Контекст — `applicationContext`: инструменты создаются один раз на экран и живут
 * дольше Activity, а держать Activity в долгоживущем объекте нельзя.
 */
object LocalToolCatalog {

    /**
     * Имена в том же порядке, что и [tools]. Отдельный список, а не литерал внутри:
     * порядок — часть контракта с провайдером, и его проверяет JVM-тест.
     */
    val NAMES: List<String> = listOf(
        "set_alarm",
        "set_timer",
        "list_events",
        "create_event",
        "find_contact",
        "read_clipboard",
        "write_clipboard",
        "get_battery",
        "get_device_info",
        "remember",
        "generate_image",
        "web_search",
    )

    /**
     * Двенадцать инструментов в фиксированном порядке.
     *
     * `generate_image` и `web_search` создаются последними: новый элемент в конце не
     * сдвигает позиции схем выше. Их конструкторы поднимают `OkHttpClient` только при
     * первом обращении к клиенту (лениво), поэтому сборка каталога остаётся дешёвой.
     *
     * `web_search` получает адрес и ключ **поставщиком**, а не значением: каталог
     * собирается на JVM-тестах без Android, и чтение AndroidKeyStore в конструкторе
     * упало бы там. Поставщик обращается к активному профилю в момент вызова
     * инструмента — заодно видит смену профиля посреди сессии.
     */
    fun tools(context: Context): List<LocalDeviceTool> {
        val app = context.applicationContext ?: context
        return listOf(
            SetAlarmTool(app),
            SetTimerTool(app),
            ListEventsTool(app),
            CreateEventTool(app),
            FindContactTool(app),
            ReadClipboardTool(app),
            WriteClipboardTool(app),
            GetBatteryTool(app),
            GetDeviceInfoTool(app),
            RememberTool(app),
            GenerateImageTool(app),
            WebSearchTool(credentials = { activeCredentials(app) }),
        )
    }

    /**
     * Ключ и адрес активного профиля на момент вызова `web_search`.
     *
     * `null` — провайдер не настроен либо ключ не сохранён: инструмент ответит
     * «Поиск не удался: у активного профиля нет ключа API», а не полезет в сеть с
     * пустым `Authorization`.
     *
     * Ошибки хранилища (недоступный Keystore, битые предпочтения) наружу не бросаются:
     * инструмент — не место для краха экрана, ему достаточно `null`.
     */
    private fun activeCredentials(context: Context): ProviderCredentials? = runCatching {
        val store = LocalProviderPrefsStore(context)
        val profile = store.state().active ?: return@runCatching null
        val apiKey = store.apiKey(profile.id)?.takeIf { it.isNotBlank() } ?: return@runCatching null
        ProviderCredentials(
            baseUrl = profile.baseUrl.ifBlank { LocalProviderDefaults.BASE_URL },
            apiKey = apiKey,
        )
    }.getOrNull()
}
