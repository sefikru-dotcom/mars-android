package ru.mars.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.ModelsRepository

class ModelsRepositoryTest {
    @Test
    fun loadsModelsWithBearerToken() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"activeProvider\":\"openai\",\"defaultModel\":\"gpt-4o\",\"models\":[{\"id\":\"gpt-4o\",\"name\":\"GPT-4o\",\"provider\":\"openai\"}]}"))
            val credential = ConsumedDeviceCredential("token", "Bearer", "device", emptyList(), 1)
            val result = ModelsRepository().getModels(server.url("/").toString(), credential)
            assertEquals("openai", result.activeProvider)
            assertEquals("gpt-4o", result.models.single().id)
            val request = server.takeRequest()
            assertEquals("/api/models", request.path)
            assertEquals("Bearer token", request.getHeader("Authorization"))
        }
    }
}
