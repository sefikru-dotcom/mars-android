package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ProviderQuota
import ru.mars.network.model.ProviderQuotaAmounts
import ru.mars.network.repository.ProviderQuotaOutcome
import ru.mars.network.repository.SessionToolsetsOutcome

/**
 * A9: чип набора инструментов и полезная нагрузка сохранения.
 * Тесты чистые: презентер и политика не тянут Android, сеть подменяется
 * outcome-типами репозитория.
 */
class SessionToolsetsPresenterTest {

    // --- payloadFor: пустой выбор — это СБРОС (null), а не пустой список ---

    @Test
    fun `пустой выбор трактуется как сброс к набору по умолчанию`() {
        assertNull(SessionToolsetsPolicy.payloadFor(emptySet()))
        assertNull("пробелы не считаются выбором", SessionToolsetsPolicy.payloadFor(setOf("  ")))
    }

    @Test
    fun `неизвестные имена отбрасываются, а не отправляются на сервер`() {
        val payload = SessionToolsetsPolicy.payloadFor(listOf("browser", "нет-такого", "terminal"))
        assertEquals(listOf("browser", "terminal"), payload)
    }

    @Test
    fun `порядок полезной нагрузки — по справочнику, а не по порядку отметки`() {
        // Отмечено в обратном порядке: сервер должен получить стабильный список.
        val payload = SessionToolsetsPolicy.payloadFor(listOf("terminal", "browser"))
        assertEquals(listOf("browser", "terminal"), payload)
    }

    @Test
    fun `выбор после нормализации не содержит дублей`() {
        val payload = SessionToolsetsPolicy.payloadFor(listOf("browser", "browser", " browser "))
        assertEquals(listOf("browser"), payload)
    }

    // --- chipLabel: null и пустой список означают ОДНО И ТО ЖЕ — «все» ---

    @Test
    fun `null и пустой набор одинаково показывают набор по умолчанию`() {
        assertEquals("Инструменты: все", SessionToolsetsPolicy.chipLabel(null))
        assertEquals("Инструменты: все", SessionToolsetsPolicy.chipLabel(emptyList()))
    }

    @Test
    fun `до двух наборов перечисляются именами, дальше — счётчиком`() {
        assertEquals("Инструменты: Браузер", SessionToolsetsPolicy.chipLabel(listOf("browser")))
        assertEquals("Инструменты: Браузер, Терминал", SessionToolsetsPolicy.chipLabel(listOf("browser", "terminal")))
        assertEquals(
            "Инструменты: 3 выбрано",
            SessionToolsetsPolicy.chipLabel(listOf("browser", "terminal", "web")),
        )
    }

    @Test
    fun `неизвестное имя отсеивается нормализацией, остаётся набор по умолчанию`() {
        // Реальный контракт: normalize() выбрасывает имена вне справочника,
        // поэтому «загадка» не доходит ни до чипа, ни до сервера.
        assertEquals("Инструменты: все", SessionToolsetsPolicy.chipLabel(listOf("загадка")))
        assertNull("неизвестное имя не отправляется как выбор", SessionToolsetsPolicy.payloadFor(listOf("загадка")))
        assertEquals(
            "известное имя сохраняется, неизвестное отбрасывается",
            listOf("browser"),
            SessionToolsetsPolicy.payloadFor(listOf("browser", "загадка")),
        )
    }

    // --- презентер: серверное значение — источник истины ---

    @Test
    fun `bind подхватывает серверное значение без вспышки статуса`() {
        val p = SessionToolsetsPresenter()
        p.onSaved(listOf("browser"))
        p.bind(listOf("terminal"))

        assertEquals(listOf("terminal"), p.enabledToolsets)
        assertEquals(ToolsetSaveState.IDLE, p.saveState)
        assertNull(p.errorMessage)
    }

    @Test
    fun `провал сохранения НЕ меняет значение чипа`() {
        val p = SessionToolsetsPresenter()
        p.bind(listOf("browser"))
        p.onSaving()
        p.onFailed("сервер недоступен")

        assertEquals("прежнее подтверждённое значение", listOf("browser"), p.enabledToolsets)
        assertEquals(ToolsetSaveState.ERROR, p.saveState)
        assertEquals("сервер недоступен", p.errorMessage)
    }

    @Test
    fun `пустое сообщение об ошибке заменяется понятным текстом`() {
        val p = SessionToolsetsPresenter()
        p.onFailed("   ")
        assertEquals("Не удалось сохранить набор инструментов", p.errorMessage)
    }

    @Test
    fun `успех сбрасывает прежнюю ошибку`() {
        val p = SessionToolsetsPresenter()
        p.onFailed("сбой")
        p.apply(SessionToolsetsOutcome.Success(listOf("web")))

        assertEquals(listOf("web"), p.enabledToolsets)
        assertEquals(ToolsetSaveState.SAVED, p.saveState)
        assertNull(p.errorMessage)
    }

    @Test
    fun `apply(Failure) выставляет ошибку и сохраняет прежний набор`() {
        val p = SessionToolsetsPresenter()
        p.bind(listOf("browser"))
        p.apply(SessionToolsetsOutcome.Failure("timeout"))

        assertEquals(listOf("browser"), p.enabledToolsets)
        assertEquals(ToolsetSaveState.ERROR, p.saveState)
        assertEquals("timeout", p.errorMessage)
    }

    @Test
    fun `справочник не содержит дублей имён и все записи заполнены`() {
        val names = SessionToolsetsPolicy.ALL.map { it.name }
        assertEquals("дубли имён недопустимы", names.size, names.toSet().size)
        assertTrue(
            "у каждой опции должны быть заголовок и описание",
            SessionToolsetsPolicy.ALL.all { it.title.isNotBlank() && it.description.isNotBlank() },
        )
    }
}

/**
 * A10: состояния чипа квоты. Ключевой контракт — живой ответ нашего провайдера
 * приходит с `supported=false`/`status=unsupported` и HTTP 200; UI обязан честно
 * сказать «квоты нет», а не уйти в ошибку и не нарисовать нули.
 */
class ProviderQuotaPresenterTest {

    private fun unsupported() = ProviderQuota(
        ok = false,
        provider = "plusvibe",
        displayName = "PlusVibe",
        supported = false,
        status = "unsupported",
        message = "Провайдер PlusVibe не отдаёт данные о квоте",
    )

    @Test
    fun `на старте чип скрыт`() {
        val m = ProviderQuotaPresenter().model()
        assertEquals(QuotaChipState.HIDDEN, m.state)
        assertFalse(m.visible)
    }

    @Test
    fun `supported=false даёт UNSUPPORTED, а НЕ ERROR — несмотря на ok=false`() {
        val p = ProviderQuotaPresenter()
        p.applyQuota(unsupported())

        assertEquals(QuotaChipState.UNSUPPORTED, p.state)
        assertTrue("чип должен быть видим с объяснением", p.model().visible)
    }

    @Test
    fun `неподдерживаемый провайдер не предлагает повтор — это не сбой`() {
        val p = ProviderQuotaPresenter()
        p.applyQuota(unsupported())
        assertFalse("повтор бессмыслен: состояние окончательное", p.model().canRefresh)
    }

    @Test
    fun `пояснение для неподдерживаемого берётся из сообщения сервера`() {
        val detail = ProviderQuotaPresenter.unsupportedDetail(unsupported())
        assertEquals("Провайдер PlusVibe не отдаёт данные о квоте", detail)
    }

    @Test
    fun `без сообщения сервера пояснение называет провайдера само`() {
        val bare = unsupported().copy(message = null)
        assertEquals("Провайдер PlusVibe не отдаёт данные о квоте", ProviderQuotaPresenter.unsupportedDetail(bare))
    }

    @Test
    fun `без имени провайдера остаётся честная общая фраза`() {
        val bare = unsupported().copy(message = null, displayName = null, provider = null)
        assertEquals("Провайдер не отдаёт данные о квоте", ProviderQuotaPresenter.unsupportedDetail(bare))
    }

    @Test
    fun `supported=true без чисел — UNAVAILABLE, а не нули`() {
        val p = ProviderQuotaPresenter()
        p.applyQuota(ProviderQuota(ok = true, supported = true, quota = null))

        assertEquals(QuotaChipState.UNAVAILABLE, p.state)
        val m = p.model()
        assertTrue(m.visible)
        assertEquals(ProviderQuotaPresenter.UNAVAILABLE_LABEL, m.label)
    }

    @Test
    fun `есть числа — состояние OK с процентом остатка`() {
        val p = ProviderQuotaPresenter()
        p.applyQuota(
            ProviderQuota(
                ok = true,
                supported = true,
                label = "Квота",
                quota = ProviderQuotaAmounts(limitRemaining = 25.0, limit = 100.0),
            ),
        )

        assertEquals(QuotaChipState.OK, p.state)
        val m = p.model()
        assertTrue(m.visible)
        assertTrue("в подписи должны быть числа и процент", m.label.contains("25") && m.label.contains("25%"))
        assertTrue(m.canRefresh)
        assertFalse("подпись непустая для TalkBack", m.contentDescription.isBlank())
    }

    @Test
    fun `транспортный сбой даёт ERROR с кнопкой повтора`() {
        val p = ProviderQuotaPresenter()
        p.apply(ProviderQuotaOutcome.Failure("нет сети"))

        assertEquals(QuotaChipState.ERROR, p.state)
        val m = p.model()
        assertTrue(m.visible)
        assertTrue("сбой стоит повторить", m.canRefresh)
        assertEquals("нет сети", p.errorMessage)
    }

    @Test
    fun `пустое сообщение сбоя заменяется понятным текстом`() {
        val p = ProviderQuotaPresenter()
        p.onTransportError("  ")
        assertEquals("Не удалось получить квоту", p.errorMessage)
    }

    @Test
    fun `смена сессии сбрасывает прежние числа`() {
        val p = ProviderQuotaPresenter()
        p.applyQuota(ProviderQuota(ok = true, supported = true, quota = ProviderQuotaAmounts(limitRemaining = 5.0, limit = 10.0)))
        p.reset()

        assertEquals(QuotaChipState.HIDDEN, p.state)
        assertNull(p.quota)
        assertFalse(p.model().visible)
    }

    @Test
    fun `loading очищает прежнюю ошибку`() {
        val p = ProviderQuotaPresenter()
        p.onTransportError("сбой")
        p.onLoading()
        assertEquals(QuotaChipState.LOADING, p.state)
        assertNull(p.errorMessage)
    }

    @Test
    fun `процент не считается, когда лимит нулевой или отсутствует`() {
        assertNull(ProviderQuotaPresenter.percentRemaining(10.0, 0.0))
        assertNull(ProviderQuotaPresenter.percentRemaining(10.0, null))
        assertNull(ProviderQuotaPresenter.percentRemaining(null, 100.0))
    }

    @Test
    fun `процент зажимается в диапазон 0-100`() {
        assertEquals(100, ProviderQuotaPresenter.percentRemaining(150.0, 100.0))
        assertEquals(0, ProviderQuotaPresenter.percentRemaining(-5.0, 100.0))
    }

    @Test
    fun `целые числа печатаются без хвоста, дробные — с одним знаком`() {
        assertEquals("25", ProviderQuotaPresenter.formatAmount(25.0))
        assertEquals("25.5", ProviderQuotaPresenter.formatAmount(25.48))
    }
}
