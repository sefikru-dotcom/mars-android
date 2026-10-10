package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Юнит-тесты [ComposerInputGuardPolicy] — защиты композера от программных перезаписей:
 * текст пользователя не затирается SSE-кадрами, черновиками и сетевыми переключениями;
 * фокус/клавиатура восстанавливаются одноразово и только после «тишины» в сети;
 * SSE-драйвенные перерисовки композера прореживаются.
 */
class ComposerInputGuardPolicyTest {

    private var now = 1_700_000_000_000L
    private val policy = ComposerInputGuardPolicy(clock = { now })

    private fun transition(from: NetworkState, to: NetworkState) =
        policy.onNetworkTransition(NetworkTransition(from, to))

    private fun dropWhileFocused() {
        policy.onComposerFocusChanged(true)
        transition(NetworkState.ONLINE, NetworkState.OFFLINE)
        policy.onComposerFocusChanged(false)
    }

    // --- защита текста ----------------------------------------------------------------------------

    @Test
    fun `stream updates never touch composer text even on pristine composer`() {
        assertSame(ComposerTextDecision.Preserve, policy.resolveTextApply(TextApplyOrigin.STREAM_UPDATE))
        policy.onUserEdited()
        assertSame(ComposerTextDecision.Preserve, policy.resolveTextApply(TextApplyOrigin.STREAM_UPDATE))
    }

    @Test
    fun `network changes never touch composer text`() {
        assertSame(ComposerTextDecision.Preserve, policy.resolveTextApply(TextApplyOrigin.NETWORK_CHANGE))
        policy.onUserEdited()
        assertSame(ComposerTextDecision.Preserve, policy.resolveTextApply(TextApplyOrigin.NETWORK_CHANGE))
    }

    @Test
    fun `draft restore applies only while text is unowned`() {
        assertFalse(policy.userOwnsText)
        assertSame(ComposerTextDecision.Apply, policy.resolveTextApply(TextApplyOrigin.DRAFT_RESTORE))
        policy.onUserEdited()
        assertTrue(policy.userOwnsText)
        assertSame(ComposerTextDecision.Preserve, policy.resolveTextApply(TextApplyOrigin.DRAFT_RESTORE))
    }

    @Test
    fun `deliberate manual clearing still marks text user-owned`() {
        // Пользователь сам стёр поле — черновик не должен «воскрешать» старый текст.
        policy.onUserEdited()
        assertSame(ComposerTextDecision.Preserve, policy.resolveTextApply(TextApplyOrigin.DRAFT_RESTORE))
    }

    @Test
    fun `send reset and programmatic apply release text ownership`() {
        policy.onUserEdited()
        policy.onSendReset()
        assertFalse(policy.userOwnsText)
        assertSame(ComposerTextDecision.Apply, policy.resolveTextApply(TextApplyOrigin.DRAFT_RESTORE))

        policy.onUserEdited()
        policy.onTextAppliedProgrammatically()
        assertSame(ComposerTextDecision.Apply, policy.resolveTextApply(TextApplyOrigin.DRAFT_RESTORE))
    }

    @Test
    fun `user-action origins always apply even while typing`() {
        policy.onUserEdited()
        assertSame(ComposerTextDecision.Apply, policy.resolveTextApply(TextApplyOrigin.SESSION_SWITCH))
        assertSame(ComposerTextDecision.Apply, policy.resolveTextApply(TextApplyOrigin.SEND_RESET))
        assertSame(ComposerTextDecision.Apply, policy.resolveTextApply(TextApplyOrigin.VOICE_INPUT))
    }

    @Test
    fun `typing during stream keeps ownership across chunks and flaps`() {
        policy.onUserEdited()
        repeat(5) {
            assertSame(ComposerTextDecision.Preserve, policy.resolveTextApply(TextApplyOrigin.STREAM_UPDATE))
        }
        transition(NetworkState.ONLINE, NetworkState.LOSING)
        transition(NetworkState.LOSING, NetworkState.OFFLINE)
        transition(NetworkState.OFFLINE, NetworkState.ONLINE)
        assertSame(ComposerTextDecision.Preserve, policy.resolveTextApply(TextApplyOrigin.NETWORK_CHANGE))
        assertSame(ComposerTextDecision.Preserve, policy.resolveTextApply(TextApplyOrigin.DRAFT_RESTORE))
        assertTrue(policy.userOwnsText)
    }

    @Test
    fun `session switch clears ownership so new draft applies`() {
        policy.onUserEdited()
        policy.onSessionSwitch()
        assertFalse(policy.userOwnsText)
        assertSame(ComposerTextDecision.Apply, policy.resolveTextApply(TextApplyOrigin.DRAFT_RESTORE))
    }

    // --- защита фокуса ------------------------------------------------------------------------------

    @Test
    fun `stream chunks are always forbidden from moving focus`() {
        // Даже при ожидающем восстановлении фокуса SSE-кадр не имеет права его переставлять.
        dropWhileFocused()
        assertTrue(policy.pendingFocusRestore)
        assertSame(ComposerFocusDecision.Suppress, policy.resolveFocusRestore(FocusRequestOrigin.STREAM_CHUNK))
        assertTrue(policy.pendingFocusRestore)
        assertSame(ComposerFocusDecision.Suppress, policy.resolveFocusRestore(FocusRequestOrigin.STREAM_CHUNK))
    }

    @Test
    fun `no pending restore means network settle keeps focus untouched`() {
        assertSame(ComposerFocusDecision.Keep, policy.resolveFocusRestore(FocusRequestOrigin.NETWORK_SETTLED))
        assertSame(ComposerFocusDecision.Keep, policy.resolveFocusRestore(FocusRequestOrigin.BANNER_HIDDEN))
        // Обрыв без фокуса на композере ничего не запоминает.
        transition(NetworkState.ONLINE, NetworkState.OFFLINE)
        assertFalse(policy.pendingFocusRestore)
        transition(NetworkState.OFFLINE, NetworkState.ONLINE)
        assertSame(ComposerFocusDecision.Keep, policy.resolveFocusRestore(FocusRequestOrigin.NETWORK_SETTLED))
    }

    @Test
    fun `drop while focused then calm restore brings focus back once`() {
        dropWhileFocused()
        // Офлайн длился дольше окна флаппинга — к моменту восстановления сеть «остыла».
        now += ComposerInputGuardPolicy.DEFAULT_FLAP_WINDOW_MILLIS + 5_000
        transition(NetworkState.OFFLINE, NetworkState.ONLINE)
        assertFalse(policy.isFlapping())
        assertSame(
            ComposerFocusDecision.RestoreWithKeyboard,
            policy.resolveFocusRestore(FocusRequestOrigin.NETWORK_SETTLED),
        )
        assertFalse(policy.pendingFocusRestore)
        // Single-shot: повторный запрос до нового обрыва — Keep.
        assertSame(ComposerFocusDecision.Keep, policy.resolveFocusRestore(FocusRequestOrigin.NETWORK_SETTLED))
        assertSame(ComposerFocusDecision.Keep, policy.resolveFocusRestore(FocusRequestOrigin.BANNER_HIDDEN))
    }

    @Test
    fun `banner hidden origin restores focus identically to network settle`() {
        dropWhileFocused()
        now += 30_000
        transition(NetworkState.OFFLINE, NetworkState.ONLINE)
        assertSame(
            ComposerFocusDecision.RestoreWithKeyboard,
            policy.resolveFocusRestore(FocusRequestOrigin.BANNER_HIDDEN),
        )
    }

    @Test
    fun `quick blip defers focus restore until the flap window quiets down`() {
        dropWhileFocused()
        now += 100 // мгновенный restore — оба перехода внутри окна: флаппинг.
        transition(NetworkState.OFFLINE, NetworkState.ONLINE)
        assertTrue(policy.isFlapping())
        assertSame(
            ComposerFocusDecision.DeferUntilQuiet,
            policy.resolveFocusRestore(FocusRequestOrigin.NETWORK_SETTLED),
        )
        // Defer не потребляет одноразовое восстановление.
        assertTrue(policy.pendingFocusRestore)
        val remaining = policy.quietMillisRemaining()
        assertEquals(ComposerInputGuardPolicy.DEFAULT_FLAP_WINDOW_MILLIS - 100, remaining)

        now += remaining // окно прошло: первый переход выпал, осталась тишина.
        assertFalse(policy.isFlapping())
        assertEquals(0, policy.quietMillisRemaining())
        assertSame(
            ComposerFocusDecision.RestoreWithKeyboard,
            policy.resolveFocusRestore(FocusRequestOrigin.NETWORK_SETTLED),
        )
    }

    @Test
    fun `repeated flaps keep deferring and recompute quiet window from penultimate transition`() {
        dropWhileFocused()
        // Серия из четырёх переходов плотнее окна — сеть «мигает».
        repeat(2) {
            now += 200
            transition(NetworkState.OFFLINE, NetworkState.ONLINE)
            now += 200
            transition(NetworkState.ONLINE, NetworkState.OFFLINE)
        }
        now += 200
        transition(NetworkState.OFFLINE, NetworkState.ONLINE)
        assertTrue(policy.isFlapping())
        assertSame(ComposerFocusDecision.DeferUntilQuiet, policy.resolveFocusRestore(FocusRequestOrigin.NETWORK_SETTLED))

        // Тишина наступает, когда предпоследний переход покинет окно.
        val remaining = policy.quietMillisRemaining()
        assertTrue(remaining in 1..ComposerInputGuardPolicy.DEFAULT_FLAP_WINDOW_MILLIS)
        now += remaining
        assertFalse(policy.isFlapping())
        assertSame(
            ComposerFocusDecision.RestoreWithKeyboard,
            policy.resolveFocusRestore(FocusRequestOrigin.NETWORK_SETTLED),
        )
    }

    @Test
    fun `single drop-restore pair right at window edge stops flapping`() {
        dropWhileFocused()
        now += 100
        transition(NetworkState.OFFLINE, NetworkState.ONLINE)
        assertTrue(policy.isFlapping())
        // Ровно на границе окна первый переход выпадает (prune по >=).
        now += ComposerInputGuardPolicy.DEFAULT_FLAP_WINDOW_MILLIS - 100
        assertFalse(policy.isFlapping())
    }

    @Test
    fun `user refocusing composer cancels pending restore`() {
        dropWhileFocused()
        now += 30_000
        transition(NetworkState.OFFLINE, NetworkState.ONLINE)
        policy.onComposerFocusChanged(true) // пользователь сам тапнул в поле
        assertSame(ComposerFocusDecision.Keep, policy.resolveFocusRestore(FocusRequestOrigin.NETWORK_SETTLED))
        assertFalse(policy.pendingFocusRestore)
    }

    @Test
    fun `losing state also arms focus restore because sending is already blocked`() {
        policy.onComposerFocusChanged(true)
        transition(NetworkState.ONLINE, NetworkState.LOSING)
        assertTrue(policy.pendingFocusRestore)
    }

    @Test
    fun `session switch clears pending restore and flap history`() {
        dropWhileFocused()
        now += 100
        transition(NetworkState.OFFLINE, NetworkState.ONLINE)
        assertTrue(policy.isFlapping())
        policy.onSessionSwitch()
        assertFalse(policy.pendingFocusRestore)
        assertFalse(policy.isFlapping())
        assertSame(ComposerFocusDecision.Keep, policy.resolveFocusRestore(FocusRequestOrigin.NETWORK_SETTLED))
    }

    @Test
    fun `reset clears every guard state`() {
        dropWhileFocused()
        policy.onUserEdited()
        now += 100
        transition(NetworkState.OFFLINE, NetworkState.ONLINE)
        assertTrue(policy.allowComposerRender())
        policy.reset()
        assertFalse(policy.userOwnsText)
        assertFalse(policy.composerFocused)
        assertFalse(policy.pendingFocusRestore)
        assertFalse(policy.isFlapping())
        // После сброса троттл рендера снова пропускает первый вызов.
        assertTrue(policy.allowComposerRender())
    }

    // --- прореживание SSE-ре-рендеров ---------------------------------------------------------------

    @Test
    fun `composer renders are throttled to the minimum interval`() {
        assertTrue(policy.allowComposerRender()) // первый кадр проходит всегда
        assertFalse(policy.allowComposerRender()) // немедленный повтор — коалесинг
        now += ComposerInputGuardPolicy.DEFAULT_RENDER_MIN_INTERVAL_MILLIS - 1
        assertFalse(policy.allowComposerRender())
        now += 1
        assertTrue(policy.allowComposerRender())
        assertFalse(policy.allowComposerRender())
    }

    @Test
    fun `render throttle interval is configurable`() {
        val strict = ComposerInputGuardPolicy(clock = { now }, renderMinIntervalMillis = 500)
        assertTrue(strict.allowComposerRender())
        now += 499
        assertFalse(strict.allowComposerRender())
        now += 1
        assertTrue(strict.allowComposerRender())
    }

    @Test
    fun `flap window is configurable`() {
        val wide = ComposerInputGuardPolicy(clock = { now }, flapWindowMillis = 10_000)
        wide.onComposerFocusChanged(true)
        wide.onNetworkTransition(NetworkTransition(NetworkState.ONLINE, NetworkState.OFFLINE))
        now += 5_000
        wide.onNetworkTransition(NetworkTransition(NetworkState.OFFLINE, NetworkState.ONLINE))
        // 5 c < 10 c окна — переходы всё ещё вместе: флаппинг.
        assertTrue(wide.isFlapping())
        assertEquals(5_000, wide.quietMillisRemaining())
    }

    // --- константы ----------------------------------------------------------------------------------

    @Test
    fun `defaults are sane and documented`() {
        assertEquals(2_000L, ComposerInputGuardPolicy.DEFAULT_FLAP_WINDOW_MILLIS)
        assertEquals(2, ComposerInputGuardPolicy.FLAP_TRANSITION_THRESHOLD)
        assertEquals(100L, ComposerInputGuardPolicy.DEFAULT_RENDER_MIN_INTERVAL_MILLIS)
    }
}
