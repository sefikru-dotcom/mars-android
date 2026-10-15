package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правило отрисовки ссылок в чате: цвет приходит из темы, а не из хардкода,
 * и ссылка всегда подчёркнута. Тест чисто JVM — Android-классы не нужны,
 * потому что правило вынесено в [LinkRenderPolicy].
 */
class LinkRenderPolicyTest {

    @Test
    fun linkUsesAccentColorFromThemeAndStaysUnderlined() {
        val state = LinkRenderPolicy.drawState(MarsTheme.ACCENT_TEXT)
        assertEquals(MarsTheme.ACCENT_TEXT, state.color)
        assertTrue("ссылка обязана отличаться подчёркиванием", state.underline)
    }

    @Test
    fun linkBackgroundIsTransparentToDropTheHighlightPlate() {
        // Иначе базовый ClickableSpan заливает текст widget.highlightColor.
        assertEquals(0, LinkRenderPolicy.drawState(MarsTheme.ACCENT_TEXT).background)
    }

    @Test
    fun accentColorIsTakenFromTheArgumentNotHardcoded() {
        // Другой акцент темы — другой цвет: правило не подменяет палитру своей.
        val primary = LinkRenderPolicy.drawState(MarsTheme.ACCENT_TEXT)
        val alternative = LinkRenderPolicy.drawState(MarsTheme.ACCENT_HOVER)
        assertEquals(MarsTheme.ACCENT_HOVER, alternative.color)
        assertNotEquals(primary.color, alternative.color)
    }

    @Test
    fun themeAccentDiffersFromBodyTextSoTheLinkIsVisible() {
        // Визуальный признак ссылки: её цвет не совпадает с обычным текстом пузыря.
        val link = LinkRenderPolicy.drawState(MarsTheme.ACCENT_TEXT)
        assertNotEquals(MarsTheme.TEXT, link.color)
        assertNotEquals(MarsTheme.TEXT_EM, link.color)
    }
}
