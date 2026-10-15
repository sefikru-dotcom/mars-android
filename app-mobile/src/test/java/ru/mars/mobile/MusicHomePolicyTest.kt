package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MusicShelf
import ru.mars.network.model.MusicTrack

/**
 * Контракты персональной ленты «Для тебя» (MUS-HOME): пустые полки не рендерятся,
 * порядок сервера сохраняется, очередь воспроизведения — плоская конкатенация видимых.
 */
class MusicHomePolicyTest {

    private fun track(id: String) = MusicTrack(id, "T$id", "A$id")

    @Test
    fun emptyFeedIsReportedEmpty() {
        assertTrue(MusicHomePolicy.isFeedEmpty(emptyList()))
        assertTrue(
            MusicHomePolicy.isFeedEmpty(
                listOf(
                    MusicShelf("continue", "Продолжить слушать", emptyList()),
                    MusicShelf("new", "Новинки", emptyList()),
                ),
            ),
        )
    }

    @Test
    fun visibleShelvesSkipEmptyAndPreserveServerOrder() {
        val shelves = listOf(
            MusicShelf("continue", "Продолжить слушать", emptyList()),
            MusicShelf("new", "Новинки", listOf(track("n1"))),
            MusicShelf("for_you", "Для тебя", emptyList()),
            MusicShelf("similar", "Похожие на «Song»", listOf(track("s1"), track("s2"))),
        )
        assertEquals(listOf("new", "similar"), MusicHomePolicy.visibleShelves(shelves).map { it.id })
    }

    @Test
    fun playbackQueueConcatenatesVisibleShelvesInRenderOrder() {
        val shelves = listOf(
            MusicShelf("continue", "Продолжить слушать", listOf(track("c1"))),
            MusicShelf("new", "Новинки", emptyList()),
            MusicShelf("similar", "Похожие на «Song»", listOf(track("s1"))),
        )
        assertEquals(listOf("c1", "s1"), MusicHomePolicy.playbackQueue(shelves).map { it.id })
    }

    @Test
    fun isFeedEmptyFalseWhenAnyShelfHasTracks() {
        assertFalse(
            MusicHomePolicy.isFeedEmpty(
                listOf(MusicShelf("new", "Новинки", listOf(track("n1")))),
            ),
        )
    }
}
