package ru.mars.tv.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ServerLogFile
import ru.mars.network.model.ServerLogTail
import ru.mars.network.model.ServerLogWindow

/**
 * Unit-тесты [TvLogsPolicy] без Android-фреймворка: подписи опций файла/хвоста
 * (mobile `LogsPolicy` parity), мета-строка окна, формат размера и честные
 * пустые состояния (hint сервера vs «Файл пока пуст»).
 */
class TvLogsPolicyTest {

    @Test
    fun `file labels mirror the server whitelist in d-pad order`() {
        assertEquals(listOf("Агент", "Ошибки", "Шлюз"), TvLogsPolicy.FILE_OPTIONS.map { TvLogsPolicy.fileLabel(it) })
        assertEquals(listOf(ServerLogFile.AGENT, ServerLogFile.ERRORS, ServerLogFile.GATEWAY), TvLogsPolicy.FILE_OPTIONS)
    }

    @Test
    fun `tail labels show the line counts of the server whitelist`() {
        assertEquals(listOf("100 строк", "200 строк", "500 строк", "1000 строк"), TvLogsPolicy.TAIL_OPTIONS.map { TvLogsPolicy.tailLabel(it) })
        assertEquals(ServerLogTail.TAIL_200, TvLogsPolicy.DEFAULT_TAIL)
        assertEquals(ServerLogFile.AGENT, TvLogsPolicy.DEFAULT_FILE)
    }

    @Test
    fun `bytes formatting switches units at the right thresholds`() {
        assertEquals("0 B", TvLogsPolicy.formatBytes(0))
        assertEquals("512 B", TvLogsPolicy.formatBytes(512))
        assertEquals("1.0 KiB", TvLogsPolicy.formatBytes(1024))
        assertEquals("1.5 KiB", TvLogsPolicy.formatBytes(1536))
        assertEquals("2.0 MiB", TvLogsPolicy.formatBytes(2 * 1024 * 1024))
        assertEquals("3 GiB", TvLogsPolicy.formatBytes(3L * 1024 * 1024 * 1024))
    }

    @Test
    fun `meta shows size, truncation flag and mtime when present`() {
        val plain = TvLogsPolicy.meta(ServerLogWindow(totalBytes = 2048))
        assertEquals("2.0 KiB", plain)

        val truncated = TvLogsPolicy.meta(ServerLogWindow(totalBytes = 2048, truncated = true))
        assertEquals("2.0 KiB · обрезано", truncated)

        val withMtime = TvLogsPolicy.meta(ServerLogWindow(totalBytes = 10, mtime = 0.0))
        assertTrue("mtime должен попадать в мету", withMtime.startsWith("10 B · изменено "))
        assertFalse(withMtime.endsWith("· изменено "))
    }

    @Test
    fun `empty window prefers the server hint over generic copy`() {
        val missing = ServerLogWindow(hint = "Log file for agent not found yet.")
        assertEquals("Log file for agent not found yet.", TvLogsPolicy.emptyMessage(missing))
        assertEquals("Файл пока пуст", TvLogsPolicy.emptyMessage(ServerLogWindow()))
    }

    @Test
    fun `window title counts lines and survives a blank file name`() {
        assertEquals("Файл agent · 3 строк", TvLogsPolicy.windowTitle(ServerLogWindow(file = "agent", lines = listOf("a", "b", "c"))))
        assertEquals("Файл ? · 0 строк", TvLogsPolicy.windowTitle(ServerLogWindow()))
    }

    @Test
    fun `focus restoration clamps and never wraps`() {
        assertEquals(TvLogsPolicy.NO_SELECTION, TvLogsPolicy.restoreSelection(5, 0))
        assertEquals(9, TvLogsPolicy.restoreSelection(42, 10))
        assertEquals(0, TvLogsPolicy.restoreSelection(-3, 10))
    }
}
