package ru.mars.mobile.local

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.mars.mobile.LocalChatPresenter
import ru.mars.mobile.LocalModelPick
import ru.mars.mobile.MarsModelLog
import ru.mars.network.local.LocalModelInfo
import ru.mars.network.local.LocalProviderProfile
import ru.mars.network.local.LocalProviderState
import ru.mars.network.local.LocalProviderStore
import ru.mars.network.local.LocalToolExecutor
import ru.mars.network.local.LocalToolResult
import ru.mars.network.local.OkHttpLocalChatClient
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Смена модели в локальном чате: выбор в пикере обязан доехать до ТЕЛА запроса.
 *
 * Почему тест на проводе, а не на состоянии: `state().active.model` может быть верной,
 * а запрос уйти со старой моделью (другой профиль, другой снимок). Единственное честное
 * доказательство — строка `"model"` в том, что реально принял сервер, плюс та же модель
 * в строке `MarsModel` (`adb logcat -s MarsModel`), по которой разбирают жалобу на телефоне.
 *
 * Хранилище здесь в памяти ([MemoryStore]): проверяется связка «пикер → запись модели →
 * презентер → клиент», а JSON-хранилище `LocalProviderPrefsStore` проверяется отдельно
 * на устройстве. Клиент — настоящий ([OkHttpLocalChatClient]) против MockWebServer.
 */
class LocalModelSwitchTest {

    private val logs = CopyOnWriteArrayList<String>()
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        MarsModelLog.sink = { line -> logs.add(line) }
        server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"),
        )
        server.start()
    }

    @After
    fun tearDown() {
        MarsModelLog.sink = null
        server.shutdown()
    }

    /**
     * Главный сценарий жалобы: выбрал `gpt-6.1-sol` — следующий запрос обязан нести его.
     */
    @Test
    fun pickedModelGoesOnTheWire() {
        val store = store()
        val presenter = presenter(store)
        presenter.bindProfile(store.state().active)

        // Пикер открыт на этом профиле, затем человек тапнул строку.
        val snapshot = store.state().active ?: error("нет активного профиля")
        LocalModelPick.apply(store, snapshot, LocalModelInfo(id = PICKED, contextWindow = 128_000))
        presenter.setModel(PICKED)

        assertEquals("выбор не лёг в активный профиль", PICKED, store.state().active?.model)

        runBlocking {
            presenter.send(
                profile = store.state().active ?: error("профиль пропал"),
                apiKey = TEST_KEY,
                tools = emptyList(),
                text = "which model are you",
            )
        }

        val body = server.takeRequest().body.readUtf8()
        assertTrue("в теле запроса нет выбранной модели: $body", body.contains("\"model\":\"$PICKED\""))
        // Та же модель в логе, который смотрят на телефоне: `adb logcat -s MarsModel`.
        assertTrue("нет строки выбора: $logs", logs.any { it.contains("model=$PICKED") })
        assertTrue(
            "нет строки запроса: $logs",
            logs.any { it.startsWith("request model=$PICKED") },
        )
    }

    /**
     * Снимок профиля, снятый при открытии пикера, устаревает: пока список был открыт,
     * экран «Провайдер» мог сохранить профиль с новым `id` и сделать его активным.
     * Запись по снимку уходила в профиль, которого `state().active` уже не видит, —
     * выбор «терялся», и следующий запрос нёс старую модель. Ровно эта жалоба.
     */
    @Test
    fun pickLandsInActiveProfileEvenWhenSnapshotIsStale() {
        val store = store()
        val snapshot = store.state().active ?: error("нет активного профиля")

        // Профиль пересоздан в настройках (новый id) и сделан активным.
        store.saveProfile(profile(id = "p2", model = DEFAULT_MODEL))
        store.setActive("p2")

        LocalModelPick.apply(store, snapshot, LocalModelInfo(id = PICKED, contextWindow = 128_000))

        assertEquals("выбор ушёл в неактивный профиль", PICKED, store.state().active?.model)
        assertEquals("p2", store.state().activeId)
    }

    /** Активного профиля нет вовсе — выбор всё равно должен стать активным. */
    @Test
    fun pickOnProfileWithoutActiveMakesItActive() {
        val store = store(active = false)
        val snapshot = store.state().profiles.first()

        LocalModelPick.apply(store, snapshot, LocalModelInfo(id = PICKED))

        assertEquals(PICKED, store.state().active?.model)
    }

    /** Адрес провайдера — тестовый сервер: тело запроса принимает он, а не сеть. */
    private fun baseUrl(): String = server.url("/v1").toString().trimEnd('/')

    private fun store(model: String = DEFAULT_MODEL, active: Boolean = true): MemoryStore =
        MemoryStore(baseUrl(), model, active)

    private fun presenter(store: LocalProviderStore): LocalChatPresenter = LocalChatPresenter(
        client = OkHttpLocalChatClient(),
        baseExecutor = LocalToolExecutor { LocalToolResult(ok = true, content = "ok") },
        onProfileChange = { updated -> store.saveProfile(updated); updated },
    )

    private fun profile(id: String = "p1", model: String): LocalProviderProfile =
        LocalProviderProfile(
            id = id,
            title = "PlusVibe",
            baseUrl = baseUrl(),
            model = model,
        )

    /** Хранилище в памяти: те же гарантии, что у prefs-версии — мерж по id и активный id. */
    private class MemoryStore(
        baseUrl: String,
        model: String,
        active: Boolean = true,
    ) : LocalProviderStore {

        private var profiles: List<LocalProviderProfile> = listOf(
            LocalProviderProfile(
                id = "p1",
                title = "PlusVibe",
                baseUrl = baseUrl,
                model = model,
            )
        )
        private var activeId: String? = if (active) "p1" else null

        override fun state(): LocalProviderState =
            LocalProviderState(profiles = profiles, activeId = activeId)

        override fun saveProfile(profile: LocalProviderProfile) {
            profiles = profiles.filterNot { it.id == profile.id } + profile
            if (activeId == null) activeId = profile.id
        }

        override fun removeProfile(id: String) {
            profiles = profiles.filterNot { it.id == id }
        }

        override fun setActive(id: String?) {
            activeId = id
        }

        override fun apiKey(profileId: String): String = TEST_KEY

        override fun saveApiKey(profileId: String, key: String) = Unit

        override fun clearApiKey(profileId: String) = Unit
    }

    private companion object {
        /** Заводская модель профиля: ровно то, что приходит «всегда». */
        const val DEFAULT_MODEL = ""

        const val PICKED = "gpt-6.1-sol"

        /** Ключ-заглушка: настоящий в тест не попадает и никуда не уходит. */
        const val TEST_KEY = "test-key"
    }
}
