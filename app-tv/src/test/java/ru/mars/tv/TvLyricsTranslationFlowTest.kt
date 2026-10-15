package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MusicTrack
import java.util.concurrent.Executor

/**
 * Unit-тесты [TvLyricsTranslationPolicy] и [TvLyricsTranslationFlow] — переключателя
 * «Перевод»/«Оригинал» в панели текста песни (надстройка над [TvLyricsPanelState];
 * без Android: гейтвей, executor и ui-dispatcher — fakes, TvLyricsPanelFlowTest parity).
 * Покрыто: подписи во всех состояниях, переключение кэша без повторного запроса, пустой
 * перевод не стирает оригинал, ветка ошибки — явный вид причины (понятную строку берёт
 * Activity из ресурсов), Job-guard на двойное нажатие, generation-guard на reset (смена
 * трека/закрытие панели), изоляция падения гейтвея, destroy-семантика и видимость
 * кнопки по состоянию панели.
 */
class TvLyricsTranslationFlowTest {

    private class QueueExecutor : Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.addLast(command) }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }
    private class QueueUi : TvUiDispatcher {
        val queue = ArrayDeque<Runnable>()
        override fun dispatch(block: () -> Unit) { queue.addLast(block) }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }
    private class FakeTranslationGateway : TvLyricsTranslationGateway {
        val results = ArrayDeque<TvLyricsTranslationResult>()
        var fallback: TvLyricsTranslationResult = TvLyricsTranslationResult.Translated("Warm place")
        var calls = 0
        val requested = mutableListOf<String>()
        var throwOnTranslate: Throwable? = null

        override fun translate(text: String): TvLyricsTranslationResult {
            calls++
            requested += text
            throwOnTranslate?.let { throw it }
            return if (results.isNotEmpty()) results.removeFirst() else fallback
        }
    }

    private fun track(id: String = "t1") = MusicTrack(id = id, title = "Title $id", artist = "Artist $id")

    private class Harness {
        val gateway = FakeTranslationGateway()
        val executor = QueueExecutor()
        val ui = QueueUi()
        val states = mutableListOf<TvLyricsTranslationState>()
        val flow = TvLyricsTranslationFlow(gateway, executor, ui).apply {
            onStateChanged = { states += it }
        }
        fun drain() {
            executor.runAll()
            ui.runAll()
        }
    }

    private val original = "Тёплое место, но улицы ждут отпечатков наших ног."

    // ----------------------------------------------------------------- policy: подписи

    @Test
    fun `labels follow what the tap will do`() {
        assertEquals(TvLyricsTranslationPolicy.Label.TRANSLATE, TvLyricsTranslationPolicy.actionLabel(showingTranslation = false))
        assertEquals(TvLyricsTranslationPolicy.Label.ORIGINAL, TvLyricsTranslationPolicy.actionLabel(showingTranslation = true))
    }

    @Test
    fun `button label covers every toggle state`() {
        assertEquals(
            TvLyricsTranslationPolicy.Label.TRANSLATE,
            TvLyricsTranslationPolicy.buttonLabel(TvLyricsTranslationState.Off),
        )
        assertEquals(
            TvLyricsTranslationPolicy.Label.TRANSLATING,
            TvLyricsTranslationPolicy.buttonLabel(TvLyricsTranslationState.Translating),
        )
        assertEquals(
            TvLyricsTranslationPolicy.Label.ORIGINAL,
            TvLyricsTranslationPolicy.buttonLabel(TvLyricsTranslationState.Ready("Warm place", showing = true)),
        )
        assertEquals(
            TvLyricsTranslationPolicy.Label.TRANSLATE,
            TvLyricsTranslationPolicy.buttonLabel(TvLyricsTranslationState.Ready("Warm place", showing = false)),
        )
        assertEquals(
            TvLyricsTranslationPolicy.Label.TRANSLATE,
            TvLyricsTranslationPolicy.buttonLabel(TvLyricsTranslationState.Failed(TvLyricsTranslationFailure.NETWORK)),
        )
    }

    @Test
    fun `canTranslate only with a real original`() {
        assertTrue(TvLyricsTranslationPolicy.canTranslate(original))
        assertFalse(TvLyricsTranslationPolicy.canTranslate(null))
        assertFalse(TvLyricsTranslationPolicy.canTranslate(""))
        assertFalse(TvLyricsTranslationPolicy.canTranslate("  \n "))
    }

    // ------------------------------------------------------------------- policy: тело

    @Test
    fun `body switches between original and translation`() {
        assertEquals(
            original,
            TvLyricsTranslationPolicy.bodyFor(original, "Warm place", showingTranslation = false),
        )
        assertEquals(
            "Warm place",
            TvLyricsTranslationPolicy.bodyFor(original, "Warm place", showingTranslation = true),
        )
        assertEquals(original, TvLyricsTranslationPolicy.bodyFor(original, null, showingTranslation = true))
    }

    @Test
    fun `empty translation never wipes the original`() {
        // Живой ответ на пустой ввод — голый translated:"" — оригинал обязан остаться.
        assertEquals(original, TvLyricsTranslationPolicy.bodyFor(original, "", showingTranslation = true))
        assertEquals(original, TvLyricsTranslationPolicy.bodyFor(original, "   ", showingTranslation = true))
        assertEquals(
            original,
            TvLyricsTranslationPolicy.bodyFor(original, TvLyricsTranslationState.Ready("  ", showing = true)),
        )
    }

    @Test
    fun `bodyFor by state keeps the original while off translating or failed`() {
        assertEquals(original, TvLyricsTranslationPolicy.bodyFor(original, TvLyricsTranslationState.Off))
        assertEquals(original, TvLyricsTranslationPolicy.bodyFor(original, TvLyricsTranslationState.Translating))
        assertEquals(
            original,
            TvLyricsTranslationPolicy.bodyFor(original, TvLyricsTranslationState.Failed(TvLyricsTranslationFailure.NETWORK)),
        )
        assertEquals(
            "Warm place",
            TvLyricsTranslationPolicy.bodyFor(original, TvLyricsTranslationState.Ready("Warm place", showing = true)),
        )
    }

    // ---------------------------------------------------- policy: видимость и заметки

    @Test
    fun `button visible only for a real Text panel`() {
        val t = track()
        assertTrue(TvLyricsTranslationPolicy.buttonVisible(TvLyricsPanelState.Text(t, original, synced = false, source = null)))
        assertFalse(TvLyricsTranslationPolicy.buttonVisible(TvLyricsPanelState.Text(t, "  ", synced = false, source = null)))
        assertFalse(TvLyricsTranslationPolicy.buttonVisible(TvLyricsPanelState.Missing(t)))
        assertFalse(TvLyricsTranslationPolicy.buttonVisible(TvLyricsPanelState.Error(t, "сбой")))
        assertFalse(TvLyricsTranslationPolicy.buttonVisible(TvLyricsPanelState.Loading(t)))
        assertFalse(TvLyricsTranslationPolicy.buttonVisible(TvLyricsPanelState.Idle))
    }

    @Test
    fun `failure kind selects the resource wording`() {
        assertNull(TvLyricsTranslationPolicy.failureOf(TvLyricsTranslationState.Off))
        assertNull(TvLyricsTranslationPolicy.failureOf(TvLyricsTranslationState.Ready("x", showing = true)))
        assertEquals(
            TvLyricsTranslationFailure.EMPTY_ANSWER,
            TvLyricsTranslationPolicy.failureOf(TvLyricsTranslationState.Failed(TvLyricsTranslationFailure.EMPTY_ANSWER)),
        )
        assertEquals(
            TvLyricsTranslationFailure.NETWORK,
            TvLyricsTranslationPolicy.failureOf(TvLyricsTranslationState.Failed(TvLyricsTranslationFailure.NETWORK)),
        )
    }

    @Test
    fun `meta line joins panel meta and the note`() {
        assertEquals(
            "источник: youtube · Перевод недоступен",
            TvLyricsTranslationPolicy.metaLine("источник: youtube", "Перевод недоступен"),
        )
        assertEquals("источник: youtube", TvLyricsTranslationPolicy.metaLine("источник: youtube", ""))
        assertEquals("Перевод недоступен", TvLyricsTranslationPolicy.metaLine("", "Перевод недоступен"))
        assertEquals("", TvLyricsTranslationPolicy.metaLine("", ""))
    }

    // ------------------------------------------------------------------------ flow

    @Test
    fun `toggle publishes Translating immediately and Ready after the answer lands`() {
        val h = Harness()

        h.flow.toggle(original)
        assertEquals(listOf<TvLyricsTranslationState>(TvLyricsTranslationState.Translating), h.states)

        h.drain()
        assertEquals(2, h.states.size)
        assertEquals(TvLyricsTranslationState.Ready("Warm place", showing = true), h.states.last())
        assertEquals(listOf(original), h.gateway.requested)
    }

    @Test
    fun `second toggle shows the original without a second request`() {
        val h = Harness()
        h.flow.toggle(original)
        h.drain()

        h.flow.toggle(original)
        assertEquals(TvLyricsTranslationState.Ready("Warm place", showing = false), h.flow.state)
        assertEquals(1, h.gateway.calls) // перевод закэширован — сеть не дёргается (паритет с mobile)

        h.flow.toggle(original)
        assertEquals(TvLyricsTranslationState.Ready("Warm place", showing = true), h.flow.state)
        assertEquals(1, h.gateway.calls)
    }

    @Test
    fun `empty translated answer lands as EMPTY_ANSWER and the original stays`() {
        val h = Harness()
        h.gateway.fallback = TvLyricsTranslationResult.Translated("")

        h.flow.toggle(original)
        h.drain()

        assertEquals(
            TvLyricsTranslationState.Failed(TvLyricsTranslationFailure.EMPTY_ANSWER),
            h.flow.state,
        )
        assertEquals(original, TvLyricsTranslationPolicy.bodyFor(original, h.flow.state))
    }

    @Test
    fun `gateway failure lands as NETWORK without leaking the raw message`() {
        val h = Harness()
        h.gateway.results.addLast(TvLyricsTranslationResult.Failed("HTTP 503 upstream down"))

        h.flow.toggle(original)
        h.drain()

        // Сырое серверное сообщение в состояние не попадает — только вид для строки из ресурсов.
        assertEquals(TvLyricsTranslationState.Failed(TvLyricsTranslationFailure.NETWORK), h.flow.state)
        assertEquals(original, TvLyricsTranslationPolicy.bodyFor(original, h.flow.state))
    }

    @Test
    fun `gateway crash is contained as NETWORK`() {
        val h = Harness()
        h.gateway.throwOnTranslate = RuntimeException("socket closed")

        h.flow.toggle(original)
        h.drain()

        assertEquals(TvLyricsTranslationState.Failed(TvLyricsTranslationFailure.NETWORK), h.flow.state)
    }

    @Test
    fun `double press while translating does not duplicate the request`() {
        val h = Harness()
        h.flow.toggle(original)
        h.flow.toggle(original) // Job-guard: запрос в пути — второе нажатие глушится.
        assertEquals(listOf<TvLyricsTranslationState>(TvLyricsTranslationState.Translating), h.states)

        h.drain()
        assertEquals(1, h.gateway.calls)
        assertEquals(TvLyricsTranslationState.Ready("Warm place", showing = true), h.flow.state)
    }

    @Test
    fun `reset while translating drops the late answer`() {
        val h = Harness()
        h.flow.toggle(original)
        h.flow.reset() // смена трека/закрытие панели
        assertEquals(listOf<TvLyricsTranslationState>(TvLyricsTranslationState.Translating, TvLyricsTranslationState.Off), h.states)

        h.drain() // ответ устаревшего поколения в UI не попадает
        assertEquals(2, h.states.size)
        assertEquals(TvLyricsTranslationState.Off, h.flow.state)
    }

    @Test
    fun `reset from Off is silent but still invalidates`() {
        val h = Harness()
        h.flow.reset()
        assertTrue(h.states.isEmpty())

        // Поколение сброшено: следующий toggle работает штатно.
        h.flow.toggle(original)
        h.drain()
        assertEquals(TvLyricsTranslationState.Ready("Warm place", showing = true), h.flow.state)
    }

    @Test
    fun `toggle after failure retries the request`() {
        val h = Harness()
        h.gateway.results.addLast(TvLyricsTranslationResult.Failed("timeout"))
        h.flow.toggle(original)
        h.drain()
        assertEquals(TvLyricsTranslationState.Failed(TvLyricsTranslationFailure.NETWORK), h.flow.state)

        h.gateway.results.addLast(TvLyricsTranslationResult.Translated("Warm place"))
        h.flow.toggle(original)
        h.drain()
        assertEquals(TvLyricsTranslationState.Ready("Warm place", showing = true), h.flow.state)
        assertEquals(2, h.gateway.calls)
    }

    @Test
    fun `toggle without a real original starts nothing`() {
        val h = Harness()
        h.flow.toggle(null)
        h.flow.toggle("   ")
        assertTrue(h.states.isEmpty())
        assertEquals(0, h.gateway.calls)
        assertEquals(TvLyricsTranslationState.Off, h.flow.state)
    }

    @Test
    fun `destroy silences publication and drops answers in flight`() {
        val h = Harness()
        h.flow.toggle(original)
        h.flow.destroy()

        h.drain() // ответ не публикуется: окно умерло
        assertEquals(listOf<TvLyricsTranslationState>(TvLyricsTranslationState.Translating), h.states)

        h.flow.toggle(original) // новые команды игнорируются
        h.flow.reset()
        assertEquals(1, h.states.size)
    }
}
