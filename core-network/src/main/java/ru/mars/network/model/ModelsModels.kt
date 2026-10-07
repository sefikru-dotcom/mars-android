package ru.mars.network.model

/** Models exposed by the WebUI provider configuration. */
data class ModelInfo(
    val id: String,
    val name: String? = null,
    val provider: String? = null,
    val contextLength: Int? = null,
)

data class ModelsResponse(
    val activeProvider: String? = null,
    val defaultModel: String? = null,
    val models: List<ModelInfo> = emptyList(),
)
