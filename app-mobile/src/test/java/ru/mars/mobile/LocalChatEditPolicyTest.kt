package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.local.LocalRole

/**
 * Правка своей реплики: чистые правила.
 *
 * Проверяется именно то, из-за чего фича ломается молча: сколько строк уйдёт под нож,
 * кому вообще можно править и не предлагается ли правка во время хода. Лента и диалог
 * в тесте — настоящие [LocalChatItem], а не заглушки: правила читают роль, инструмент
 * и флаг стрима.
 */
class LocalChatEditPolicyTest {

    private fun user(id: String, text: String) =
        LocalChatItem(id = id, role = LocalRole.USER, text = text)

    private fun assistant(id: String, text: String, streaming: Boolean = false) =
        LocalChatItem(id = id, role = LocalRole.ASSISTANT, text = text, streaming = streaming)

    private val feed = listOf(
        user("u1", "вопрос один"),
        assistant("a1", "ответ один"),
        user("u2", "вопрос два"),
        assistant("a2", "ответ два"),
    )

    @Test
    fun `правка середины требует удалить хвост`() {
        val plan = LocalChatEditPolicy.plan(feed, "u1", "исправленный вопрос")
        assertNotNull(plan)
        // После u1 остались a1, u2, a2 — их и удаляем.
        assertEquals(3, plan!!.dropAfter)
        assertEquals("u1", plan.messageId)
        assertEquals("исправленный вопрос", plan.newText)
        assertFalse(plan.isNoop)
    }

    @Test
    fun `правка последней реплики ничего не удаляет`() {
        val plan = LocalChatEditPolicy.plan(feed, "u2", "новый вопрос")
        assertNotNull(plan)
        // После u2 только ответ — он исчезнет, но это перезапрос, а не «удаление истории».
        assertEquals(1, plan!!.dropAfter)
    }

    @Test
    fun `правка реплики в конце ленты без ответа даёт ноль удалений`() {
        val onlyQuestion = listOf(user("u1", "вопрос"), assistant("a1", "ответ"), user("u2", "ещё"))
        val plan = LocalChatEditPolicy.plan(onlyQuestion, "u2", "исправлено")
        assertEquals(0, plan!!.dropAfter)
        assertEquals(LocalChatEditPolicy.REGENERATE_HINT, LocalChatEditPolicy.hint(plan))
    }

    @Test
    fun `подсказка считает сообщения по-русски`() {
        val one = LocalChatEditPolicy.plan(feed, "u2", "текст")!!
        assertEquals("Будет удалено 1 сообщение", LocalChatEditPolicy.hint(one))

        val three = LocalChatEditPolicy.plan(feed, "u1", "текст")!!
        assertEquals("Будет удалено 3 сообщения", LocalChatEditPolicy.hint(three))
    }

    @Test
    fun `одиннадцать сообщений — это сообщений, а не сообщение`() {
        // Классическая ошибка русской плюрализации: 11 попадает в «сообщение».
        val items = ArrayList<LocalChatItem>()
        items += user("u1", "вопрос")
        repeat(11) { index -> items += assistant("a$index", "ответ $index") }
        val plan = LocalChatEditPolicy.plan(items, "u1", "текст")!!
        assertEquals("Будет удалено 11 сообщений", LocalChatEditPolicy.hint(plan))
    }

    @Test
    fun `во время хода править нельзя`() {
        assertFalse(LocalChatEditPolicy.canEdit(user("u1", "текст"), streaming = true))
        assertTrue(LocalChatEditPolicy.canEdit(user("u1", "текст"), streaming = false))
    }

    @Test
    fun `чужая реплика не правится`() {
        assertFalse(LocalChatEditPolicy.canEdit(assistant("a1", "ответ"), streaming = false))
    }

    @Test
    fun `пустая реплика не правится`() {
        assertFalse(LocalChatEditPolicy.canEdit(user("u1", "   "), streaming = false))
    }

    @Test
    fun `исчезнувшая реплика даёт null`() {
        assertNull(LocalChatEditPolicy.plan(feed, "нет-такого", "текст"))
    }

    @Test
    fun `пустой текст не даёт плана`() {
        // Не «план с isNoop», а именно null: пустую реплику нельзя ни отправить, ни
        // сохранить — правка не должна уметь стирать своё сообщение.
        assertNull(LocalChatEditPolicy.plan(feed, "u1", "   "))
        assertFalse(LocalChatEditPolicy.canApply("   "))
        assertTrue(LocalChatEditPolicy.canApply("  вопрос  "))
    }

    @Test
    fun `isNoop ловит пустой план, пришедший извне`() {
        // Тип публичный, и такой план может собрать вызывающий код, а не только plan().
        val empty = LocalChatEditPlan(messageId = "u1", newText = "", keepBefore = 0, dropAfter = 2)
        assertTrue(empty.isNoop)
    }

    @Test
    fun `нормализация срезает края`() {
        assertEquals("вопрос", LocalChatEditPolicy.normalize("  вопрос \n"))
    }

    @Test
    fun `правка доступна там же, где её обещает лист действий`() {
        // Согласованность двух мест: пункт меню и политика не должны разъехаться,
        // иначе лист предложит «Изменить», а нажатие ничего не сделает.
        val item = user("u1", "текст")
        assertTrue(LocalChatActions.canEdit(item))
        assertTrue(LocalChatAction.EDIT in LocalChatActions.available(item, isLastAssistant = false))
        assertTrue(LocalChatEditPolicy.canEdit(item, streaming = false))
    }
}
