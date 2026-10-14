package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Бюджет памяти просмотрщика изображений — чистые хелперы контроллера, без Android-view:
 * обрыв чтения на превышении payload-капа и множитель downsample под предельную сторону.
 *
 * Проверяется именно то, что делает лайтбокс безопасным на слабом устройстве:
 * гигантский ответ отбрасывается ДО декодирования, а кадр больше 4096 px ужимается.
 */
class ChatImageLightboxControllerTest {

    // --- payload cap ---

    @Test
    fun `payload under the cap is read whole`() {
        val payload = ByteArray(4 * 1024) { 7 }
        val read = ChatImageLightboxController.readBounded(ByteArrayInputStream(payload), 1024 * 1024)
        assertEquals(payload.size, read?.size)
    }

    @Test
    fun `payload at the cap is still accepted`() {
        val payload = ByteArray(1024)
        val read = ChatImageLightboxController.readBounded(ByteArrayInputStream(payload), 1024)
        assertEquals(1024, read?.size)
    }

    @Test
    fun `payload above the cap is dropped before decoding`() {
        val payload = ByteArray(2048)
        assertNull(ChatImageLightboxController.readBounded(ByteArrayInputStream(payload), 1024))
    }

    @Test
    fun `the viewer cap is twenty five mebibytes`() {
        assertEquals(25L * 1024 * 1024, ChatImageLightboxController.MAX_IMAGE_BYTES)
        assertEquals(4096, ChatImageLightboxController.MAX_IMAGE_DIMENSION)
    }

    // --- downsample ---

    @Test
    fun `small frames are not downsampled`() {
        assertEquals(1, ChatImageLightboxController.sampleSizeFor(1024, 768, 4096))
        assertEquals(1, ChatImageLightboxController.sampleSizeFor(4096, 4096, 4096))
    }

    @Test
    fun `oversized frames halve until both sides fit`() {
        assertEquals(2, ChatImageLightboxController.sampleSizeFor(8192, 4096, 4096))
        assertEquals(4, ChatImageLightboxController.sampleSizeFor(8192, 8192, 4096))
        assertEquals(2, ChatImageLightboxController.sampleSizeFor(4096, 9000, 4096))
    }

    @Test
    fun `undecodable bounds leave the sample size alone`() {
        assertEquals(1, ChatImageLightboxController.sampleSizeFor(0, 0, 4096))
        assertEquals(1, ChatImageLightboxController.sampleSizeFor(-1, 100, 4096))
        assertEquals(1, ChatImageLightboxController.sampleSizeFor(1000, 1000, 0))
    }

    // --- резолв URL: относительный путь клеится к базе, чужое отбрасывается ---

    @Test
    fun `relative image paths resolve against the base url`() {
        assertEquals(
            "https://marscore.ru/uploads/pic.png",
            ImageLightboxPolicy.resolveUrl("/uploads/pic.png", "https://marscore.ru"),
        )
        assertNull(ImageLightboxPolicy.resolveUrl(null, "https://marscore.ru"))
    }
}
