package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ServerLogFile
import ru.mars.network.model.ServerLogTail
import ru.mars.network.model.ServerLogWindow

/** Чистые тесты [LogsPolicy]: whitelist файл/tail, подписи и формат окна. */
class LogsPolicyTest {

    @Test
    fun fileOptionsMirrorServerWhitelistExactly() {
        // Whitelist сервера: {agent, errors, gateway} — и ничего больше;
        // произвольный путь лога через политику непредставим типом.
        assertEquals(listOf("agent", "errors", "gateway"), LogsPolicy.FILE_OPTIONS.map { it.wireKey })
        assertEquals(ServerLogFile.values().toList(), LogsPolicy.FILE_OPTIONS)
    }

    @Test
    fun tailOptionsMirrorServerWhitelistExactly() {
        assertEquals(listOf(100, 200, 500, 1000), LogsPolicy.TAIL_OPTIONS.map { it.lines })
        assertEquals(ServerLogTail.values().toList(), LogsPolicy.TAIL_OPTIONS)
    }

    @Test
    fun fileLabelsAreShortAndDistinct() {
        val labels = LogsPolicy.FILE_OPTIONS.map { LogsPolicy.fileLabel(it) }
        assertEquals(listOf("Агент", "Ошибки", "Шлюз"), labels)
        assertEquals(labels.size, labels.distinct().size)
        labels.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun tailLabelsCarryLineCounts() {
        assertEquals("100 строк", LogsPolicy.tailLabel(ServerLogTail.TAIL_100))
        assertEquals("200 строк", LogsPolicy.tailLabel(ServerLogTail.TAIL_200))
        assertEquals("500 строк", LogsPolicy.tailLabel(ServerLogTail.TAIL_500))
        assertEquals("1000 строк", LogsPolicy.tailLabel(ServerLogTail.TAIL_1000))
    }

    @Test
    fun defaultsAreAgentAnd200() {
        assertEquals(ServerLogFile.AGENT, LogsPolicy.DEFAULT_FILE)
        assertEquals(ServerLogTail.TAIL_200, LogsPolicy.DEFAULT_TAIL)
    }

    @Test
    fun formatBytesScalesUnits() {
        assertEquals("0 Б", LogsPolicy.formatBytes(0))
        assertEquals("1023 Б", LogsPolicy.formatBytes(1023))
        assertEquals("1.0 КБ", LogsPolicy.formatBytes(1024))
        assertEquals("1.5 КБ", LogsPolicy.formatBytes(1536))
        assertEquals("1023.9 КБ", LogsPolicy.formatBytes(1048575))
        assertEquals("1.0 МБ", LogsPolicy.formatBytes(1048576))
        assertEquals("4.0 МБ", LogsPolicy.formatBytes(4L * 1024 * 1024))
        // Отрицательный размер (мусор с сервера) не роняет подпись.
        assertEquals("0 Б", LogsPolicy.formatBytes(-1))
    }

    @Test
    fun metaShowsLineCountAndSize() {
        val window = ServerLogWindow(
            file = "agent",
            tail = 200,
            lines = listOf("a", "b", "c"),
            totalBytes = 2048,
        )
        assertEquals("строк: 3 · 2.0 КБ", LogsPolicy.meta(window))
    }

    @Test
    fun emptyMessagePrefersServerHint() {
        val hinted = ServerLogWindow(hint = "Log file for agent not found yet.")
        assertEquals("Log file for agent not found yet.", LogsPolicy.emptyMessage(hinted))
        assertEquals(LogsPolicy.MSG_EMPTY, LogsPolicy.emptyMessage(ServerLogWindow()))
        assertEquals(LogsPolicy.MSG_EMPTY, LogsPolicy.emptyMessage(ServerLogWindow(hint = "   ")))
    }

    @Test
    fun invalidRequestMessageIsNeverBlank() {
        assertTrue(LogsPolicy.msgInvalidRequest("Unknown log file").contains("Unknown log file"))
        assertTrue(LogsPolicy.msgInvalidRequest("").isNotBlank())
    }
}
