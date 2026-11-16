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

    /**
     * The model the USER deliberately picked in the picker. It lives longer than any
     * session: server answers ([setSessionModel]) never touch it, so
     * `explicit_model_pick=true` keeps going out with every following `chat/start`
     * until the user picks another model or clears it ([clearExplicit]).
     *
     * Why it must be a field of its own: `sessionModel` is server truth and gets
     * rewritten on every session load / first-turn response. Deriving the explicit
     * flag from it meant the first server answer wiped the pick and the server then
     * "repaired" the turn back to the profile default (haiku) — the owner's bug.
     */
    private var userPick: ModelChoice? = current?.takeIf { it.explicitPick }

    /** The server-authoritative model used by the currently opened session. */
    private var sessionModel: ModelChoice? = null

    /**
     * The server reported the model of a session (session detail, `chat/start`
     * response). This is session truth, NOT the user's choice: an explicit pick is
     * deliberately preserved, otherwise the server sees `explicit_model_pick=false`
     * and repairs the model back to its default.
     */
    fun setSessionModel(modelId: String?, providerId: String?) {
        sessionModel = modelId?.takeIf { it.isNotBlank() }?.let { ModelChoice(it, providerId, explicitPick = false) }
    }

    /**
     * Binds the model of a chat the user OPENED (drawer tap, reload, offline copy).
     *
     * Unlike [setSessionModel] this is not "the server said something": opening an
     * existing dialog means its model is the truth for display and for the wire,
     * exactly like in the WebUI. A pick remembered from another chat (or restored
     * from SharedPreferences days ago) must NOT keep the chip on a foreign model,
     * so the explicit pick is dropped ([clearExplicit]) and [current] is moved onto
     * the session model. A blank [modelId] means "this chat has no model yet" — the
     * previous behaviour ([setSessionModel]) applies and nothing is reset.
     *
     * The canonical catalog row (id + providerId) is used when [lastCatalog] already
     * knows this model, so a bare `claude-sonnet-5.5` becomes
     * `@custom:plusvibe-api:claude-sonnet-5.5` with its provider and the chip shows
     * the human `name` instead of a raw id.
     */
    fun bindOpenedSessionModel(modelId: String?, providerId: String?) {
        val id = modelId?.trim()?.takeIf { it.isNotEmpty() }
        if (id == null) {
            setSessionModel(modelId, providerId)
            return
        }
        val row = findCatalogModel(id, providerId)
        val choice = ModelChoice(
            modelId = row?.id ?: id,
            providerId = row?.providerId ?: providerId,
            explicitPick = false,
        )
        sessionModel = choice
        // Old global pick is not this chat's model: drop it (also from persistence).
        clearExplicit()
        current = choice
    }

    /**
     * Catalog row for a model identity that may come in any of the three live wire
     * shapes: the catalog id itself (`@custom:plusvibe-api:claude-sonnet-5.5`), the
     * bare model id the session detail returns (`claude-sonnet-5.5`) or the
     * `vendor/model` form (`anthropic/claude-sonnet-5.5`). Mirrors the WebUI
     * `_findModelInDropdown`, so the phone and the browser agree on one row.
     *
     * Order: exact id (respecting [providerId]) -> `@provider:model` for
     * `custom*` providers -> normalized match (leading `@provider:`/`vendor/`
     * stripped, lowercase, `-` treated as `.`), preferring the same provider.
     * Returns null when the catalog is unknown or carries no such model — the
     * caller then shows the raw id instead of inventing a name.
     */
    fun findCatalogModel(modelId: String, providerId: String? = null): ModelInfo? {
        val id = modelId.trim()
        if (id.isEmpty()) return null
        val rows = lastCatalog?.models.orEmpty()
        if (rows.isEmpty()) return null
        val provider = providerId?.trim()?.takeIf { it.isNotEmpty() }

        rows.firstOrNull { it.id.equals(id, ignoreCase = true) && (provider == null || it.providerId == null || it.providerId.equals(provider, ignoreCase = true)) }
            ?.let { return it }
        if (provider != null && provider.startsWith("custom")) {
            rows.firstOrNull { it.id.equals("@$provider:$id", ignoreCase = true) }?.let { return it }
        }
        val normalized = normalizeModelId(id)
        val sameId = rows.filter { normalizeModelId(it.id) == normalized }
        return sameId.firstOrNull { provider != null && it.providerId.equals(provider, ignoreCase = true) }
            ?: sameId.firstOrNull()
    }

    /**
     * True when the picker row [model] is the model of [choice] (defaults to the
     * effective choice). Used for the `checked` row of the picker: a plain
     * `model.id == choice.modelId` missed every session whose server id was the bare
     * model name while the catalog row is `@provider:model`, leaving nothing checked.
     */
    fun matchesCatalogModel(model: ModelInfo, choice: ModelChoice? = null): Boolean {
        val target = choice ?: wireChoice() ?: return false
        if (model.id == target.modelId) return true
        findCatalogModel(target.modelId, target.providerId)?.let { return it.id == model.id }
        return normalizeModelId(model.id) == normalizeModelId(target.modelId)
    }

    fun clearSessionModel() { sessionModel = null }

    /**
     * «Как у сессии»: drops the explicit pick (also from persistence), so the wire
     * falls back to the server's session model with `explicit_model_pick=false`.
     */
    fun clearExplicit() {
        userPick = null
        storage.clear()
        current = sessionModel ?: current?.copy(explicitPick = false)
    }

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
        // The pick is remembered separately from the session model: no server answer
        // may turn it into `explicitPick=false` and lose `explicit_model_pick`.
        // [sessionModel] is left untouched on purpose — it only ever holds server
        // truth (`explicitPick=false`), so nothing can resurrect a stale explicit flag.
        userPick = choice
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

    /** Header-button label from the effective wire choice (explicit pick, else session model). */
    fun buttonLabel(): String? {
        val choice = wireChoice() ?: return null
        // Catalog lookup tolerates the wire shapes (`claude-sonnet-5.5`,
        // `anthropic/claude-sonnet-5.5`) — a bare `id ==` check showed the raw id.
        val row = findCatalogModel(choice.modelId, choice.providerId)
        return row?.name?.takeIf { it.isNotBlank() } ?: choice.modelId
    }

    fun displayedChoice(): ModelChoice? = wireChoice()

    /**
     * THE choice for the wire (`POST /api/chat/start` model fields) and, by
     * delegation, for display. Order of authority:
     *
     * 1. the user's explicit pick ([select]) — it keeps `explicitPick=true` so the
     *    server honors a cross-family model instead of repairing it to the profile
     *    default;
     * 2. otherwise the server-authoritative session model ([setSessionModel]);
     * 3. otherwise the local [current] (persisted pick, else adopted server default).
     *
     * Null means "no usable identity": send chat/start WITHOUT model fields so the
     * server routes with its own session/configured default instead of a client-side
     * guess.
     */
    fun wireChoice(): ModelChoice? = userPick ?: sessionModel ?: current

    /**
     * Model identity normalization shared with the WebUI `_findModelInDropdown`:
     * drop the leading `@provider:` prefix (`@custom:plusvibe-api:model`), drop the
     * leading `vendor/` (`anthropic/model`), lowercase and treat `-` as `.`
     * (`claude-sonnet-5.5` == `claude.sonnet.5.5`). Only used for MATCHING — the
     * wire always carries the catalog id.
     */
    private fun normalizeModelId(raw: String): String =
        raw.trim()
            .replace(Regex("^@([^:]+:)+"), "")
            .replace(Regex("^[^/]+/"), "")
            .lowercase()
            .replace('-', '.')

    private fun entryLabel(model: ModelInfo): String {
        val name = model.name?.takeIf { it.isNotBlank() } ?: model.id
        val provider = model.provider?.takeIf { it.isNotBlank() }
        return if (provider != null && provider != name) "$name · $provider" else name
    }
}
