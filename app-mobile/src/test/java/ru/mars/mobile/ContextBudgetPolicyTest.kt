package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ChatEvent

class ContextBudgetPolicyTest {

    private fun usage(used: Int, max: Int, percent: Int, threshold: Int = 0) =
        ChatEvent.ContextUsage(
            usedTokens = used,
            maxTokens = max,
            percent = percent,
            thresholdTokens = threshold,
        )

    @Test
    fun `unknown usage hides the pill`() {
        val pill = ContextBudgetPolicy.pill(null, compressionRunning = false)
        assertFalse(pill.visible)
        assertNull(pill.text)
    }

    @Test
    fun `unresolved window (maxTokens 0) hides the pill instead of showing fake percent`() {
        val pill = ContextBudgetPolicy.pill(usage(0, 0, 0), compressionRunning = false)
        assertFalse(pill.visible)
    }

    @Test
    fun `pill text is percent plus compact used and total tokens`() {
        val pill = ContextBudgetPolicy.pill(usage(12_345, 200_000, 6), compressionRunning = false)
        assertTrue(pill.visible)
        assertEquals("6% · 12.3k/200.0k", pill.text)
        assertEquals("Заполнение контекста: 6%", pill.contentDescription)
    }

    @Test
    fun `compression overrides the measurement and marks the pill as progress`() {
        val pill = ContextBudgetPolicy.pill(usage(10, 100, 10), compressionRunning = true)
        assertTrue(pill.visible)
        assertTrue(pill.compression)
        assertEquals(ContextBudgetPolicy.COMPRESSION_LABEL, pill.text)
        assertEquals("Идёт сжатие контекста", pill.contentDescription)
        // Compression wins even with no measurement yet.
        assertTrue(ContextBudgetPolicy.pill(null, compressionRunning = true).visible)
    }

    @Test
    fun `level thresholds follow the webui ring colors`() {
        assertEquals(ContextBudgetLevel.NORMAL, ContextBudgetPolicy.level(0))
        assertEquals(ContextBudgetLevel.NORMAL, ContextBudgetPolicy.level(50))
        assertEquals(ContextBudgetLevel.WARNING, ContextBudgetPolicy.level(51))
        assertEquals(ContextBudgetLevel.WARNING, ContextBudgetPolicy.level(75))
        assertEquals(ContextBudgetLevel.CRITICAL, ContextBudgetPolicy.level(76))
        assertEquals(ContextBudgetLevel.CRITICAL, ContextBudgetPolicy.level(100))
    }

    @Test
    fun `pill carries the ring percent for the composer ring`() {
        // Кольцо в композере рисует дугу по этому числу; текст `NN% · used/total`
        // остался для деталей и тестов паритета.
        assertEquals(6, ContextBudgetPolicy.pill(usage(12_345, 200_000, 6), false).percent)
        assertEquals(77, ContextBudgetPolicy.pill(usage(154_000, 200_000, 77), false).percent)
        // Сжатие не имеет процента: кольцо показывает полную дугу с многоточием.
        assertNull(ContextBudgetPolicy.pill(usage(10, 100, 10), true).percent)
        assertNull(ContextBudgetPolicy.pill(null, false).percent)
    }

    @Test
    fun `pill carries the level for coloring`() {
        assertEquals(
            ContextBudgetLevel.NORMAL,
            ContextBudgetPolicy.pill(usage(10, 100, 10), false).level,
        )
        assertEquals(
            ContextBudgetLevel.WARNING,
            ContextBudgetPolicy.pill(usage(60, 100, 60), false).level,
        )
        assertEquals(
            ContextBudgetLevel.CRITICAL,
            ContextBudgetPolicy.pill(usage(90, 100, 90), false).level,
        )
    }

    @Test
    fun `details text lists fill percent and token counts`() {
        val details = ContextBudgetPolicy.detailsText(usage(1_500, 32_000, 5))
        assertEquals(
            "Заполнено: 5%\nИспользовано токенов: 1.5k\nРазмер окна: 32.0k\n" +
                "Сервер сожмёт при: порог не сообщён",
            details,
        )
    }

    @Test
    fun `details text shows the server auto compression threshold in tokens and percent`() {
        // Живой payload: window 200k, threshold_tokens 160k -> «Сервер сожмёт при: 160.0k · 80%».
        val details = ContextBudgetPolicy.detailsText(usage(50_000, 200_000, 25, threshold = 160_000))
        assertEquals(
            "Заполнено: 25%\nИспользовано токенов: 50.0k\nРазмер окна: 200.0k\n" +
                "Сервер сожмёт при: 160.0k · 80%",
            details,
        )
    }

    @Test
    fun `details text keeps a threshold larger than the window honest`() {
        // Порог больше окна: автосжатие сервера уже не наступит — показываем >100%, не врём «100%».
        val details = ContextBudgetPolicy.detailsText(usage(10_000, 200_000, 5, threshold = 250_000))
        assertTrue(details.endsWith("Сервер сожмёт при: 250.0k · 125%"))
    }

    @Test
    fun `details text for unknown measurement says it is not measured yet`() {
        assertEquals(ContextBudgetPolicy.UNKNOWN_DETAILS, ContextBudgetPolicy.detailsText(null))
        assertEquals(ContextBudgetPolicy.UNKNOWN_DETAILS, ContextBudgetPolicy.detailsText(usage(0, 0, 0)))
    }

    @Test
    fun `unknown window hides the pill even when a threshold is present`() {
        // context_length<=0: окна нет — ни процента, ни порога, ни подписи кольца (#1436).
        val pill = ContextBudgetPolicy.pill(usage(50_000, 0, 0, threshold = 160_000), false)
        assertFalse(pill.visible)
        assertNull(pill.autoCompression)
        assertNull(ContextBudgetPolicy.autoCompressionLabel(usage(50_000, 0, 0, threshold = 160_000)))
        assertNull(ContextBudgetPolicy.thresholdPercent(usage(50_000, 0, 0, threshold = 160_000)))
    }

    @Test
    fun `ring caption carries the webui auto compression wording`() {
        // Эталон WebUI `context_auto_compress_at`: «Автосжатие при <порог> · <процент>%».
        val pill = ContextBudgetPolicy.pill(usage(50_000, 200_000, 25, threshold = 160_000), false)
        assertEquals("Автосжатие при 160.0k · 80%", pill.autoCompression)
        assertEquals(
            "Заполнение контекста: 25%. Автосжатие при 160.0k · 80%",
            pill.contentDescription,
        )
        assertEquals(80, ContextBudgetPolicy.thresholdPercent(usage(50_000, 200_000, 25, threshold = 160_000)))
    }

    @Test
    fun `ring caption stays short when the server sent no threshold`() {
        val pill = ContextBudgetPolicy.pill(usage(50_000, 200_000, 25), false)
        assertNull(pill.autoCompression)
        assertEquals("Заполнение контекста: 25%", pill.contentDescription)
        assertNull(ContextBudgetPolicy.autoCompressionLabel(null))
        assertNull(ContextBudgetPolicy.autoCompressionLabel(usage(50_000, 200_000, 25, threshold = 0)))
    }
}
