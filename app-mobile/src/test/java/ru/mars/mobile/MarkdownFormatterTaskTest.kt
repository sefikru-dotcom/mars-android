package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownFormatterTaskTest {

    @Test
    fun parseLines_detectsTaskItems() {
        val markdown = """
            - [ ] Pending task
            - [x] Completed task
            * [X] Upper completed task
            + [ ] Plus pending task
            - Normal bullet
        """.trimIndent()

        val lines = MarkdownFormatter.parseLines(markdown)
        assertEquals(5, lines.size)

        val task1 = lines[0] as RichLine.TaskItem
        assertFalse(task1.checked)

        val task2 = lines[1] as RichLine.TaskItem
        assertTrue(task2.checked)

        val task3 = lines[2] as RichLine.TaskItem
        assertTrue(task3.checked)

        val task4 = lines[3] as RichLine.TaskItem
        assertFalse(task4.checked)

        assertTrue(lines[4] is RichLine.Bullet)
    }
}
