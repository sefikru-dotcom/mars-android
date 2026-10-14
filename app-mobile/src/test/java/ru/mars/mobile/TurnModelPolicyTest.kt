package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Подпись фактической модели хода (волна C, фича A): серверное значение важнее
 * локального пикера, пустое = «не сообщено», форма — `provider/model` без дублей.
 */
class TurnModelPolicyTest {

    @Test
    fun `unknown model has no label - host keeps its fallback`() {
        assertNull(TurnModelPolicy.label(null, null))
        assertNull(TurnModelPolicy.label(null, "openai"))
        assertNull(TurnModelPolicy.label("", "openai"))
        assertNull(TurnModelPolicy.label("   ", "openai"))
    }

    @Test
    fun `model without provider is shown as-is (trimmed)`() {
        assertEquals("gpt-5.2", TurnModelPolicy.label("gpt-5.2", null))
        assertEquals("gpt-5.2", TurnModelPolicy.label("  gpt-5.2  ", "  "))
    }

    @Test
    fun `provider joins the model CLI-style`() {
        assertEquals("openai/gpt-5.2", TurnModelPolicy.label("gpt-5.2", "openai"))
        assertEquals("anthropic/claude-sonnet-4-5", TurnModelPolicy.label("claude-sonnet-4-5", "anthropic"))
    }

    @Test
    fun `custom transport prefix is stripped from the provider`() {
        assertEquals("plusvibe-api/qwen3.8-max", TurnModelPolicy.label("qwen3.8-max", "custom:plusvibe-api"))
        // Префикс, съевший всё содержимое, — провайдера нет, подпись без него.
        assertEquals("gpt-5.2", TurnModelPolicy.label("gpt-5.2", "custom:"))
        assertEquals("gpt-5.2", TurnModelPolicy.label("gpt-5.2", " custom: "))
    }

    @Test
    fun `provider already carried by the model id is not duplicated`() {
        assertEquals("openrouter/deepseek/deepseek-r1", TurnModelPolicy.label("openrouter/deepseek/deepseek-r1", "openrouter"))
        assertEquals("openai/gpt-5.2", TurnModelPolicy.label("openai/gpt-5.2", "OpenAI"))
        // Модель, совпадающая с провайдером, не превращается в «openai/openai».
        assertEquals("openai", TurnModelPolicy.label("openai", "OPENAI"))
    }

    @Test
    fun `values are delivered verbatim - case is preserved`() {
        assertEquals("NousResearch/Hermes-4", TurnModelPolicy.label("Hermes-4", "NousResearch"))
    }
}
