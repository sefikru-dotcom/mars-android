package ru.mars.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Гейт перерисовки smart-чипов: лента перерисовывается только когда реально меняются
 * её входы, а не на каждом кадре стрима.
 */
class QuickChipsRenderGateTest {

    private fun sig(
        status: ChatStatus = ChatStatus.RECEIVED,
        hasAssistantReply: Boolean = true,
        lastTurnFailed: Boolean = false,
    ) = QuickChipsRenderGate.Signature(status, hasAssistantReply, lastTurnFailed)

    @Test
    fun `первая подпись всегда требует отрисовки`() {
        assertTrue(QuickChipsRenderGate().shouldRender(sig()))
    }

    @Test
    fun `повторная подпись не требует отрисовки`() {
        val gate = QuickChipsRenderGate()
        gate.shouldRender(sig())

        assertFalse(gate.shouldRender(sig()))
    }

    @Test
    fun `смена статуса хода перерисовывает ленту`() {
        val gate = QuickChipsRenderGate()
        gate.shouldRender(sig(status = ChatStatus.TYPING))

        assertTrue(gate.shouldRender(sig(status = ChatStatus.RECEIVED)))
    }

    @Test
    fun `появление ответа ассистента перерисовывает ленту`() {
        val gate = QuickChipsRenderGate()
        gate.shouldRender(sig(hasAssistantReply = false))

        assertTrue(gate.shouldRender(sig(hasAssistantReply = true)))
    }

    @Test
    fun `падение хода перерисовывает ленту`() {
        val gate = QuickChipsRenderGate()
        gate.shouldRender(sig(lastTurnFailed = false))

        assertTrue(gate.shouldRender(sig(lastTurnFailed = true)))
    }

    @Test
    fun `кадры стрима с неизменными входами не перерисовывают ленту`() {
        val gate = QuickChipsRenderGate()
        gate.shouldRender(sig(status = ChatStatus.TYPING, hasAssistantReply = true))

        repeat(20) {
            assertFalse("кадр $it", gate.shouldRender(sig(status = ChatStatus.TYPING, hasAssistantReply = true)))
        }
    }

    @Test
    fun `после invalidate та же подпись снова требует отрисовки`() {
        val gate = QuickChipsRenderGate()
        gate.shouldRender(sig())
        gate.invalidate()

        assertTrue(gate.shouldRender(sig()))
    }
}
