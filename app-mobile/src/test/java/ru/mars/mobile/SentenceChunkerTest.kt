package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Границы фраз, склейка, разрезание и фильтрация разметки.
 *
 * Проверяем именно то, от чего зависит задержка первого звука: первая фраза должна выйти
 * на первом знаке конца предложения, а не после всего ответа.
 */
class SentenceChunkerTest {

    @Test
    fun `первая фраза выходит сразу по точке, не дожидаясь остального ответа`() {
        val chunker = SentenceChunker()

        assertEquals(emptyList<String>(), chunker.feed("Привет"))
        assertEquals(listOf("Привет мир."), chunker.feed(" мир. Как де"))
    }

    @Test
    fun `короткая фраза склеивается со следующей`() {
        val chunker = SentenceChunker()

        val ready = chunker.feed("Это довольно длинное первое предложение. Да. ")

        assertEquals(listOf("Это довольно длинное первое предложение."), ready)
        // «Да.» короче порога — уходит только в конце хода, а не отдельным запросом синтеза.
        assertEquals(listOf("Да."), chunker.flush())
    }

    @Test
    fun `не режет на сокращениях и версиях`() {
        val chunker = SentenceChunker()

        val ready = chunker.feed("Обновил до версии 1.7.6, т.е. последней. Дальше всё работает штатно.")

        // Точки версии и сокращения не режут: первая фраза — ровно первое предложение.
        assertEquals("Обновил до версии 1.7.6, т.е. последней.", ready.first())
        assertTrue(ready.none { it == "Обновил до версии 1.7.6, т." })
    }

    @Test
    fun `дробное число не режется, а точка после порта режет`() {
        val chunker = SentenceChunker()

        assertEquals(listOf("Точность 3.14 по формуле."), chunker.feed("Точность 3.14 по формуле. Потом пояснение."))
        assertEquals(listOf("Порт 8090."), SentenceChunker().feed("Порт 8090. Он слушает запросы."))
    }

    @Test
    fun `блок кода не попадает в озвучку`() {
        val chunker = SentenceChunker()

        val ready = chunker.feed("Начало ответа.\n```kotlin\nval x = 1\n```\nКонец ответа тут.")

        assertEquals(listOf("Начало ответа."), ready)
        assertEquals(listOf("Конец ответа тут."), chunker.flush())
    }

    @Test
    fun `таблицы, ссылки и заголовки дают только читаемый текст`() {
        val chunker = SentenceChunker()

        val ready = chunker.feed(
            "## Итоги\nСмотри [отчёт](https://example.com/x) и таблицу.\n" +
                "| a | b |\n|---|---|\nГотово, всё работает нормально.",
        )
        val all = ready + chunker.flush()

        assertTrue(all.none { it.contains("http") })
        assertTrue(all.none { it.contains("|") })
        assertTrue(all.none { it.contains("##") })
        assertTrue(all.any { it.contains("Смотри отчёт и таблицу.") })
        assertTrue(all.any { it == "Готово, всё работает нормально." })
    }

    @Test
    fun `длинный кусок режется по запятым в пределах лимита`() {
        val chunker = SentenceChunker()
        val long = "Первая часть фразы довольно длинная, " +
            "вторая часть тоже длинная и подробная, ".repeat(6) +
            "конец."

        val ready = chunker.feed(long)

        assertTrue(ready.isNotEmpty())
        assertTrue(ready.all { it.length <= SentenceChunker.DEFAULT_MAX_PHRASE_LENGTH })
    }

    @Test
    fun `flush выпускает остаток без знака конца и сбрасывает состояние`() {
        val chunker = SentenceChunker()

        chunker.feed("Это длинное предложение без точки в конце")

        assertEquals(listOf("Это длинное предложение без точки в конце"), chunker.flush())
        assertEquals(emptyList<String>(), chunker.flush())
    }

    @Test
    fun `reset выбрасывает накопленный текст`() {
        val chunker = SentenceChunker()
        chunker.feed("Текст, который уже не нужен")

        chunker.reset()

        assertEquals(emptyList<String>(), chunker.flush())
    }
}
