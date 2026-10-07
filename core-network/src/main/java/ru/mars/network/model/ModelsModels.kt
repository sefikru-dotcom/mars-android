package ru.mars.network.model

/** A single model entry inside a provider group of the GET /api/models payload. */
data class ModelInfo(
    val id: String,
    val name: String? = null,
    val provider: String? = null,
    val providerId: String? = null,
    val contextLength: Int? = null,
)

/**
 * A provider group from GET /api/models.
 *
 * Real payload shape: `groups[].provider`, `groups[].provider_id`,
 * `groups[].models` (visible picker rows) and optional `groups[].extra_models`
 * (overflow rows kept for search/autocomplete).
 */
data class ModelGroup(
    val provider: String? = null,
    val providerId: String? = null,
    val models: List<ModelInfo> = emptyList(),
    val extraModels: List<ModelInfo> = emptyList(),
) {
    /** Visible + overflow rows, in server order. */
    val allModels: List<ModelInfo> get() = models + extraModels
}

/**
 * Parsed GET /api/models response.
 *
 * [models] is the convenient flat view for the UI: every group's `models` +
 * `extra_models` flattened in server order. [groups] keeps the grouped shape
 * and [aliases] the server-side `alias -> model id` map.
 */
data class ModelsResponse(
    val activeProvider: String? = null,
    val defaultModel: String? = null,
    val models: List<ModelInfo> = emptyList(),
    val groups: List<ModelGroup> = emptyList(),
    val aliases: Map<String, String> = emptyMap(),
)
