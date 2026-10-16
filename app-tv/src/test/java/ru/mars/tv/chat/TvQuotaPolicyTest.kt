package ru.mars.tv.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ProviderQuota
import ru.mars.network.model.ProviderQuotaAmounts
import ru.mars.network.repository.ProviderQuotaOutcome

/**
 * Юнит-тесты [TvQuotaPolicy] — чистый JVM, без Android-фреймворка. Покрывают:
 * живой кейс «провайдер не поддерживает квоту» (200 + supported=false), успех с
 * числами и порогами тона, отказ транспорта, отсутствие данных и жизненный цикл
 * (загрузка/сброс). Тексты здесь не проверяются — проза живёт в ресурсах, политика
 * отдаёт только решения.
 */
class TvQuotaPolicyTest {

    private fun policy() = TvQuotaPolicy()

    private fun success(quota: ProviderQuota) = policy().apply {
        apply(ProviderQuotaOutcome.Success(quota))
    }

    /** Живой ответ `custom:plusvibe-api`: 200, но квоты у провайдера нет вовсе. */
    private fun unsupportedPayload() = ProviderQuota(
        ok = false,
        provider = "plusvibe-api",
        displayName = "PlusVibe",
        supported = false,
        status = "unsupported",
        label = null,
        quota = null,
        message = "Провайдер PlusVibe не отдаёт данные о квоте",
    )

    private fun availablePayload(remaining: Double?, limit: Double?): ProviderQuota = ProviderQuota(
        ok = true,
        provider = "openrouter",
        displayName = "OpenRouter",
        supported = true,
        status = "available",
        label = "Квота OpenRouter",
        quota = ProviderQuotaAmounts(limitRemaining = remaining, usage = null, limit = limit),
    )

    // --- отсутствие данных --------------------------------------------------------------------

    @Test
    fun `fresh policy is hidden and silent`() {
        val model = policy().model()
        assertEquals(TvQuotaState.HIDDEN, model.state)
        assertFalse(model.visible)
        assertEquals(TvQuotaTone.NEUTRAL, model.tone)
        assertFalse(model.canRefresh)
        assertEquals(TvQuotaAmountsShape.NONE, model.amountsShape)
        assertNull(model.providerName)
        assertNull(model.note)
        assertNull(model.percentRemaining)
    }

    @Test
    fun `loading state is visible without a retry`() {
        val p = policy()
        p.onLoading()
        val model = p.model()
        assertEquals(TvQuotaState.LOADING, model.state)
        assertTrue(model.visible)
        assertFalse(model.canRefresh)
        assertEquals(TvQuotaTone.NEUTRAL, model.tone)
    }

    @Test
    fun `reset drops data back to hidden`() {
        val p = success(availablePayload(80.0, 100.0))
        assertEquals(TvQuotaState.OK, p.model().state)
        p.reset()
        val model = p.model()
        assertEquals(TvQuotaState.HIDDEN, model.state)
        assertFalse(model.visible)
        assertNull(p.quota)
        assertNull(p.errorMessage)
    }

    // --- живой кейс: провайдер без квоты ------------------------------------------------------

    @Test
    fun `unsupported provider is stated, not treated as an error`() {
        val p = policy()
        p.apply(ProviderQuotaOutcome.Success(unsupportedPayload()))
        val model = p.model()
        assertEquals(TvQuotaState.UNSUPPORTED, model.state)
        assertTrue("провайдер без квоты обязан быть виден словами", model.visible)
        assertFalse("повторять нечего: состояние окончательное", model.canRefresh)
        assertEquals(TvQuotaTone.NEUTRAL, model.tone)
        assertEquals("Провайдер PlusVibe не отдаёт данные о квоте", model.note)
        assertEquals("PlusVibe", model.providerName)
        assertEquals(TvQuotaAmountsShape.NONE, model.amountsShape)
    }

    @Test
    fun `unsupported wins over the naive ok check`() {
        // ok=false у живого ответа: наивная проверка `ok` увела бы состояние в ERROR.
        val p = policy()
        p.applyQuota(ProviderQuota(ok = false, supported = false, status = "unsupported"))
        assertEquals(TvQuotaState.UNSUPPORTED, p.model().state)
    }

    @Test
    fun `status unsupported with supported flag true is still unsupported`() {
        val p = policy()
        p.applyQuota(ProviderQuota(ok = false, supported = true, status = "unsupported"))
        assertEquals(TvQuotaState.UNSUPPORTED, p.model().state)
    }

    @Test
    fun `provider name falls back to the technical provider id`() {
        val p = policy()
        p.applyQuota(ProviderQuota(ok = false, provider = "plusvibe-api", supported = false))
        assertEquals("plusvibe-api", p.model().providerName)
    }

    @Test
    fun `blank provider fields give no invented name`() {
        val p = policy()
        p.applyQuota(ProviderQuota(ok = false, provider = "  ", displayName = "", supported = false))
        assertNull(p.model().providerName)
    }

    // --- поддержано, но числа не прочитаны ----------------------------------------------------

    @Test
    fun `retryable statuses are unavailable with a retry`() {
        listOf("no_key", "invalid_key", "unavailable").forEach { status ->
            val p = policy()
            p.applyQuota(
                ProviderQuota(
                    ok = false, provider = "openrouter", supported = true, status = status,
                    message = "Ключ провайдера не принят",
                ),
            )
            val model = p.model()
            assertEquals("status=$status", TvQuotaState.UNAVAILABLE, model.state)
            assertTrue(model.visible)
            assertTrue("$status retryable", model.canRefresh)
            assertEquals(TvQuotaTone.WARNING, model.tone)
            assertEquals("Ключ провайдера не принят", model.note)
        }
    }

    @Test
    fun `supported ok without a quota block is unavailable, not a fake zero`() {
        val p = policy()
        p.applyQuota(ProviderQuota(ok = true, provider = "openrouter", supported = true, status = "available"))
        val model = p.model()
        assertEquals(TvQuotaState.UNAVAILABLE, model.state)
        assertTrue(model.canRefresh)
        assertEquals(TvQuotaAmountsShape.NONE, model.amountsShape)
        assertNull(model.remaining)
        assertNull(model.percentRemaining)
    }

    @Test
    fun `state and model never disagree`() {
        val cases = listOf(
            availablePayload(90.0, 100.0),
            unsupportedPayload(),
            ProviderQuota(ok = true, supported = true, status = "available"),
        )
        cases.forEach { payload ->
            val p = success(payload)
            assertEquals(p.state, p.model().state)
        }
    }

    // --- успех и пороги тона ------------------------------------------------------------------

    @Test
    fun `success carries numbers, provider name and full shape`() {
        val model = success(availablePayload(remaining = 74.0, limit = 100.0)).model()
        assertEquals(TvQuotaState.OK, model.state)
        assertTrue(model.visible)
        assertTrue(model.canRefresh)
        assertEquals(TvQuotaAmountsShape.REMAINING_OF_LIMIT, model.amountsShape)
        assertEquals(74.0, model.remaining!!, 0.0)
        assertEquals(100.0, model.limit!!, 0.0)
        assertEquals(74, model.percentRemaining)
        assertEquals("OpenRouter", model.providerName)
    }

    @Test
    fun `tone follows the remaining share thresholds`() {
        assertEquals(TvQuotaTone.ONLINE, success(availablePayload(100.0, 100.0)).model().tone)
        assertEquals(TvQuotaTone.ONLINE, success(availablePayload(75.0, 100.0)).model().tone)
        assertEquals(TvQuotaTone.WARNING, success(availablePayload(74.0, 100.0)).model().tone)
        assertEquals(TvQuotaTone.WARNING, success(availablePayload(50.0, 100.0)).model().tone)
        assertEquals(TvQuotaTone.ALERT, success(availablePayload(49.0, 100.0)).model().tone)
        assertEquals(TvQuotaTone.ALERT, success(availablePayload(0.0, 100.0)).model().tone)
    }

    @Test
    fun `healthy share stays online well above the warning line`() {
        val model = success(availablePayload(999.0, 1000.0)).model()
        assertEquals(100, model.percentRemaining)
        assertEquals(TvQuotaTone.ONLINE, model.tone)
    }

    @Test
    fun `unknown share is neutral, not an invented alarm`() {
        val model = success(availablePayload(remaining = 5.0, limit = null)).model()
        assertEquals(TvQuotaState.OK, model.state)
        assertEquals(TvQuotaAmountsShape.REMAINING_ONLY, model.amountsShape)
        assertNull(model.percentRemaining)
        assertEquals(TvQuotaTone.NEUTRAL, model.tone)
    }

    @Test
    fun `percent only answers neutral tone too`() {
        assertEquals(TvQuotaTone.NEUTRAL, TvQuotaPolicy.toneFor(null))
        val model = success(availablePayload(remaining = null, limit = 100.0)).model()
        assertEquals(TvQuotaAmountsShape.NONE, model.amountsShape)
        assertEquals(TvQuotaTone.NEUTRAL, model.tone)
    }

    @Test
    fun `empty quota block keeps the ok state with no numbers`() {
        val p = policy()
        p.applyQuota(availablePayload(remaining = null, limit = null))
        val model = p.model()
        assertEquals(TvQuotaState.OK, model.state)
        assertEquals(TvQuotaAmountsShape.NONE, model.amountsShape)
        assertEquals(TvQuotaTone.NEUTRAL, model.tone)
    }

    // --- отказ транспорта ---------------------------------------------------------------------

    @Test
    fun `transport failure is an error with a retry and keeps the reason`() {
        val p = policy()
        p.apply(ProviderQuotaOutcome.Failure("HTTP 500"))
        val model = p.model()
        assertEquals(TvQuotaState.ERROR, model.state)
        assertTrue("сбой не прячет чип: иначе непонятно, что происходит", model.visible)
        assertTrue("повтор по нажатию доступен", model.canRefresh)
        assertEquals(TvQuotaTone.ALERT, model.tone)
        assertEquals("HTTP 500", model.note)
        assertNull(p.quota)
    }

    @Test
    fun `blank failure message leaves no prose in the policy`() {
        val p = policy()
        p.apply(ProviderQuotaOutcome.Failure("   "))
        val model = p.model()
        assertEquals(TvQuotaState.ERROR, model.state)
        assertNull(model.note)
        assertNull(p.errorMessage)
    }

    @Test
    fun `transport error after a success drops the stale numbers`() {
        val p = success(availablePayload(80.0, 100.0))
        assertEquals(80, p.model().percentRemaining)
        p.apply(ProviderQuotaOutcome.Failure("Network error"))
        val model = p.model()
        assertEquals(TvQuotaState.ERROR, model.state)
        assertNull("старые числа не должны переехать в ошибку", model.percentRemaining)
        assertNull(model.remaining)
    }

    @Test
    fun `error payload without support goes to error state`() {
        val p = policy()
        p.applyQuota(
            ProviderQuota(ok = false, provider = "openrouter", supported = true, status = "boom", message = "boom"),
        )
        val model = p.model()
        assertEquals(TvQuotaState.ERROR, model.state)
        assertEquals("boom", model.note)
        assertTrue(model.canRefresh)
    }

    @Test
    fun `loading clears a previous error note`() {
        val p = policy()
        p.apply(ProviderQuotaOutcome.Failure("HTTP 503"))
        p.onLoading()
        assertEquals(TvQuotaState.LOADING, p.model().state)
        assertNull(p.errorMessage)
        assertNull(p.model().note)
    }

    // --- чистые вычисления --------------------------------------------------------------------

    @Test
    fun `percent remaining needs a positive limit`() {
        assertNull(TvQuotaPolicy.percentRemaining(null, 100.0))
        assertNull(TvQuotaPolicy.percentRemaining(10.0, null))
        assertNull(TvQuotaPolicy.percentRemaining(10.0, 0.0))
        assertNull(TvQuotaPolicy.percentRemaining(10.0, -5.0))
        assertEquals(25, TvQuotaPolicy.percentRemaining(25.0, 100.0))
        assertEquals(50, TvQuotaPolicy.percentRemaining(1.0, 2.0))
    }

    @Test
    fun `percent remaining is clamped to the printable range`() {
        assertEquals(100, TvQuotaPolicy.percentRemaining(150.0, 100.0))
        assertEquals(0, TvQuotaPolicy.percentRemaining(-3.0, 100.0))
    }

    @Test
    fun `amount formatting keeps integers clean and decimals short`() {
        assertEquals("12", TvQuotaPolicy.formatAmount(12.0))
        assertEquals("0", TvQuotaPolicy.formatAmount(0.0))
        assertEquals("12.3", TvQuotaPolicy.formatAmount(12.34))
        assertEquals("-4", TvQuotaPolicy.formatAmount(-4.0))
    }

    @Test
    fun `thresholds mirror the context pill breakpoints`() {
        assertEquals(50, TvQuotaPolicy.ALERT_BELOW_PERCENT)
        assertEquals(75, TvQuotaPolicy.WARN_BELOW_PERCENT)
        assertEquals(TvQuotaTone.ONLINE, TvQuotaPolicy.toneFor(75))
        assertEquals(TvQuotaTone.WARNING, TvQuotaPolicy.toneFor(74))
        assertEquals(TvQuotaTone.WARNING, TvQuotaPolicy.toneFor(50))
        assertEquals(TvQuotaTone.ALERT, TvQuotaPolicy.toneFor(49))
    }

    @Test
    fun `amount shape follows the mobile label branches`() {
        assertEquals(
            TvQuotaAmountsShape.REMAINING_OF_LIMIT,
            TvQuotaPolicy.amountsShape(10.0, 20.0, 50),
        )
        assertEquals(TvQuotaAmountsShape.REMAINING_ONLY, TvQuotaPolicy.amountsShape(10.0, null, null))
        assertEquals(TvQuotaAmountsShape.PERCENT_ONLY, TvQuotaPolicy.amountsShape(null, 20.0, 50))
        assertEquals(TvQuotaAmountsShape.NONE, TvQuotaPolicy.amountsShape(null, null, null))
    }
}
