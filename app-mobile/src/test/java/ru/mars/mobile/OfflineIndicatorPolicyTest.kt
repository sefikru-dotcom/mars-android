package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-тесты индикатора офлайна (требование «видимый, но не блокирующий»): текст говорит
 * и про отсутствие связи, и про возраст показанной копии, а при неизвестном моменте
 * сохранения не выдумывает «только что».
 */
class OfflineIndicatorPolicyTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `chat hint without snapshot moment is the base line only`() {
        assertEquals(OfflineIndicatorPolicy.CHAT_OFFLINE_BASE, OfflineIndicatorPolicy.chatHint(null, now))
        assertEquals(OfflineIndicatorPolicy.CHAT_OFFLINE_BASE, OfflineIndicatorPolicy.chatHint(0L, now))
        assertEquals(OfflineIndicatorPolicy.CHAT_OFFLINE_BASE, OfflineIndicatorPolicy.chatHint(-5L, now))
    }

    @Test
    fun `chat hint appends cache age`() {
        val hint = OfflineIndicatorPolicy.chatHint(now - 5 * 60_000L, now)

        assertTrue(hint.startsWith(OfflineIndicatorPolicy.CHAT_OFFLINE_BASE))
        assertTrue(hint.endsWith("Кэш (5 мин назад)"))
        assertEquals(
            OfflineIndicatorPolicy.CHAT_OFFLINE_BASE + OfflineIndicatorPolicy.SEPARATOR + "Кэш (5 мин назад)",
            hint,
        )
    }

    @Test
    fun `list hint keeps the network banner text and adds age`() {
        val base = "Нет связи — в очереди 2 сообщения. Отправим автоматически"

        val hint = OfflineIndicatorPolicy.listHint(base, now - 3 * 60 * 60_000L, now)

        assertEquals("$base · Кэш (3 ч назад)", hint)
    }

    @Test
    fun `list hint without snapshot is untouched banner text`() {
        val base = "Нет связи. Сообщения будут поставлены в очередь и отправлены автоматически"
        assertEquals(base, OfflineIndicatorPolicy.listHint(base, null, now))
    }

    @Test
    fun `fresh snapshot reads as just now`() {
        assertEquals(
            OfflineIndicatorPolicy.CHAT_OFFLINE_BASE + " · Кэш (только что)",
            OfflineIndicatorPolicy.chatHint(now - 10_000L, now),
        )
    }

    @Test
    fun `legacy status constant matches indicator base`() {
        assertEquals(OfflineIndicatorPolicy.CHAT_OFFLINE_BASE, ChatActivity.OFFLINE_SESSION_HINT)
    }
}
