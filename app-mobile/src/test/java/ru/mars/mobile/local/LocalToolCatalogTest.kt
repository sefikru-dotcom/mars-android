package ru.mars.mobile.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import ru.mars.mobile.LocalSessionRetention
import ru.mars.mobile.LocalSessionStore
import ru.mars.mobile.localtools.LocalToolCatalog
import ru.mars.network.local.LocalMessage
import ru.mars.network.local.LocalRole

/**
 * Контракт каталога инструментов телефона.
 *
 * Почему проверяется именно это: список уходит провайдеру как есть, а его **порядок**
 * и состав — часть префикса запроса. Перестановка меняет байты промпта и обнуляет
 * кэш провайдера (каждый ход дорожает), а случайное выпадение инструмента человек
 * замечает только тогда, когда модель «не умеет» ставить будильник.
 *
 * Контекст здесь — `null`: конструкторы инструментов его принимают, но проверка
 * схем и флагов не обращается к Android. Это позволяет проверять каталог на чистой
 * JVM без Robolectric; сами системные вызовы проверяются на устройстве.
 */
class LocalToolCatalogTest {

    // Заглушка `Context`: схемам инструментов платформа не нужна (см. FakeContext).
    private fun catalog() = LocalToolCatalog.tools(FakeContext())

    /**
     * Заглушка контекста для сборки каталога.
     *
     * Конструкторы вызывают `context.applicationContext ?: context` — на заглушке
     * вернётся `null`, и каталог возьмёт переданный объект. Ни один инструмент при
     * создании к Android не обращается, поэтому заглушка безопасна и не требует
     * ни Robolectric, ни устройства.
     */

    @Test
    fun catalogHasExactlyTwelveToolsInStableOrder() {
        val names = catalog().map { it.schema.name }
        assertEquals(
            listOf(
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
            ),
            names,
        )
    }

    @Test
    fun catalogOrderIsDeterministicAcrossCalls() {
        // Два вызова подряд: порядок не должен зависеть от итерации по множеству.
        assertEquals(catalog().map { it.schema.name }, catalog().map { it.schema.name })
    }

    @Test
    fun toolNamesAreUnique() {
        val names = catalog().map { it.schema.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun destructiveToolsRequireConfirmation() {
        val tools = catalog().associateBy { it.schema.name }
        // Затирает буфер обмена, ставит будильник, пишет в календарь — всё это меняет
        // устройство без возможности «отменить» одним нажатием.
        assertTrue("write_clipboard", tools.getValue("write_clipboard").requiresConfirmation)
        assertTrue("set_alarm", tools.getValue("set_alarm").requiresConfirmation)
        assertTrue("create_event", tools.getValue("create_event").requiresConfirmation)
    }

    @Test
    fun rememberWritesMemoryWithoutConfirmation() {
        val tools = catalog().associateBy { it.schema.name }
        // Запись факта в память безобидна и обратима (память чистится в настройках),
        // поэтому диалог на каждый «запомни, что…» не показываем.
        assertTrue("remember", !tools.getValue("remember").requiresConfirmation)
        val schema = org.json.JSONObject(tools.getValue("remember").schema.parametersJson)
        assertEquals("text", schema.getJSONArray("required").getString(0))
    }

    @Test
    fun webSearchTakesRequiredQueryAndNeedsNoConfirmation() {
        val tools = catalog().associateBy { it.schema.name }
        // Поиск ничего не меняет на устройстве — диалог подтверждения только мешал бы.
        assertTrue("web_search", !tools.getValue("web_search").requiresConfirmation)
        val schema = org.json.JSONObject(tools.getValue("web_search").schema.parametersJson)
        assertEquals("query", schema.getJSONArray("required").getString(0))
        assertEquals(
            "string",
            schema.getJSONObject("properties").getJSONObject("query").getString("type"),
        )
    }

    @Test
    fun readOnlyToolsDoNotRequireConfirmation() {
        val tools = catalog().associateBy { it.schema.name }
        assertTrue("read_clipboard", !tools.getValue("read_clipboard").requiresConfirmation)
        assertTrue("get_battery", !tools.getValue("get_battery").requiresConfirmation)
        assertTrue("get_device_info", !tools.getValue("get_device_info").requiresConfirmation)
        assertTrue("list_events", !tools.getValue("list_events").requiresConfirmation)
        assertTrue("find_contact", !tools.getValue("find_contact").requiresConfirmation)
    }

    @Test
    fun everySchemaHasDescriptionAndJsonObjectParameters() {
        catalog().forEach { tool ->
            val schema = tool.schema
            assertTrue("пустое описание у ${schema.name}", schema.description.isNotBlank())
            // Схема параметров — JSON-объект: провайдеры отвергают не-объект целиком.
            assertTrue(
                "parametersJson у ${schema.name} не объект: ${schema.parametersJson.take(40)}",
                schema.parametersJson.trimStart().startsWith("{"),
            )
            @Suppress("UNUSED_VARIABLE")
            val parsed = org.json.JSONObject(schema.parametersJson) // бросает при битом JSON
        }
    }
}

/**
 * Лимиты хранилища и защита пути — вторая половина приёмки волны 1.
 *
 * Отдельный класс, а не дополнение к `LocalSessionStoreTest`, потому что здесь
 * проверяются ГРАНИЦЫ (200/100) на реальных файлах, а не отдельные функции.
 */
class LocalSessionLimitsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = LocalSessionStore(tmp.newFolder())

    private fun message(index: Int) = LocalMessage(
        id = "m$index",
        role = LocalRole.USER,
        text = "текст $index",
        createdAt = index.toLong(),
    )

    @Test
    fun messageLimitIsTwoHundred() {
        assertEquals(200, LocalSessionStore.MAX_MESSAGES)
    }

    @Test
    fun sessionLimitIsHundred() {
        assertEquals(100, LocalSessionStore.MAX_SESSIONS)
    }

    @Test
    fun savingBeyondMessageLimitKeepsExactlyTheLimit() {
        val store = store()
        val session = store.create()
        store.save(session, (1..250).map(::message))

        val stored = store.messages(session.id)
        assertEquals(LocalSessionStore.MAX_MESSAGES, stored.size)
        // Хвост: последнее сообщение диалога обязано выжить.
        assertEquals("m250", stored.last().id)
    }

    @Test
    fun tempFilesNeverSurviveSave() {
        val dir = tmp.newFolder()
        val store = LocalSessionStore(dir)
        val session = store.create()
        store.save(session, listOf(message(1)))

        val tmpFiles = dir.listFiles()!!.filter { it.name.endsWith(LocalSessionRetention.TEMP_SUFFIX) }
        assertTrue("остались временные файлы: $tmpFiles", tmpFiles.isEmpty())
    }
}
