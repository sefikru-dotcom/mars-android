package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт шкалы и вертикального бюджета экрана чата (activity_tv_chat.xml).
 *
 * Причина существования: шапка переполнялась — четыре текстовые кнопки занимали
 * 732dp из 904dp доступных, и заголовку доставалось 38dp, то есть он схлопывался
 * в вертикальную колонку по одной букве. Композер и ряд чипсов были на «подобранных»
 * dp, поэтому при открытой клавиатуре (224dp из 540dp) под ленту оставалось 106dp —
 * ровно одно двухстрочное сообщение.
 *
 * Тест держит три вещи, которые легко откатить обратно:
 *  1. заголовок шапки не сжимается (minWidth + одна строка + ellipsize);
 *  2. лента влезает в реальный вертикальный бюджет при клавиатуре — считается
 *     арифметикой из тех же dimens, а не проверкой одного атрибута (minHeight на
 *     контейнере с weight=1 не работает вовсе, поэтому «объявленный пол» ничего
 *     не гарантировал);
 *  3. в лейауте НЕТ ни одного кегля числом и композер/чипсы стоят на токенах.
 *
 * Как и остальные тесты модуля — обычный JVM-тест, читающий исходники с диска
 * (`user.dir` = каталог модуля app-tv), без Robolectric.
 */
class TvChatScaleContractTest {

    private val moduleDir = File(System.getProperty("user.dir") ?: ".")
    private val layoutDir = File(moduleDir, "src/main/res/layout")
    private val srcDir = File(moduleDir, "src/main/java/ru/mars/tv")

    private val chat = File(layoutDir, "activity_tv_chat.xml").readText()

    /** Самозакрывающийся блок view по id: `<Button ... android:id="@+id/x" ... />`. */
    private fun block(viewId: String, source: String = chat): String {
        val at = source.indexOf("android:id=\"@+id/$viewId\"")
        assertTrue("в лейауте нет @+id/$viewId", at >= 0)
        val start = source.lastIndexOf('<', at)
        val end = source.indexOf("/>", at) + 2
        return source.substring(start, end)
    }

    private fun attr(viewId: String, name: String): String? =
        Regex("android:$name=\"([^\"]*)\"").find(block(viewId))?.groupValues?.get(1)

    // ── шапка ────────────────────────────────────────────────────────────────────────

    @Test
    fun `header title never collapses to a one-letter column`() {
        val title = block("chatTitle")
        assertTrue("заголовку нужен minWidth из шкалы", title.contains("android:minWidth=\"@dimen/tv_title_min_w\""))
        assertEquals("заголовок — одна строка", "1", attr("chatTitle", "maxLines"))
        assertEquals("обрезается многоточием", "end", attr("chatTitle", "ellipsize"))
        assertTrue(
            "заголовок набирается ступенью шкалы, а не числом sp",
            title.contains("android:textAppearance=\"@style/AppText.Headline\""),
        )
    }

    @Test
    fun `header keeps one text action and three icon actions`() {
        // Текстовая кнопка в шапке допустима ровно одна («Режим»): каждая следующая
        // возвращает переполнение и снова душит заголовок.
        val header = chat.substring(
            chat.indexOf("android:id=\"@+id/chatRoot\""),
            chat.indexOf("<!-- Mini player"),
        )
        val buttons = Regex("<Button\\b[^>]*/>", RegexOption.DOT_MATCHES_ALL)
            .findAll(header).map { it.value }.toList()
        val textButtons = buttons.filter { it.contains("android:text=") }
        assertEquals("в шапке должна остаться одна текстовая кнопка", 1, textButtons.size)

        // Вторичные действия — иконки 40dp с подписью для TalkBack (текст «съедал» ширину).
        listOf("backButton", "sessionsButton", "newChatButton").forEach { id ->
            val icon = block(id)
            assertFalse("$id: текст убран в пользу иконки", icon.contains("android:text="))
            assertEquals("$id: квадрат иконки 40dp", "@dimen/tv_btn_icon_h", attr(id, "layout_width"))
            assertEquals("$id: квадрат иконки 40dp", "@dimen/tv_btn_icon_h", attr(id, "layout_height"))
            assertTrue("$id: подпись для TalkBack", icon.contains("android:contentDescription=\""))
            assertTrue("$id: векторная иконка", icon.contains("android:drawableStart=\"@drawable/ic_"))
        }
    }

    // ── лента и композер ─────────────────────────────────────────────────────────────

    @Test
    fun `transcript fits the real vertical budget with the keyboard open`() {
        // Считаем по тем же ресурсам, что видит устройство. Экран Mi TV — 960x540dp,
        // клавиатура занимает 224dp. minHeight на контейнере с weight=1 не действует,
        // поэтому единственная настоящая гарантия — арифметика хрома.
        val dimens = File(moduleDir, "src/main/res/values/dimens.xml").readText()
        fun dp(name: String): Int =
            Regex("<dimen name=\"$name\">(\\d+)dp</dimen>").find(dimens)
                ?.groupValues?.get(1)?.toInt()
                ?: error("нет токена $name в dimens.xml")

        val screen = 540
        val ime = 224
        val chrome = dp("tv_screen_pad_top") + dp("tv_screen_pad_bottom") +
            dp("tv_header_h") + dp("tv_chat_row_gap") + dp("tv_chat_composer_h")
        val floor = dp("tv_chat_transcript_min_h")
        val window = screen - ime

        assertTrue(
            "хром $chrome dp + пол ленты $floor dp не влезают в окно $window dp: " +
                "композер окажется под клавиатурой",
            chrome + floor <= window,
        )
        assertTrue(
            "при клавиатуре лента должна давать хотя бы два сообщения (~90dp каждое), " +
                "а не одно; сейчас ${window - chrome} dp",
            window - chrome >= 170,
        )
    }

    @Test
    fun `composer and chips rows are sized by tokens`() {
        assertEquals("@dimen/tv_chat_composer_h", attr("messageInput", "layout_height"))
        assertEquals("@dimen/tv_chat_composer_h", attr("sendButton", "layout_height"))
        assertEquals("@dimen/tv_chat_chips_h", attr("quickChipsRow", "layout_height"))
        assertEquals(
            "обёртка ряда чипсов прячется при открытой клавиатуре — ей нужен id",
            "@dimen/tv_chat_chips_h",
            attr("quickChipsScroll", "layout_height"),
        )
    }

    @Test
    fun `ime collapse is wired to the real keyboard detector`() {
        // Видимость чипсов считает существующий TvDpadTextInput.isImeVisible; отдельный
        // флаг в хосте завёл бы второй источник правды о клавиатуре.
        val host = File(srcDir, "TvChatActivity.kt").readText()
        assertTrue("хост обязан пересчитывать видимость IME", host.contains("TvDpadTextInput.isImeVisible"))
        assertTrue("прячется именно ряд чипсов", host.contains("quickChipsScroll.visibility"))
        assertTrue("listener снимается в onDestroy", host.contains("removeOnGlobalLayoutListener"))
    }

    // ── шкала текста ─────────────────────────────────────────────────────────────────

    @Test
    fun `layout carries no raw textSize at all`() {
        val raw = Regex("""android:textSize="\d+sp"""").findAll(chat).map { it.value }.toList()
        assertTrue("кегли только через @style/AppText.*, найдено: $raw", raw.isEmpty())
        assertTrue(
            "экран должен реально пользоваться ступенями шкалы",
            Regex("""android:textAppearance="@style/AppText\.\w+"""").findAll(chat).count() >= 10,
        )
    }

    @Test
    fun `renderers take their font sizes from the scale tokens`() {
        // Программный рендер не проходит через styles.xml — он обязан брать @dimen/tv_text_*,
        // иначе плоский и «богатый» пузырь разъезжаются по кеглю.
        listOf("TvChatTranscriptSection.kt", "chat/TvMarkdownRenderer.kt").forEach { rel ->
            val source = File(srcDir, rel).readText()
            val raw = Regex("""textSize\s*=\s*\d+(?:\.\d+)?f""").findAll(source).map { it.value }.toList()
            assertTrue("$rel: кегль числом запрещён, найдено $raw", raw.isEmpty())
            assertTrue("$rel: ожидался токен шкалы", source.contains("R.dimen.tv_text_"))
        }
    }
}
