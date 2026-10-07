package ru.mars.network.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.ModelGroup
import ru.mars.network.model.ModelInfo
import ru.mars.network.model.ModelsResponse

/**
 * GET /api/models — grouped provider catalog served by the WebUI backend:
 * `{active_provider, default_model, configured_model_badges, groups[], aliases}`,
 * where each group is `{provider, provider_id, models[], extra_models?}` and each
 * model row is `{id, label}`. The repository keeps a flat [ModelsResponse.models]
 * view (groups[].models + groups[].extra_models) so UI pickers stay simple.
 */
class ModelsRepository(private val client: OkHttpClient = OkHttpClient()) {
    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val moshi = Moshi.Builder().build()

    suspend fun getModels(baseUrl: String, credential: ConsumedDeviceCredential): ModelsResponse =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(baseUrl.toHttpUrl().newBuilder().addPathSegments("api/models").build())
                .header("Authorization", "Bearer ${credential.accessToken}")
                .get().build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use ModelsResponse()
                    val root = parseMap(response.body?.string().orEmpty()) ?: return@use ModelsResponse()
                    buildResponse(root)
                }
            } catch (_: Exception) { ModelsResponse() }
        }

    private fun buildResponse(root: Map<String, Any?>): ModelsResponse {
        val groups = (root["groups"] as? List<*>).orEmpty().mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val providerLabel = string(map, "provider")
            val providerId = string(map, "provider_id", "providerId")
            ModelGroup(
                provider = providerLabel,
                providerId = providerId,
                models = modelRows(map["models"], providerLabel, providerId),
                extraModels = modelRows(map["extra_models"], providerLabel, providerId),
            )
        }
        val flat = if (groups.isNotEmpty()) {
            groups.flatMap { it.allModels }
        } else {
            // Tolerance for older/simplified payloads with a bare top-level "models" list.
            modelRows(root["models"], string(root, "active_provider", "activeProvider"), null)
        }
        val aliases = (root["aliases"] as? Map<*, *>)?.mapNotNull { (k, v) ->
            val key = k?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val value = v?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            key to value
        }?.toMap().orEmpty()
        return ModelsResponse(
            activeProvider = string(root, "active_provider", "activeProvider"),
            defaultModel = string(root, "default_model", "defaultModel"),
            models = flat,
            groups = groups,
            aliases = aliases,
        )
    }

    private fun modelRows(raw: Any?, providerLabel: String?, providerId: String?): List<ModelInfo> =
        (raw as? List<*>).orEmpty().mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val id = string(map, "id", "model", "name") ?: return@mapNotNull null
            ModelInfo(
                id = id,
                name = string(map, "label", "name", "display_name", "displayName"),
                provider = providerLabel ?: string(map, "provider"),
                providerId = providerId ?: string(map, "provider_id", "providerId"),
                contextLength = (map["context_length"] as? Number)?.toInt() ?: (map["contextLength"] as? Number)?.toInt(),
            )
        }

    private fun parseMap(json: String): Map<String, Any?>? =
        try { moshi.adapter<Map<String, Any?>>(mapType).fromJson(json) } catch (_: Exception) { null }

    private fun string(map: Map<*, *>, vararg keys: String) =
        keys.firstNotNullOfOrNull { map[it]?.toString()?.takeIf(String::isNotBlank) }
}
