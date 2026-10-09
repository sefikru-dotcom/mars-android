package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageLightboxPolicyTest {

    @Test
    fun `image extensions are recognized regardless of case and query`() {
        assertTrue(ImageLightboxPolicy.isImageUrl("https://marscore.ru/uploads/a.png"))
        assertTrue(ImageLightboxPolicy.isImageUrl("https://x.ru/i.JPG"))
        assertTrue(ImageLightboxPolicy.isImageUrl("https://x.ru/i.jpeg?v=2"))
        assertTrue(ImageLightboxPolicy.isImageUrl("https://x.ru/i.webp#frag"))
        assertTrue(ImageLightboxPolicy.isImageUrl("https://x.ru/gif/i.gif"))
    }

    @Test
    fun `non-image urls are rejected`() {
        assertFalse(ImageLightboxPolicy.isImageUrl("https://marscore.ru/api/sessions"))
        assertFalse(ImageLightboxPolicy.isImageUrl("https://x.ru/pic.png.html"))
        assertFalse(ImageLightboxPolicy.isImageUrl("https://x.ru/"))
        assertFalse(ImageLightboxPolicy.isImageUrl("https://x.ru/file"))
        assertFalse(ImageLightboxPolicy.isImageUrl(""))
    }

    @Test
    fun `relative server paths resolve against the base url`() {
        assertEquals(
            "https://marscore.ru/uploads/a.png",
            ImageLightboxPolicy.resolveUrl("/uploads/a.png", "https://marscore.ru"),
        )
        assertEquals(
            "https://marscore.ru/uploads/a.png",
            ImageLightboxPolicy.resolveUrl("uploads/a.png", "https://marscore.ru"),
        )
    }

    @Test
    fun `absolute http urls pass through and foreign schemes are rejected`() {
        assertEquals(
            "https://cdn.example.ru/i.png",
            ImageLightboxPolicy.resolveUrl("https://cdn.example.ru/i.png", "https://marscore.ru"),
        )
        assertNull(ImageLightboxPolicy.resolveUrl("javascript:alert(1)", "https://marscore.ru"))
        assertNull(ImageLightboxPolicy.resolveUrl(null, "https://marscore.ru"))
        assertNull(ImageLightboxPolicy.resolveUrl("  ", "https://marscore.ru"))
    }

    @Test
    fun `inline label falls back to a neutral caption without emoji`() {
        assertEquals("Скриншот", ImageLightboxPolicy.inlineLabel("Скриншот"))
        assertEquals("Изображение", ImageLightboxPolicy.inlineLabel(""))
        assertEquals("Изображение", ImageLightboxPolicy.inlineLabel("   "))
        assertEquals("Изображение", ImageLightboxPolicy.inlineLabel(null))
        assertEquals("Скрин", ImageLightboxPolicy.inlineLabel("  Скрин \n"))
    }
}
