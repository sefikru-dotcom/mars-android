package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.SessionItem

class SessionSearchPolicyTest {

    @Test
    fun singleCharacterQueryStaysLocalOnly() {
        // Один символ вернул бы с сервера почти весь список — дорого и бесполезно.
        assertFalse(SessionSearchPolicy.shouldQueryServer("а"))
    }

    @Test
    fun twoCharacterQueryGoesToServer() {
        assertTrue(SessionSearchPolicy.shouldQueryServer("аб"))
    }

    @Test
    fun blankQueryNeverGoesToServer() {
        assertFalse(SessionSearchPolicy.shouldQueryServer(""))
        assertFalse(SessionSearchPolicy.shouldQueryServer("   "))
    }

    @Test
    fun surroundingWhitespaceDoesNotCountTowardsMinimum() {
        // " а " — это один символ запроса, а не три.
        assertFalse(SessionSearchPolicy.shouldQueryServer(" а "))
        assertTrue(SessionSearchPolicy.shouldQueryServer("   аб   "))
    }

    @Test
    fun serverOnlyDropsRowsAlreadyVisibleLocally() {
        val remote = listOf(item("a"), item("b"), item("c"))
        val shown = SessionSearchPolicy.serverOnly(remote, setOf("a", "c"))
        assertEquals(listOf("b"), shown.map { it.id })
    }

    @Test
    fun serverOnlyDeduplicatesRepeatedRows() {
        // Сервер может повторить строку в ответе — второй раз её рисовать нельзя.
        val remote = listOf(item("a"), item("a"), item("b"))
        assertEquals(listOf("a", "b"), SessionSearchPolicy.serverOnly(remote, emptySet()).map { it.id })
    }

    @Test
    fun serverOnlyDropsRowsWithoutId() {
        val remote = listOf(item(""), item("ok"))
        assertEquals(listOf("ok"), SessionSearchPolicy.serverOnly(remote, emptySet()).map { it.id })
    }

    @Test
    fun serverOnlyPreservesServerOrder() {
        // Релевантность расставляет сервер: пересортировка на клиенте её бы стёрла.
        val remote = listOf(item("z"), item("y"), item("x"))
        assertEquals(listOf("z", "y", "x"), SessionSearchPolicy.serverOnly(remote, emptySet()).map { it.id })
    }

    @Test
    fun sectionLabelCarriesMatchCount() {
        assertEquals("Найдено на сервере: 3", SessionSearchPolicy.sectionLabel(3))
    }

    @Test
    fun matchNoteExplainsWhyRowIsHereWhenTitleDoesNotMatch() {
        // Сервер ищет и по содержимому сообщений — строка, где ни заголовок, ни
        // превью не содержат запроса, нашлась где-то в глубине диалога, и без
        // пояснения выглядит случайной.
        val row = SessionItem("s1", "Бюджет на квартал", preview = "обсудили сроки")
        assertEquals("совпадение внутри диалога", SessionSearchPolicy.matchNote(row, "логистика"))
    }

    @Test
    fun matchNoteIsNullWhenPreviewItselfMatches() {
        // Совпадение видно прямо в строке превью — пояснять нечего.
        val row = SessionItem("s1", "Бюджет на квартал", preview = "обсудили сроки")
        assertNull(SessionSearchPolicy.matchNote(row, "сроки"))
    }

    @Test
    fun matchNoteIsNullWhenTitleItselfMatches() {
        val row = SessionItem("s1", "Бюджет на квартал")
        assertNull(SessionSearchPolicy.matchNote(row, "бюджет"))
    }

    @Test
    fun matchNoteIsNullForBlankQuery() {
        assertNull(SessionSearchPolicy.matchNote(SessionItem("s1", "Что угодно"), "  "))
    }

    private fun item(id: String) = SessionItem(id, "Диалог $id")
}
