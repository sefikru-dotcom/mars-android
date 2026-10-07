package ru.mars.network.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.ModelInfo
import ru.mars.network.model.ModelsResponse

class ModelsRepository(private val client: OkHttpClient = OkHttpClient()) {
    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val moshi = Moshi.Builder().build()

    fun getModels(baseUrl: String, credential: ConsumedDeviceCredential): ModelsResponse {
        val request = Request.Builder()
            .url(baseUrl.toHttpUrl().newBuilder().addPathSegments("api/models").build())
            .header("Authorization", "Bearer ${credential.accessToken}")
            .get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return ModelsResponse()
                val root = moshi.adapter<Map<String, Any?>>(mapType).fromJson(response.body?.string().orEmpty()) ?: return ModelsResponse()
                val models = (root["models"] as? List<*>).orEmpty().mapNotNull { item ->
                    val map = item as? Map<*, *> ?: return@mapNotNull null
                    ModelInfo(
                        id = string(map, "id", "model", "name").orEmpty(),
                        name = string(map, "name", "display_name", "displayName"),
                        provider = string(map, "provider"),
                        contextLength = (map["context_length"] as? Number)?.toInt() ?: (map["contextLength"] as? Number)?.toInt(),
                    )
                }
                ModelsResponse(string(root, "activeProvider", "active_provider"), string(root, "defaultModel", "default_model"), models)
            }
        } catch (_: Exception) { ModelsResponse() }
    }

    private fun string(map: Map<*, *>, vararg keys: String) = keys.firstNotNullOfOrNull { map[it]?.toString()?.takeIf(String::isNotBlank) }
}
