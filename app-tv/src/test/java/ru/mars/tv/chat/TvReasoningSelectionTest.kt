package ru.mars.tv.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.mars.network.model.ReasoningStatus

/**
 * Выбор уровня размышлений на ТВ (чип «Мысли»): разбор ответа `GET /api/reasoning`
 * в строки пикера и подпись чипа. Лестница берётся только из `supported_efforts` —
 * клиент уровни не выдумывает. Паритет мобильного `ReasoningEffortSelectionTest`.
 */
class TvReasoningSelectionTest {

    private class FakeStorage(initial: TvReasoningChoice? = null) : TvReasoningChoiceStorage {
        var saved: TvReasoningChoice? = initial
        var cleared = false
        override fun load(): TvReasoningChoice? = saved
        override fun save(choice: TvReasoningChoice) {
            saved = choice
            cleared = false
        }
        override fun clear() {
            saved = null
            cleared = true
        }
    }

    private fun status(vararg efforts: String, effort: String? = null) = ReasoningStatus(
        reasoningEffort = effort,
        supportedEfforts = efforts.toList(),
        supportsReasoningEffort = efforts.isNotEmpty(),
    )

    // --- разбор ответа GET /api/reasoning -------------------------------------------------------

    @Test
    fun `supported efforts become the ladder, auto goes first`() {
        val selection = TvReasoningSelection(FakeStorage())
        selection.applyStatus(status("Low", " high ", "Low")) // регистр/пробелы/дубли — серверные как есть

        val options = selection.options()

        assertEquals(listOf(null, "low", "high"), options.map { it.effort })
        assertEquals(listOf("Авто", "Low", "High"), options.map { it.label })
    }

    @Test
    fun `empty ladder means no options - picker stays closed, chip stays hidden`() {
        val selection = TvReasoningSelection(FakeStorage())

        selection.applyStatus(status()) // supports_thinking_toggle-модель: лестницы нет

        assertEquals(emptyList<TvReasoningOption>(), selection.options())
        assertNull(selection.buttonLabel())
    }

    @Test
    fun `unknown state hides the chip instead of guessing`() {
        val selection = TvReasoningSelection(FakeStorage())

        assertNull(selection.buttonLabel()) // до первой загрузки
        selection.applyStatus(status("low", "high"))
        assertEquals("Авто", selection.buttonLabel())
        selection.applyStatus(null) // сбой GET: состояние неизвестно
        assertNull(selection.buttonLabel())
    }

    @Test
    fun `effective effort comes from the coerced server value`() {
        val selection = TvReasoningSelection(FakeStorage())

        selection.applyStatus(status("low", "high"))
        assertEquals("Авто", selection.label(selection.effectiveEffort()))

        selection.applyStatus(status("low", "high", effort = " High "))
        assertEquals("high", selection.effectiveEffort())
        assertEquals("High", selection.buttonLabel())
    }

    // --- явный выбор: persist только после подтверждения сервера --------------------------------

    @Test
    fun `select persists the normalized choice for the next turns`() {
        val storage = FakeStorage()
        val selection = TvReasoningSelection(storage)
        selection.applyStatus(status("low", "high"))

        val choice = selection.select(" High ")

        assertEquals(TvReasoningChoice("high", explicitPick = true), choice)
        assertEquals(choice, storage.saved)
        // Пока серверный статус не обновлён, эффективный уровень — серверный (default = Авто):
        // локальный выбор не подставляется вместо правды сервера.
        assertNull(selection.effectiveEffort())
        // После подтверждения (POST /api/reasoning вернул новый статус) чип показывает его.
        selection.applyStatus(status("low", "high", effort = "high"))
        assertEquals("high", selection.effectiveEffort())
    }

    @Test
    fun `select of null stores the auto choice and clears nothing`() {
        val storage = FakeStorage(TvReasoningChoice("high", explicitPick = true))
        val selection = TvReasoningSelection(storage)

        selection.select(null) // «Авто»: POST уйдёт с effort:'' и снимет override

        assertEquals(TvReasoningChoice(null, explicitPick = true), storage.saved)
        assertNull(selection.effectiveEffort())
        assertEquals("Авто", selection.buttonLabel())
    }

    @Test
    fun `restored local pick drives the label while the status is unknown`() {
        val selection = TvReasoningSelection(FakeStorage(TvReasoningChoice("high", explicitPick = true)))

        assertEquals("high", selection.effectiveEffort())
        assertEquals("High", selection.buttonLabel()) // выбор есть -> чип виден и до загрузки

        selection.applyStatus(status("low", "medium", effort = "low")) // серверный источник побеждает
        assertEquals("Low", selection.buttonLabel())
    }

    @Test
    fun `labels capitalize server values and map blanks to auto`() {
        val selection = TvReasoningSelection(FakeStorage())

        assertEquals("Авто", selection.label(null))
        assertEquals("Авто", selection.label(""))
        assertEquals("Авто", selection.label("   "))
        assertEquals("Minimal", selection.label("minimal"))
        assertEquals("Xhigh", selection.label(" XHIGH "))
    }
}
