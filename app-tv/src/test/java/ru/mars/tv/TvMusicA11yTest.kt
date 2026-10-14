package ru.mars.tv

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MUS-T1: the music screen's status line must announce itself to TalkBack —
 * mobile/TV parity with the section screens, whose status views already declare
 * `android:accessibilityLiveRegion="polite"` (projects, memory, insights, spaces,
 * kanban, todos, cron, skills). Without it a D-pad/TalkBack user only hears the
 * load/error/empty transitions after manually re-focusing the screen.
 *
 * Layout-level guard (the project has no instrumented TV layer): the raw XML is
 * parsed like in [TvMusicFocusNavigationTest]; any removal of the live region —
 * here or on a sibling section screen — breaks the test.
 */
class TvMusicA11yTest {

    private val layoutDir = File(System.getProperty("user.dir"), "src/main/res/layout")

    private fun layout(name: String): String = File(layoutDir, name).readText()

    /** Raw XML block of the element with the given id (same approach as the focus test). */
    private fun blockOf(xml: String, id: String): String {
        val re = Regex("""<\w+[^>]*android:id="@\+id/$id"[\s\S]*?/>""")
        return re.find(xml)?.value ?: error("view @$id not found in layout")
    }

    @Test fun musicStateTextIsPoliteLiveRegion() {
        val block = blockOf(layout("activity_tv_music.xml"), "stateText")
        assertTrue(
            "stateText on the TV music screen must be a polite live region (MUS-T1)",
            block.contains("""android:accessibilityLiveRegion="polite"""),
        )
    }

    @Test fun everySectionScreenStatusViewKeepsLiveRegionParity() {
        // The parity set: section screens with a dedicated status/state line.
        val screens = mapOf(
            "activity_tv_music.xml" to "stateText",
            "activity_tv_projects.xml" to "statusView",
            "activity_tv_memory.xml" to "statusView",
            "activity_tv_insights.xml" to "statusView",
            "activity_tv_spaces.xml" to "statusView",
            "activity_tv_kanban.xml" to "statusView",
            "activity_tv_todos.xml" to "statusView",
            "activity_tv_cron.xml" to "statusView",
            "activity_tv_skills.xml" to "statusView",
        )
        for ((file, id) in screens) {
            val block = blockOf(layout(file), id)
            assertTrue(
                "$file @$id lost its polite live region — section screens must announce status alike",
                block.contains("""android:accessibilityLiveRegion="polite"""),
            )
        }
    }
}
