package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Вид голосового диалога: красная кнопка весь активный диалог, состояние словом и иконкой.
 *
 * Проверяется именно то, что пользователь просил и что легко сломать рефакторингом:
 * активность кнопки во всех трёх фазах, отсутствие «красной всегда», разные иконки на
 * фазу (состояние не читается только по цвету), неизменный размер кнопки и короткие
 * подписи для статусной строки.
 */
class ChatVoiceDialogPresentationTest {

    private val policy = VoiceDialogPresentationPolicy()
    private val icons = VoiceDialogIcons(mic = 1, thinking = 2, speaking = 3)

    private fun ui(loopState: VoiceDialogLoop.State?, speaking: Boolean = false) =
        policy.uiFor(policy.phaseFor(loopState, speaking), icons)

    @Test
    fun `dialog off when loop is not running`() {
        val off = ui(null)
        assertEquals(VoiceDialogPhase.OFF, off.phase)
        assertFalse(off.active)
        // Выключенный режим не показывает «Слушаю» — иначе человек ждёт ответа в пустоту.
        assertEquals("", off.label)
    }

    @Test
    fun `off stays off even if a leftover answer is still playing`() {
        // Звук доигрывает, а диалог уже выключен: красной кнопки быть не должно, режим выключен.
        assertEquals(VoiceDialogPhase.OFF, policy.phaseFor(null, speaking = true))
    }

    @Test
    fun `button is red for the whole active dialog`() {
        val phases = listOf(
            VoiceDialogLoop.State.LISTENING,
            VoiceDialogLoop.State.THINKING,
            VoiceDialogLoop.State.SPEAKING,
        )
        phases.forEach { state ->
            val active = ui(state)
            assertTrue("фаза $state должна быть активной", active.active)
            assertNotEquals(VoiceDialogPhase.OFF, active.phase)
            assertTrue("у активной фазы $state должна быть подпись", active.label.isNotEmpty())
        }
    }

    @Test
    fun `state is readable without colour - icon and word differ per phase`() {
        val listening = ui(VoiceDialogLoop.State.LISTENING)
        val thinking = ui(VoiceDialogLoop.State.THINKING)
        val speaking = ui(VoiceDialogLoop.State.SPEAKING)

        assertEquals(setOf(1, 2, 3), setOf(listening.iconRes, thinking.iconRes, speaking.iconRes))
        assertEquals(
            setOf("Слушаю", "Думаю", "Говорю"),
            setOf(listening.stateDescription, thinking.stateDescription, speaking.stateDescription),
        )
        assertEquals(3, setOf(listening.label, thinking.label, speaking.label).size)
    }

    @Test
    fun `audio wins over loop state - first phrase may sound while loop still listening`() {
        assertEquals(VoiceDialogPhase.SPEAKING, policy.phaseFor(VoiceDialogLoop.State.LISTENING, speaking = true))
        assertEquals(VoiceDialogPhase.SPEAKING, policy.phaseFor(VoiceDialogLoop.State.THINKING, speaking = true))
    }

    @Test
    fun `labels fit one status line and do not instruct with a sentence`() {
        listOf(VoiceDialogLoop.State.LISTENING, VoiceDialogLoop.State.THINKING, VoiceDialogLoop.State.SPEAKING)
            .map { ui(it).label }
            .forEach { label ->
                assertFalse("перенос строки в статусе: $label", label.contains("\n"))
                // 20 символов — гарантия одной строки на 360 dp: длинная подпись переносится
                // и сдвигает композер на каждой смене фазы.
                assertTrue("подпись слишком длинная: $label", label.length <= 20)
                assertFalse("подпись не должна быть инструкцией: $label", label.contains("Нажмите"))
            }
    }

    @Test
    fun `button size never changes between phases`() {
        // 34 dp — прежний размер кнопок композера: увеличение сдвигает соседей и ломает
        // попадание пальцем на узком экране (POCO).
        assertEquals(34, VoiceDialogPresentationPolicy.BUTTON_SIZE_DP)
        assertTrue(VoiceDialogPresentationPolicy.ACTIVE_STROKE_DP in 1..3)
    }

    @Test
    fun `stop hint matches what the tap actually does`() {
        // Тап останавливает звук только там, где звук есть; в «думаю» он закрывает реплику/не врёт.
        assertTrue(ui(VoiceDialogLoop.State.SPEAKING).showsStopAffordance)
        assertTrue(ui(VoiceDialogLoop.State.SPEAKING).contentDescription.contains("остановить звук"))
        assertTrue(ui(VoiceDialogLoop.State.LISTENING).contentDescription.contains("закрыть реплику"))
        // В любой активной фазе удержание остаётся выходом из режима.
        listOf(VoiceDialogLoop.State.LISTENING, VoiceDialogLoop.State.THINKING, VoiceDialogLoop.State.SPEAKING)
            .forEach { state ->
                assertTrue(
                    "в фазе $state потеряна подсказка выключения",
                    ui(state).contentDescription.contains("выключить диалог"),
                )
            }
    }

    @Test
    fun `idle description keeps the pre-existing affordance wording`() {
        // Та же строка, что была у кнопки до режима: смена подписи без причины ломает привычку.
        assertEquals(
            "Голосовой ввод, удержание — голосовой диалог",
            VoiceDialogPresentationPolicy.MIC_IDLE_DESCRIPTION,
        )
        assertEquals(VoiceDialogPresentationPolicy.MIC_IDLE_DESCRIPTION, ui(null).contentDescription)
    }

    @Test
    fun `active description announces mode and state for talkback`() {
        val active = ui(VoiceDialogLoop.State.LISTENING)
        assertTrue(active.contentDescription.startsWith("Голосовой диалог включён."))
        assertTrue(active.contentDescription.contains("Слушаю"))
        assertEquals("Слушаю", active.stateDescription)
    }
}
