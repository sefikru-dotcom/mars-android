package ru.mars.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Honesty guard for TV status indicators: layouts must not statically claim the
 * server is «онлайн» — availability may only be shown after a successful check
 * (data-driven labels, cf. activity_tv_projects.xml). Runs as a plain JVM test
 * scanning module sources on disk (same pattern as TvDesignSystemTest).
 */
class TvStatusHonestyTest {

    private val resDir = File(System.getProperty("user.dir"), "src/main/res")

    private fun layout(name: String): String = File(resDir, "layout/$name").readText()

    @Test
    fun `chat and dashboard layouts contain no hardcoded online claim`() {
        // Any android:text asserting «онлайн» without data binding is fake.
        val fakeOnline = Regex("""android:text="[^"]*онлайн""", RegexOption.IGNORE_CASE)
        listOf("activity_tv_chat.xml", "activity_dashboard.xml").forEach { name ->
            val text = layout(name)
            assertFalse("$name statically claims «онлайн»", fakeOnline.containsMatchIn(text))
        }
    }

    @Test
    fun `dashboard exposes no stale status view id`() {
        // R.id.status existed only to carry the fake «Марс онлайн» string; no code
        // ever verified it. It must stay gone so nothing re-anchors a static claim.
        assertFalse(layout("activity_dashboard.xml").contains("@+id/status\""))
    }

    @Test
    fun `chat header keeps only data-driven status views`() {
        val text = layout("activity_tv_chat.xml")
        // The live status strip (gone by default, filled from real stream events).
        assertTrue(text.contains("@+id/statusText"))
        assertTrue(text.contains("@+id/contextText"))
        // The decorative always-green dot next to the title is gone.
        assertFalse(text.contains("ic_status_dot"))
    }
}
