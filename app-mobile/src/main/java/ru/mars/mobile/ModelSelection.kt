package ru.mars.mobile

import android.content.SharedPreferences
import ru.mars.network.model.ModelInfo
import ru.mars.network.model.ModelsResponse

/**
 * A selected model for `POST /api/chat/start`. Both fields come from the live
 * `GET /api/models` catalog ([ModelInfo.id] + the group's `provider_id`) or
 * from the server's own `default_model`/`active_provider` — never from a
 * hard-coded client-side list.
 *
 * [explicitPick] mirrors the web client (`static/messages.js`): true only when
 * the user actively chose this model in the picker, which tells the server to
 * record the deliberate pick (#5979) instead of repairing the session back to
 * its stored default.
 */
data class ModelChoice(
    val modelId: String,
    val providerId: String?,
    val explicitPick: Boolean
)

/** Persistence port for [ModelChoice]; the app implements it with SharedPreferences. */
interface ModelChoiceStorage {
    fun load(): ModelChoice?
    fun save(choice: ModelChoice)
    fun clear()
}

/** SharedPreferences-backed [ModelChoiceStorage]. A restored pick is always a user pick. */
class PrefsModelChoiceStorage(private val prefs: SharedPreferences) : ModelChoiceStorage {
    override fun load(): ModelChoice? {
        val id = prefs.getString(KEY_MODEL_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val providerId = prefs.getString(KEY_PROVIDER_ID, null)?.takeIf { it.isNotBlank() }
        return ModelChoice(id, providerId, explicitPick = true)
    }

    override fun save(choice: ModelChoice) {
        prefs.edit()
            .putString(KEY_MODEL_ID, choice.modelId)
            .putString(KEY_PROVIDER_ID, choice.providerId)
            .apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_MODEL_ID).remove(KEY_PROVIDER_ID).apply()
    }

    private companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_PROVIDER_ID = "model_provider_id"
    }
}

/** One picker row: a real catalog entry plus its display label. */
data class ModelPickerEntry(val model: ModelInfo, val label: String)

/**
 * Domain state for model selection (UI -> state/domain -> network): the
 * Activity fetches the catalog via [ru.mars.network.repository.ModelsRepository]
 * and hands the parsed response here; this class resolves, labels and persists
 * the choice and knows nothing about Android views.
 *
 * Deliberately has NO fallback model list: when the catalog is unavailable the
 * effective choice stays null and `chat/start` is sent without model fields, so
 * the server routes with the session/configured default instead of a guess.
 */
class ModelSelection(private val storage: ModelChoiceStorage) {

    /** Effective choice for the next `chat/start`: persisted user pick, else server default. */
    var current: ModelChoice? = storage.load()
        private set

    private var lastCatalog: ModelsResponse? = null
    private var sessionModel: ModelChoice? = null

    /** The server-authoritative model used by the currently opened session. */
    fun setSessionModel(modelId: String?, providerId: String?) {
        sessionModel = modelId?.takeIf { it.isNotBlank() }?.let { ModelChoice(it, providerId, explicitPick = false) }
    }

    fun clearSessionModel() { sessionModel = null }

    /** Picker rows in server order, labeled `name · provider` from real catalog data. */
    fun pickerEntries(catalog: ModelsResponse): List<ModelPickerEntry> {
        lastCatalog = catalog
        return catalog.models.map { ModelPickerEntry(it, entryLabel(it)) }
    }

    /** Persists a user pick from the catalog. Returns null for a blank model id. */
    fun select(model: ModelInfo): ModelChoice? {
        if (model.id.isBlank()) return null
        val choice = ModelChoice(model.id, model.providerId, explicitPick = true)
        current = choice
        sessionModel = choice
        storage.save(choice)
        return choice
    }

    /**
     * Resolves the effective choice after a catalog load: a persisted user pick
     * wins; otherwise the server's `default_model` is adopted with the provider
     * of its catalog row (falling back to `active_provider`). Returns null when
     * the catalog carries no usable default — no invented model names.
     */
    fun resolveEffective(catalog: ModelsResponse): ModelChoice? {
        lastCatalog = catalog
        current?.let { return it }
        val defaultId = catalog.defaultModel?.takeIf { it.isNotBlank() } ?: return null
        val row = catalog.models.firstOrNull { it.id == defaultId }
        val choice = ModelChoice(defaultId, row?.providerId ?: catalog.activeProvider, explicitPick = false)
        current = choice
        return choice
    }

    /** Header-button label from the effective server session, then the local picker choice. */
    fun buttonLabel(): String? {
        val choice = wireChoice() ?: return null
        val row = lastCatalog?.models?.firstOrNull { it.id == choice.modelId }
        return row?.name?.takeIf { it.isNotBlank() } ?: choice.modelId
    }

    fun displayedChoice(): ModelChoice? = wireChoice()

    /**
     * THE choice for the wire (`POST /api/chat/start` model fields) and, by
     * delegation, for display: the server-authoritative session model when one
     * is bound ([setSessionModel]), otherwise the local [current] (persisted
     * user pick, else adopted server default). Null means "no usable identity":
     * send chat/start WITHOUT model fields so the server routes with its own
     * session/configured default instead of a client-side guess.
     */
    fun wireChoice(): ModelChoice? = sessionModel ?: current

    private fun entryLabel(model: ModelInfo): String {
        val name = model.name?.takeIf { it.isNotBlank() } ?: model.id
        val provider = model.provider?.takeIf { it.isNotBlank() }
        return if (provider != null && provider != name) "$name · $provider" else name
    }
}
