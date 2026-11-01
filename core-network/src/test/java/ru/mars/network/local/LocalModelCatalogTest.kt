package ru.mars.network.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор каталога моделей провайдера.
 *
 * Тела ответов здесь взяты из РЕАЛЬНОГО ответа PlusVibe (`GET /models`, проверено
 * curl'ом при разработке), а не придуманы: именно на этой форме выяснилось, что
 * `reasoning_controls` есть лишь у части моделей, а у дефолтной `claude-haiku-5.5`
 * отсутствует. Если бы разбор считал поддержку reasoning по имени модели, переключатель
 * показывался бы там, где провайдер его игнорирует — мёртвая кнопка.
 */
class LocalModelCatalogTest {

    @Test
    fun parsesRealProviderShape() {
        val json = """
        {"object":"list","data":[
          {"id":"claude-haiku-5.5","object":"model","owned_by":"plusvibe","type":"chat",
           "architecture":{"input_modalities":["text","image"],"output_modalities":["text"]},
           "endpoints":["/v1/chat/completions"],
           "context_length":1000000,"context_window":1000000,
           "max_input_tokens":1000000,"max_output_tokens":128000,
           "top_provider":{"context_length":1000000,"max_completion_tokens":128000}}
        ]}
        """.trimIndent()

        val models = LocalModelCatalog.parse(json)
        assertEquals(1, models.size)
        val model = models.single()
        assertEquals("claude-haiku-5.5", model.id)
        assertEquals(1_000_000, model.contextWindow)
        assertEquals(128_000, model.maxOutputTokens)
        assertTrue("модальности сохраняются", model.inputModalities.contains("text"))
    }

    @Test
    fun haikuDoesNotSupportReasoning() {
        // Ключевой факт живого каталога: у haiku-5.5 поля reasoning_controls НЕТ.
        val json = """{"data":[{"id":"claude-haiku-5.5","context_window":1000000}]}"""
        val model = LocalModelCatalog.parse(json).single()
        assertFalse(
            "без reasoning_controls переключатель показывать нельзя",
            model.supportsReasoningEffort,
        )
    }

    @Test
    fun modelWithReasoningControlsIsDetected() {
        val json = """{"data":[{"id":"gpt-6-astra","context_window":400000,
            "reasoning_controls":["reasoning_effort"]}]}"""
        val model = LocalModelCatalog.parse(json).single()
        assertTrue(model.supportsReasoningEffort)
    }

    @Test
    fun emptyReasoningControlsMeansNoSupport() {
        // Пустой массив — это «поля нет», как у claude-fable-5 в живом каталоге.
        val json = """{"data":[{"id":"claude-fable-5","reasoning_controls":[]}]}"""
        assertFalse(LocalModelCatalog.parse(json).single().supportsReasoningEffort)
    }

    @Test
    fun unknownReasoningControlIsNotSupport() {
        // Чужой контрол (не reasoning_effort) не даёт права рисовать наш селектор.
        val json = """{"data":[{"id":"x","reasoning_controls":["verbosity"]}]}"""
        assertFalse(LocalModelCatalog.parse(json).single().supportsReasoningEffort)
    }

    @Test
    fun bareArrayIsAccepted() {
        val json = """[{"id":"m1","context_window":8000}]"""
        val models = LocalModelCatalog.parse(json)
        assertEquals(1, models.size)
        assertEquals(8_000, models.single().contextWindow)
    }

    @Test
    fun brokenItemDoesNotKillCatalog() {
        // Один битый элемент не должен лишать выбора остальные модели.
        val json = """{"data":[{"no_id":true},{"id":"good","context_window":32000}]}"""
        val models = LocalModelCatalog.parse(json)
        assertEquals(1, models.size)
        assertEquals("good", models.single().id)
    }

    @Test
    fun brokenJsonGivesEmptyList() {
        assertTrue(LocalModelCatalog.parse("{не json").isEmpty())
        assertTrue(LocalModelCatalog.parse("").isEmpty())
    }

    @Test
    fun missingWindowIsNull() {
        val json = """{"data":[{"id":"noctx"}]}"""
        val model = LocalModelCatalog.parse(json).single()
        assertNull("окно не сообщено — не выдумывать", model.contextWindow)
    }

    @Test
    fun negativeAndZeroWindowsAreTreatedAsUnknown() {
        val json = """{"data":[{"id":"a","context_window":0,"context_length":-5}]}"""
        assertNull(LocalModelCatalog.parse(json).single().contextWindow)
    }
}
