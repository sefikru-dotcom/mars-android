package ru.mars.network.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Кэш возможностей модели (B2).
 *
 * Главное, что здесь проверяется, — правило «неизвестно ≠ нет»: пустой ответ `/models`
 * не имеет права превратиться в «модель ничего не умеет». Второе по важности — адрес в
 * ключе: после смены сервера старые возможности не отдаются (fail-closed), иначе окно
 * контекста и поддержка картинок окажутся от чужого сервера.
 */
class LocalCapabilityCacheTest {

    @Test
    fun putThenGetReturnsCapabilities() {
        val cache = LocalCapabilityCache()
        val model = LocalModelInfo(
            id = "gpt-4o",
            contextWindow = 128_000,
            maxOutputTokens = 16_384,
            supportsReasoningEffort = true,
            inputModalities = listOf("text", "image"),
        )

        cache.put(KEY, listOf(model))

        assertEquals(model, cache.get(KEY, "gpt-4o"))
    }

    @Test
    fun invalidateEmptiesEverything() {
        val cache = LocalCapabilityCache()
        cache.put(KEY, listOf(LocalModelInfo(id = "m1", contextWindow = 8_000)))

        cache.invalidate()

        assertNull("после сброса возможностей не знаем", cache.get(KEY, "m1"))
    }

    @Test
    fun otherProviderKeyGivesNull() {
        // Тот же id модели на другом сервере — другие возможности. Отдавать старые нельзя.
        val cache = LocalCapabilityCache()
        cache.put("https://plusvibeapi.ru/v1", listOf(LocalModelInfo(id = "m1", contextWindow = 200_000)))

        assertNull(cache.get("https://other.example/v1", "m1"))
        assertNotNull("свой ключ обязан остаться", cache.get("https://plusvibeapi.ru/v1", "m1"))
    }

    @Test
    fun unknownModelGivesNullNotNegativeAnswer() {
        val cache = LocalCapabilityCache()
        cache.put(KEY, listOf(LocalModelInfo(id = "m1", contextWindow = 8_000)))

        assertNull("модели нет в каталоге — это «неизвестно»", cache.get(KEY, "m2"))
    }

    @Test
    fun emptyCatalogIsNotCachedAsAbsence() {
        val cache = LocalCapabilityCache()

        cache.put(KEY, emptyList())

        assertNull("пустой каталог — «неизвестно», а не «возможностей нет»", cache.get(KEY, "m1"))
    }

    @Test
    fun emptyCatalogDropsStaleEntryInsteadOfKeepingIt() {
        // Свежий ответ «моделей нет» отменяет прежнее знание: показывать старое окно
        // контекста как текущее — врать про процент заполнения.
        val cache = LocalCapabilityCache()
        cache.put(KEY, listOf(LocalModelInfo(id = "m1", contextWindow = 8_000)))

        cache.put(KEY, emptyList())

        assertNull(cache.get(KEY, "m1"))
    }

    @Test
    fun blankProviderKeyIsNeverCached() {
        val cache = LocalCapabilityCache()

        cache.put("", listOf(LocalModelInfo(id = "m1", contextWindow = 8_000)))
        cache.put("   ", listOf(LocalModelInfo(id = "m1", contextWindow = 8_000)))

        assertNull("без адреса кэшировать нечего", cache.get("", "m1"))
        assertNull(cache.get("   ", "m1"))
    }

    @Test
    fun blankModelIdGivesNull() {
        val cache = LocalCapabilityCache()
        cache.put(KEY, listOf(LocalModelInfo(id = "", contextWindow = 8_000)))

        assertNull(cache.get(KEY, ""))
        assertNull("элемент без имени не занимает место в кэше", cache.get(KEY, "m1"))
    }

    @Test
    fun duplicateIdsLastWins() {
        val cache = LocalCapabilityCache()
        val fresh = LocalModelInfo(id = "m1", contextWindow = 200_000)

        cache.put(KEY, listOf(LocalModelInfo(id = "m1", contextWindow = 8_000), fresh))

        assertEquals(fresh, cache.get(KEY, "m1"))
    }

    @Test
    fun keyForIgnoresTrailingSlashAndProfileId() {
        // Один и тот же сервер, записанный по-разному, обязан давать один ключ: иначе кэш
        // промахивается на ровном месте и человек ждёт сеть там, где ответ уже был.
        val first = LocalProviderProfile(id = "p1", title = "A", baseUrl = "https://host/v1", model = "m")
        val second = LocalProviderProfile(id = "p2", title = "B", baseUrl = "  https://host/v1//  ", model = "m")

        assertEquals("https://host/v1", LocalCapabilityCache.keyFor(first))
        assertEquals(LocalCapabilityCache.keyFor(first), LocalCapabilityCache.keyFor(second))
    }

    @Test
    fun capabilitiesAreVisibleFromAnotherThread() {
        // Вызовы идут с разных нитей (пилюля контекста, экран выбора модели, IO-диспетчер).
        // Проверяем не скорость, а отсутствие потерянных записей и исключений.
        val cache = LocalCapabilityCache()
        val start = CountDownLatch(1)
        val writers = (0 until 4).map { index ->
            thread {
                start.await(5, TimeUnit.SECONDS)
                repeat(200) { round ->
                    cache.put("https://host/$index", listOf(LocalModelInfo(id = "m$round", contextWindow = round + 1)))
                }
            }
        }
        val readers = (0 until 4).map {
            thread {
                start.await(5, TimeUnit.SECONDS)
                repeat(200) { round -> cache.get("https://host/0", "m$round") }
            }
        }

        start.countDown()
        (writers + readers).forEach { it.join(10_000) }
        (writers + readers).forEach { assertTrue("нить обязана была закончить", !it.isAlive) }

        assertEquals(LocalModelInfo(id = "m199", contextWindow = 200), cache.get("https://host/0", "m199"))
    }

    private companion object {
        const val KEY = "https://plusvibeapi.ru/v1"
    }
}
