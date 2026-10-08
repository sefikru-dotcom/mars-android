package ru.mars.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design-system parity guard for app-tv (WebUI MarsTheme):
 * Dark Obsidian (#07080B) background, ruby accent (#EF4444),
 * zero emojis, vector icons only, D-pad focus StateListDrawables.
 *
 * Runs as a plain JVM test: it scans the module sources on disk.
 */
class TvDesignSystemTest {

    private val moduleDir = File(System.getProperty("user.dir"))
    private val resDir = File(moduleDir, "src/main/res")
    private val srcDir = File(moduleDir, "src/main")

    private fun sourceFiles(ext: String): List<File> =
        srcDir.walkTopDown().filter { it.isFile && it.extension == ext }.toList()

    private val emojiRanges = listOf(
        0x1F300..0x1FAFF, 0x2600..0x27BF, 0x2B00..0x2BFF,
        0xFE00..0xFE0F, 0x2190..0x21FF, 0x25A0..0x25FF,
    )

    private fun String.containsEmoji(): Boolean = codePoints().anyMatch { cp ->
        emojiRanges.any { cp in it }
    }

    @Test
    fun `colors define dark obsidian background and ruby accent`() {
        val colors = File(resDir, "values/colors.xml").readText().lowercase()
        assertTrue("Dark Obsidian #07080B missing", colors.contains("#07080b"))
        assertTrue("Ruby accent #EF4444 missing", colors.contains("#ef4444"))
    }

    @Test
    fun `no emoji or glyph symbols anywhere in module sources`() {
        val offenders = (sourceFiles("kt") + sourceFiles("xml"))
            .filter { it.name != "TvDesignSystemTest.kt" }
            .filter { it.readText().containsEmoji() }
            .map { it.path }
        assertTrue("Emoji/glyph symbols found: $offenders", offenders.isEmpty())
    }

    @Test
    fun `icons are VectorDrawables, no png emoji assets`() {
        val drawables = File(resDir, "drawable").listFiles()?.filter { it.isFile } ?: emptyList()
        val icons = drawables.filter { it.name.startsWith("ic_") }
        assertTrue("expected vector icons", icons.isNotEmpty())
        icons.forEach {
            assertTrue("${it.name} must be a vector", it.readText().contains("<vector"))
        }
        val binaries = drawables.filter { it.extension in setOf("png", "jpg", "webp", "gif") }
        assertTrue("raster assets found: ${binaries.map { it.name }}", binaries.isEmpty())
    }

    @Test
    fun `focus selectors provide ruby StateListDrawable highlight`() {
        val selectors = listOf("bg_focus_card.xml", "bg_focus_accent.xml", "bg_focus_input.xml")
        selectors.forEach { name ->
            val text = File(resDir, "drawable/$name").readText()
            assertTrue("$name must be a selector", text.contains("<selector"))
            assertTrue("$name must handle D-pad focus", text.contains("state_focused"))
            assertTrue("$name must cross-fade smoothly", text.contains("enterFadeDuration"))
            assertTrue("$name must use ruby accent", text.contains("mars_accent"))
        }
    }

    @Test
    fun `layouts use theme colors, not hardcoded legacy hex`() {
        val legacyHex = Regex("#(7EE787|ff7b72|aab2c0|1b202b)", RegexOption.IGNORE_CASE)
        val offenders = sourceFiles("xml")
            .filter { legacyHex.containsMatchIn(it.readText()) }
            .map { it.path }
        assertTrue("legacy colors remain: $offenders", offenders.isEmpty())
    }

    @Test
    fun `chat bubbles use focusable StateListDrawable backgrounds`() {
        listOf("bg_bubble_user.xml", "bg_bubble_bot.xml").forEach { name ->
            val text = File(resDir, "drawable/$name").readText()
            assertTrue("$name must be a selector", text.contains("<selector"))
            assertTrue("$name must handle focus", text.contains("state_focused"))
        }
    }
}
