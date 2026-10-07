package ru.mars.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.ModelsRepository

class ModelsRepositoryTest {
    private val credential = ConsumedDeviceCredential("token", "Bearer", "device", emptyList(), 1)

    /**
     * Real GET /api/models payload captured from the WebUI backend
     * (active_provider/default_model/configured_model_badges/groups[].models+extra_models/aliases).
     */
    private fun realFixture(): String =
        javaClass.classLoader!!.getResource("fixtures/api_models.json")!!.readText()

    @Test
    fun loadsRealGroupedPayloadWithBearerToken() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(realFixture()))
            val result = ModelsRepository().getModels(server.url("/").toString(), credential)

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/models", request.path)
            assertEquals("Bearer token", request.getHeader("Authorization"))

            assertEquals("custom:vertex-gemini", result.activeProvider)
            assertEquals("gemini-3.8-flash", result.defaultModel)
            assertEquals(5, result.groups.size)

            val active = result.groups.first()
            assertEquals("Vertex Gemini · Талант", active.provider)
            assertEquals("custom:vertex-gemini", active.providerId)
            assertEquals("gemini-3.8-flash", active.models.first().id)
            assertEquals("gemini-3.8-flash", active.models.first().name)

            // Flat UI view: groups[].models + groups[].extra_models in server order.
            val expectedFlat = result.groups.sumOf { it.models.size + it.extraModels.size }
            assertEquals(expectedFlat, result.models.size)
            assertTrue(result.models.map { it.id }.contains("@custom:plusvibe-api:gpt-6.1-sol"))
        }
    }

    @Test
    fun parsesExtraModelsAndAliases() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """
                {
                  "active_provider": "custom:plusvibe-api",
                  "default_model": "@custom:plusvibe-api:gpt-6.1-sol",
                  "configured_model_badges": {},
                  "groups": [
                    {
                      "provider": "PlusVibe API",
                      "provider_id": "custom:plusvibe-api",
                      "models": [{"id": "@custom:plusvibe-api:gpt-6.1-sol", "label": "GPT 6.1 SOL"}],
                      "extra_models": [{"id": "@custom:plusvibe-api:grok-4.7", "label": "Grok 4.7"}]
                    }
                  ],
                  "aliases": {"gpt": "@custom:plusvibe-api:gpt-6.1-sol"}
                }
                """.trimIndent()
            ))
            val result = ModelsRepository().getModels(server.url("/").toString(), credential)
            assertEquals("custom:plusvibe-api", result.activeProvider)
            assertEquals("@custom:plusvibe-api:gpt-6.1-sol", result.defaultModel)
            val group = result.groups.single()
            assertEquals(listOf("@custom:plusvibe-api:gpt-6.1-sol"), group.models.map { it.id })
            assertEquals(listOf("@custom:plusvibe-api:grok-4.7"), group.extraModels.map { it.id })
            assertEquals(
                listOf("@custom:plusvibe-api:gpt-6.1-sol", "@custom:plusvibe-api:grok-4.7"),
                result.models.map { it.id }
            )
            assertEquals("PlusVibe API", result.models.first().provider)
            assertEquals("custom:plusvibe-api", result.models.first().providerId)
            assertEquals("GPT 6.1 SOL", result.models.first().name)
            assertEquals(mapOf("gpt" to "@custom:plusvibe-api:gpt-6.1-sol"), result.aliases)
        }
    }

    @Test
    fun toleratesLegacyFlatModelsPayload() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"activeProvider\":\"openai\",\"defaultModel\":\"gpt-4o\",\"models\":[{\"id\":\"gpt-4o\",\"name\":\"GPT-4o\",\"provider\":\"openai\"}]}"))
            val result = ModelsRepository().getModels(server.url("/").toString(), credential)
            assertEquals("openai", result.activeProvider)
            assertEquals("gpt-4o", result.models.single().id)
            assertTrue(result.groups.isEmpty())
        }
    }

    @Test
    fun unsuccessfulResponseYieldsEmptyResult() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"Authentication required\"}"))
            val result = ModelsRepository().getModels(server.url("/").toString(), credential)
            assertEquals(emptyList<Any>(), result.models)
            assertEquals(null, result.activeProvider)
        }
    }
}
