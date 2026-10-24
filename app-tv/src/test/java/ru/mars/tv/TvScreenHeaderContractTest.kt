package ru.mars.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Заголовки экранов разделов не должны отдавать всю ширину статусной строке.
 *
 * Причина существования: живые измерения на Mi TV показали два разных отказа
 * одной природы. В чате заголовок с `layout_width=0dp` + `layout_weight=1`
 * конкурировал с четырьмя крупными кнопками и сжимался до 38dp — одна буква
 * в строке. У «Скиллов» заголовок был уже на `wrap_content`, но `statusView`
 * с длинным текстом («Обновлено только что · Скиллов: 113 · Вызовов: 8142…»)
 * забирал всю ширину ряда, и заголовка не оставалось вовсе — 0dp, исчез
 * с экрана, хотя `maxLines` и `ellipsize` стояли.
 *
 * Правило, которое держит тест: ширину в шапке делят так, чтобы статус
 * ужимался первым, а заголовок сохранял минимальную ширину.
 *  - заголовок — wrap_content (ширина по тексту, но не меньше tv_title_min_w);
 *  - статус — 0dp + weight=1 (забирает ровно остаток и обрезается ellipsize).
 *
 * Как и остальные тесты модуля — обычный JVM-тест по исходникам, без Robolectric.
 */
class TvScreenHeaderContractTest {

    private val moduleDir = File(System.getProperty("user.dir") ?: ".")
    private val layoutDir = File(moduleDir, "src/main/res/layout")

    /** Экраны разделов, где есть шапка со статусом (музыка и дашборд устроены иначе). */
    private val screens = listOf(
        "activity_tv_projects.xml", "activity_tv_todos.xml", "activity_tv_cron.xml",
        "activity_tv_kanban.xml", "activity_tv_insights.xml", "activity_tv_spaces.xml",
        "activity_tv_profiles.xml", "activity_tv_memory.xml", "activity_tv_skills.xml",
    )

    private fun block(source: String, marker: String): String? {
        val at = source.indexOf(marker)
        if (at < 0) return null
        val start = source.lastIndexOf("<TextView", at)
        val end = source.indexOf("/>", at) + 2
        return source.substring(start, end)
    }

    @Test
    fun `screen titles keep their width instead of yielding to the status line`() {
        screens.forEach { screen ->
            val src = File(layoutDir, screen).readText()
            val title = block(src, "android:minWidth=\"@dimen/tv_title_min_w\"")
            assertTrue("$screen: нет заголовка с minWidth", title != null)
            assertFalse(
                "$screen: заголовок не должен растягиваться весом — иначе статус его выдавит",
                title!!.contains("android:layout_weight"),
            )

            val status = block(src, "android:id=\"@+id/statusView\"")
            assertTrue("$screen: нет statusView", status != null)
            assertTrue(
                "$screen: statusView обязан забирать остаток (0dp + weight), иначе съест заголовок",
                status!!.contains("android:layout_width=\"0dp\"") &&
                    status.contains("android:layout_weight"),
            )
        }
    }

    @Test
    fun `chat header title is not weight-starved`() {
        // В чате заголовок обязан быть без веса: четыре кнопки уже отнимают ширину,
        // и вес снова схлопнет его до одной буквы (было 38dp).
        val chat = File(layoutDir, "activity_tv_chat.xml").readText()
        val title = block(chat, "android:id=\"@+id/chatTitle\"")
        assertTrue("в чате нет chatTitle", title != null)
        assertTrue("чат: заголовку нужна минимальная ширина", title!!.contains("@dimen/tv_title_min_w"))
        assertFalse("чат: заголовок не должен иметь weight", title.contains("android:layout_weight"))
        assertTrue("чат: заголовок в одну строку", title.contains("android:maxLines=\"1\""))
        assertTrue("чат: обрезка многоточием", title.contains("android:ellipsize=\"end\""))
    }
}
