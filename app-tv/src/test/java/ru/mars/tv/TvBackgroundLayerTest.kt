package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Гейт фона ТВ-клиента: фотография планеты + слои затемнения.
 *
 * Причина существования: фон — layer-list, и его корректность держится на двух
 * вещах, которые не видно в коде экрана. Первая: bitmap обязан лежать НИЖНИМ
 * слоем — если он окажется выше затемнения, картинка закроет интерфейс целиком
 * (и никакой тест экрана этого не заметит). Вторая: снимок обязан быть в
 * drawable-nodpi — в плотностном бакете Android пересэмплирует его под density,
 * и на 4K-панели фон мылится.
 *
 * Проверки читают файлы с диска (как [TvTypeScaleGateTest]): модуль app-tv не
 * имеет инструментации/Robolectric, а ресурсы — это обычный текст и байты.
 */
class TvBackgroundLayerTest {

    private val resDir = File(System.getProperty("user.dir"), "src/main/res")
    private val photo = File(resDir, "drawable-nodpi/bg_mars_photo.jpg")
    private val rootLayer = File(resDir, "drawable/bg_tv_root.xml")

    /** XML без комментариев: иначе упоминание токена в пояснении ломает проверку порядка. */
    private fun xmlWithoutComments(file: File): String =
        file.readText().replace(Regex("<!--[\\s\\S]*?-->"), "")

    private fun layout(name: String): File = File(resDir, "layout/$name")

    private val screensOnScale = listOf(
        "activity_tv_music.xml",
        "activity_pairing.xml",
        "dialog_tv_lyrics.xml",
        "dialog_tv_album.xml",
    )

    @Test
    fun `photo lives in drawable-nodpi and has a sane weight`() {
        assertTrue("нет файла ${photo.path}", photo.isFile)
        assertTrue(
            "фон обязан лежать в drawable-nodpi (иначе Android масштабирует его по плотности): ${photo.path}",
            photo.parentFile?.name == "drawable-nodpi",
        )
        val bytes = photo.length()
        assertTrue("фотография фона подозрительно мала: $bytes байт (< 20KB)", bytes > 20_000)
        assertTrue("фотография фона слишком тяжёлая для APK: $bytes байт (> 400KB)", bytes < 400_000)

        // JPEG-магия: файл действительно картинка, а не заглушка/текст.
        val magic = photo.inputStream().use { it.readNBytes(3) }
        assertEquals(
            "bg_mars_photo.jpg не является JPEG (нет FF D8 FF)",
            listOf(0xFF, 0xD8, 0xFF),
            magic.map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun `root layer references the photo and the text scrim`() {
        val xml = xmlWithoutComments(rootLayer)
        assertTrue("bg_tv_root.xml не ссылается на @drawable/bg_mars_photo", xml.contains("@drawable/bg_mars_photo"))
        assertTrue("bg_tv_root.xml не ссылается на @color/mars_bg_scrim", xml.contains("@color/mars_bg_scrim"))
        assertTrue("фон должен остаться layer-list", xml.contains("<layer-list"))
        assertEquals("слоёв ровно три: фото, scrim, виньетка", 3, Regex("<item\\b").findAll(xml).count())
    }

    @Test
    fun `bitmap layer comes before the darkening layers`() {
        val xml = xmlWithoutComments(rootLayer)
        val photoAt = xml.indexOf("@drawable/bg_mars_photo")
        val scrimAt = xml.indexOf("@color/mars_bg_scrim")
        assertTrue("в bg_tv_root.xml нет bitmap-слоя", photoAt >= 0)
        assertTrue("в bg_tv_root.xml нет затемняющего слоя", scrimAt >= 0)
        assertTrue(
            "bitmap должен идти ДО затемнения: иначе фотография перекроет интерфейс " +
                "(photo@$photoAt, scrim@$scrimAt)",
            photoAt < scrimAt,
        )
    }

    @Test
    fun `music pairing and both dialogs use only the type scale`() {
        val scale = setOf(11, 12, 14, 16, 20, 28, 40)
        val re = Regex("""android:textSize="(\d+)sp"""")
        val offenders = mutableListOf<String>()
        screensOnScale.forEach { name ->
            val file = layout(name)
            assertTrue("нет layout $name", file.isFile)
            re.findAll(file.readText()).forEach { m ->
                val sp = m.groupValues[1].toInt()
                if (sp !in scale) offenders += "$name: ${sp}sp"
            }
        }
        assertTrue("кегли вне шкалы $scale (используй AppText.*): $offenders", offenders.isEmpty())
    }

    @Test
    fun `screens on the scale do not hardcode dp paddings`() {
        // Разрешены только 0dp/match_parent и ссылки на @dimen/*: числа вида "16dp"
        // в экранах означают, что правка масштаба ТВ пройдёт мимо этого файла.
        val re = Regex("""android:(?:padding|layout_margin|layout_width|layout_height)[A-Za-z]*="(\d+)dp"""")
        val offenders = mutableListOf<String>()
        screensOnScale.forEach { name ->
            re.findAll(layout(name).readText()).forEach { m ->
                if (m.groupValues[1] != "0") offenders += "$name: ${m.value}"
            }
        }
        assertTrue("хардкод dp в экранах (нужен токен из dimens.xml): $offenders", offenders.isEmpty())
    }
}
