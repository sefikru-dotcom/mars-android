package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageLightboxControllerTest {

    private val controller = ImageLightboxController()

    private fun spec(url: String, caption: String = "img", index: Int = -1) =
        ImageLightboxSpec(url, caption, index)

    // --- ImageLightboxSpec.from ---

    @Test
    fun `spec from an absolute image url keeps it as is`() {
        val s = ImageLightboxSpec.from("https://marscore.ru/media/photo.jpg", alt = "Закат")
        assertEquals("https://marscore.ru/media/photo.jpg", s?.url)
        assertEquals("Закат", s?.caption)
    }

    @Test
    fun `spec resolves a relative path against the base url`() {
        val s = ImageLightboxSpec.from("/uploads/pic.png", baseUrl = "https://example.com")
        assertEquals("https://example.com/uploads/pic.png", s?.url)
    }

    @Test
    fun `spec rejects null, blank, non-image and non-http values`() {
        assertNull(ImageLightboxSpec.from(null))
        assertNull(ImageLightboxSpec.from("   "))
        assertNull(ImageLightboxSpec.from("https://example.com/page"))
        assertNull(ImageLightboxSpec.from("https://example.com/doc.pdf"))
        assertNull(ImageLightboxSpec.from("javascript:alert(1)"))
        assertNull(ImageLightboxSpec.from("file:///sdcard/img/photo.jpg"))
    }

    @Test
    fun `image detection survives query strings and uppercase extensions`() {
        val s = ImageLightboxSpec.from("https://example.com/a/PHOTO.JPEG?token=1#frag")
        assertEquals("https://example.com/a/PHOTO.JPEG?token=1#frag", s?.url)
    }

    @Test
    fun `message index travels with the spec`() {
        val s = ImageLightboxSpec.from("https://example.com/a.png", messageIndex = 7)
        assertEquals(7, s?.messageIndex)
        assertEquals(-1, ImageLightboxSpec.from("https://example.com/a.png")?.messageIndex)
    }

    // --- caption extraction ---

    @Test
    fun `explicit alt wins over the file name`() {
        assertEquals("Мой кот", ImageLightboxSpec.captionFor("Мой кот", "https://x/cat_9.png"))
    }

    @Test
    fun `blank alt falls back to the file name without extension`() {
        assertEquals("sunset 2024", ImageLightboxSpec.captionFor("  ", "https://x/sunset_2024.jpg"))
        assertEquals("sunset 2024", ImageLightboxSpec.captionFor(null, "https://x/sunset_2024.jpg"))
    }

    @Test
    fun `file name caption ignores query and fragment and unpacks underscores`() {
        assertEquals("graph", ImageLightboxSpec.captionFor(null, "https://x/graph.png?v=2#top"))
    }

    @Test
    fun `caption falls back to the neutral label when nothing is usable`() {
        assertEquals("Изображение", ImageLightboxSpec.captionFor(null, "https://x/.png"))
    }

    // --- open / close ---

    @Test
    fun `open on an empty list is refused and the viewer stays closed`() {
        assertFalse(controller.open(emptyList()))
        assertFalse(controller.isOpen)
        assertNull(controller.current)
        assertEquals(0, controller.size)
        assertEquals(-1, controller.position)
    }

    @Test
    fun `open shows the first spec by default and clamps an out-of-range start index`() {
        val items = listOf(spec("a"), spec("b"), spec("c"))
        assertTrue(controller.open(items))
        assertEquals("a", controller.current?.url)
        assertEquals(3, controller.size)
        assertEquals(0, controller.position)

        assertTrue(controller.open(items, startIndex = 99))
        assertEquals("c", controller.current?.url)

        assertTrue(controller.open(items, startIndex = -5))
        assertEquals("a", controller.current?.url)
    }

    @Test
    fun `single-spec open convenience works`() {
        assertTrue(controller.open(spec("only")))
        assertEquals(1, controller.size)
        assertEquals("only", controller.current?.url)
    }

    @Test
    fun `dismiss closes the viewer and resets zoom`() {
        controller.open(listOf(spec("a"), spec("b")), startIndex = 1)
        controller.zoomIn()
        controller.dismiss()
        assertFalse(controller.isOpen)
        assertNull(controller.current)
        assertEquals(ImageLightboxController.MIN_ZOOM, controller.zoom, 0f)
        assertEquals(-1, controller.position)
    }

    // --- zoom ---

    @Test
    fun `zoom steps up and down with clamping at both ends`() {
        controller.open(listOf(spec("a")))
        assertEquals(1f, controller.zoom, 0f)
        assertFalse(controller.isZoomed)

        controller.zoomIn()
        assertEquals(1.5f, controller.zoom, 1e-6f)
        assertTrue(controller.isZoomed)

        repeat(10) { controller.zoomIn() }
        assertEquals(ImageLightboxController.MAX_ZOOM, controller.zoom, 0f)

        controller.zoomOut()
        assertEquals(3.5f, controller.zoom, 1e-6f)

        repeat(10) { controller.zoomOut() }
        assertEquals(ImageLightboxController.MIN_ZOOM, controller.zoom, 0f)
        assertFalse(controller.isZoomed)
    }

    @Test
    fun `setZoom clamps pinch results and rejects NaN`() {
        controller.open(listOf(spec("a")))
        controller.setZoom(2.3f)
        assertEquals(2.3f, controller.zoom, 1e-6f)
        controller.setZoom(99f)
        assertEquals(ImageLightboxController.MAX_ZOOM, controller.zoom, 0f)
        controller.setZoom(-1f)
        assertEquals(ImageLightboxController.MIN_ZOOM, controller.zoom, 0f)
        controller.setZoom(Float.NaN)
        assertEquals(ImageLightboxController.MIN_ZOOM, controller.zoom, 0f)
    }

    @Test
    fun `resetZoom returns to the resting scale`() {
        controller.open(listOf(spec("a")))
        controller.setZoom(3f)
        controller.resetZoom()
        assertEquals(1f, controller.zoom, 0f)
        assertFalse(controller.isZoomed)
    }

    @Test
    fun `a hair over the resting scale is still treated as not zoomed`() {
        controller.open(listOf(spec("a")))
        controller.setZoom(1.0001f)
        assertFalse(controller.isZoomed)
    }

    // --- gallery navigation ---

    @Test
    fun `next and previous wrap around the gallery and reset zoom`() {
        controller.open(listOf(spec("a"), spec("b"), spec("c")), startIndex = 2)
        controller.setZoom(2f)

        assertTrue(controller.next())
        assertEquals("a", controller.current?.url)
        assertEquals(0, controller.position)
        assertEquals(1f, controller.zoom, 0f) // navigation resets the zoom

        assertTrue(controller.previous())
        assertEquals("c", controller.current?.url)
        assertEquals(2, controller.position)

        assertTrue(controller.previous())
        assertEquals("b", controller.current?.url)
    }

    @Test
    fun `navigation is refused for a single-image gallery`() {
        controller.open(listOf(spec("only")))
        assertFalse(controller.next())
        assertFalse(controller.previous())
        assertEquals("only", controller.current?.url)
    }

    @Test
    fun `navigation is refused while closed`() {
        assertFalse(controller.next())
        assertFalse(controller.previous())
    }

    // --- dismiss guard ---

    @Test
    fun `back while zoomed collapses the zoom instead of closing`() {
        controller.open(listOf(spec("a")))
        controller.setZoom(2.5f)

        assertFalse(controller.onBackPressed())
        assertTrue(controller.isOpen)
        assertFalse(controller.isZoomed)

        // Second press at the resting scale really dismisses.
        assertTrue(controller.onBackPressed())
        assertFalse(controller.isOpen)
        assertNull(controller.current)
    }

    @Test
    fun `back at the resting scale closes immediately`() {
        controller.open(listOf(spec("a")))
        assertTrue(controller.onBackPressed())
        assertFalse(controller.isOpen)
    }

    @Test
    fun `back on a closed viewer is a no-op`() {
        assertFalse(controller.onBackPressed())
        assertFalse(controller.isOpen)
    }

    @Test
    fun `reopen after dismiss starts fresh at the resting scale`() {
        controller.open(listOf(spec("a")))
        controller.setZoom(4f)
        controller.dismiss()
        controller.open(listOf(spec("b")), startIndex = 0)
        assertEquals(1f, controller.zoom, 0f)
        assertEquals("b", controller.current?.url)
    }
}
