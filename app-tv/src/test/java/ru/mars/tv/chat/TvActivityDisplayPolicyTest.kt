package ru.mars.tv.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.mars.network.model.ChatEvent

/**
 * Режимы показа хода на ТВ: проводные значения (WebUI/мобильный паритет), цикл
 * переключения, персистентность выбора и поведение политики (строки транскрипта
 * в «Всё», глушение статус-полосы в «Тишине»).
 */
class TvActivityDisplayPolicyTest {

    /** In-memory порт хранения вместо SharedPreferences. */
    private class FakeStorage(initial: TvActivityDisplayMode? = null) : TvActivityModeStorage {
        var saved: TvActivityDisplayMode? = initial
        override fun load(): TvActivityDisplayMode =
            TvActivityDisplayMode.fromWire(saved?.wire)
        override fun save(mode: TvActivityDisplayMode) { saved = mode }
    }

    @Test
    fun `wire values match WebUI and mobile`() {
        assertEquals("stream_full", TvActivityDisplayMode.STREAM_FULL.wire)
        assertEquals("stream_window", TvActivityDisplayMode.STREAM_WINDOW.wire)
        assertEquals("stream_quiet", TvActivityDisplayMode.STREAM_QUIET.wire)
    }

    @Test
    fun `fromWire falls back to default on unknown or blank`() {
        assertEquals(TvActivityDisplayMode.DEFAULT, TvActivityDisplayMode.fromWire(null))
        assertEquals(TvActivityDisplayMode.DEFAULT, TvActivityDisplayMode.fromWire("  "))
        assertEquals(TvActivityDisplayMode.DEFAULT, TvActivityDisplayMode.fromWire("stream_loud"))
        assertEquals(TvActivityDisplayMode.STREAM_QUIET, TvActivityDisplayMode.fromWire(" stream_quiet "))
        assertEquals(TvActivityDisplayMode.STREAM_WINDOW, TvActivityDisplayMode.DEFAULT)
    }

    @Test
    fun `cycle is FULL to WINDOW to QUIET to FULL`() {
        assertEquals(TvActivityDisplayMode.STREAM_WINDOW, TvActivityDisplayMode.STREAM_FULL.next())
        assertEquals(TvActivityDisplayMode.STREAM_QUIET, TvActivityDisplayMode.STREAM_WINDOW.next())
        assertEquals(TvActivityDisplayMode.STREAM_FULL, TvActivityDisplayMode.STREAM_QUIET.next())
    }

    @Test
    fun `selection restores persisted pick and saves cycles`() {
        val storage = FakeStorage()
        val selection = TvActivityModeSelection(storage)
        assertEquals(TvActivityDisplayMode.STREAM_WINDOW, selection.current)
        selection.cycle()
        assertEquals(TvActivityDisplayMode.STREAM_QUIET, selection.current)
        assertEquals(TvActivityDisplayMode.STREAM_QUIET, storage.saved)
        // Перезапуск экрана: выбор восстановлен из хранилища.
        val restored = TvActivityModeSelection(FakeStorage(storage.saved))
        assertEquals(TvActivityDisplayMode.STREAM_QUIET, restored.current)
        assertEquals("Тишина", restored.buttonLabel())
    }

    @Test
    fun `full mode duplicates thoughts and tools into transcript, others do not`() {
        val thought = ChatEvent.Thought("думаю о разметке")
        val call = ChatEvent.ToolCall("t1", "terminal", "ls")
        val result = ChatEvent.ToolResult("t1", "terminal", "ok")
        assertEquals("Мысль: думаю о разметке", TvActivityDisplayPolicy.transcriptLine(TvActivityDisplayMode.STREAM_FULL, thought))
        assertEquals("Инструмент: terminal", TvActivityDisplayPolicy.transcriptLine(TvActivityDisplayMode.STREAM_FULL, call))
        assertEquals("Готово: terminal", TvActivityDisplayPolicy.transcriptLine(TvActivityDisplayMode.STREAM_FULL, result))
        // «Окно» и «Тишина» держат детали в статус-полосе — лента чистая.
        listOf(TvActivityDisplayMode.STREAM_WINDOW, TvActivityDisplayMode.STREAM_QUIET).forEach { mode ->
            assertNull(TvActivityDisplayPolicy.transcriptLine(mode, thought))
            assertNull(TvActivityDisplayPolicy.transcriptLine(mode, call))
            assertNull(TvActivityDisplayPolicy.transcriptLine(mode, result))
        }
        // Пустая мысль строки не даёт.
        assertNull(TvActivityDisplayPolicy.transcriptLine(TvActivityDisplayMode.STREAM_FULL, ChatEvent.Thought("   ")))
        // События ответа/терминала в ленту не дублируются ни в каком режиме.
        assertNull(TvActivityDisplayPolicy.transcriptLine(TvActivityDisplayMode.STREAM_FULL, ChatEvent.Chunk("текст")))
        assertNull(TvActivityDisplayPolicy.transcriptLine(TvActivityDisplayMode.STREAM_FULL, ChatEvent.Done))
    }

    @Test
    fun `quiet mode collapses activity statuses while turn is live`() {
        val thought = ChatEvent.Thought("шаг")
        // «Тишина» + живой ход + деталь хода -> краткая строка.
        assertEquals(
            TvActivityDisplayPolicy.QUIET_LIVE_STATUS,
            TvActivityDisplayPolicy.status(TvActivityDisplayMode.STREAM_QUIET, turnLive = true, lastEvent = thought, fallback = "Марс размышляет…"),
        )
        // Явный вердикт (ход завершён) «Тишина» НЕ глушит.
        assertEquals(
            "Ответ получен",
            TvActivityDisplayPolicy.status(TvActivityDisplayMode.STREAM_QUIET, turnLive = false, lastEvent = ChatEvent.Done, fallback = "Ответ получен"),
        )
        // «Всё»/«Окно» показывают статус как есть даже на живом ходу.
        listOf(TvActivityDisplayMode.STREAM_FULL, TvActivityDisplayMode.STREAM_WINDOW).forEach { mode ->
            assertEquals(
                "Марс размышляет…",
                TvActivityDisplayPolicy.status(mode, turnLive = true, lastEvent = thought, fallback = "Марс размышляет…"),
            )
        }
    }

    @Test
    fun `header caption and switch notice expose mode`() {
        assertEquals("Режим: Всё", TvActivityDisplayPolicy.buttonCaption(TvActivityDisplayMode.STREAM_FULL))
        val notice = TvActivityDisplayPolicy.switchNotice(TvActivityDisplayMode.STREAM_QUIET)
        assertEquals("Режим «Тишина» — Ход без деталей, статус краткий", notice)
    }
}
