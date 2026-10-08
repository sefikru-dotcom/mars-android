package ru.mars.mobile

import android.content.SharedPreferences
import ru.mars.network.model.ReasoningStatus

/**
 * An explicit user pick of the reasoning effort for `/api/reasoning`.
 *
 * [effort] null = "Авто" (Default: posts `effort:''`, clearing the override); any other
 * value is a server-offered level, normalized to lowercase. Like [ModelChoice], the pick
 * is only recorded when the user actively chose it — the server (not the client) resolves
 * the effective effort per turn, and `chat/start` never carries a reasoning field.
 */
data class ReasoningChoice(
    val effort: String?,
    val explicitPick: Boolean,
)

/** Persistence port for [ReasoningChoice]; the app implements it with SharedPreferences. */
interface ReasoningChoiceStorage {
    fun load(): ReasoningChoice?
    fun save(choice: ReasoningChoice)
    fun clear()
}

/** SharedPreferences-backed [ReasoningChoiceStorage]. A restored pick is always a user pick. */
class PrefsReasoningChoiceStorage(private val prefs: SharedPreferences) : ReasoningChoiceStorage {
    override fun load(): ReasoningChoice? {
        if (!prefs.contains(KEY_EFFORT)) return null
        return ReasoningChoice(prefs.getString(KEY_EFFORT, null)?.takeIf { it.isNotBlank() }, explicitPick = true)
    }

    override fun save(choice: ReasoningChoice) {
        prefs.edit().putString(KEY_EFFORT, choice.effort.orEmpty()).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_EFFORT).apply()
    }

    private companion object {
        const val KEY_EFFORT = "reasoning_effort"
    }
}

/** One selector row: a server-supported level (`null` = Авто/Default) plus its display label. */
data class ReasoningOption(val effort: String?, val label: String)

/**
 * Domain state of the reasoning-effort selector (UI -> state/domain -> network), mirroring
 * [ModelSelection]: the Activity fetches the status via
 * [ru.mars.network.repository.ReasoningRepository] and hands it to [applyStatus]; this class
 * resolves options/labels and persists explicit picks. It knows nothing about Android views.
 *
 * Deliberately server-driven: the ladder comes only from `supported_efforts` (no hard-coded
 * levels), and an unknown/failed state hides the selector instead of guessing.
 */
class ReasoningEffortSelection(private val storage: ReasoningChoiceStorage) {

    /** The last explicit user pick (restored from storage on start); null until the user chooses. */
    var current: ReasoningChoice? = storage.load()
        private set

    /** Last server status; null before the first successful fetch and after any failure. */
    var status: ReasoningStatus? = null
        private set

    /** Stores the outcome of a status fetch: the payload on success, null on failure/unknown. */
    fun applyStatus(newStatus: ReasoningStatus?) {
        status = newStatus
    }

    /**
     * Selector rows: "Авто" first, then the server-supported levels in server order
     * (normalized + deduped). Empty when the state is unknown or the model has no ladder —
     * the caller hides the control.
     */
    fun options(): List<ReasoningOption> {
        val supported = normalizedSupported()
        if (supported.isEmpty()) return emptyList()
        return listOf(ReasoningOption(null, label(null))) + supported.map { ReasoningOption(it, label(it)) }
    }

    /**
     * Persists an explicit pick ([effort] null = Авто). Called ONLY after the server
     * confirmed the change (POST /api/reasoning success), so a refused/failed write
     * never becomes a local truth.
     */
    fun select(effort: String?): ReasoningChoice {
        val choice = ReasoningChoice(normalize(effort), explicitPick = true)
        current = choice
        storage.save(choice)
        return choice
    }

    /**
     * The effective effort to display/check: the server's coerced value when a status is
     * known (it reflects global/per-model/session resolution), else the restored local pick.
     */
    fun effectiveEffort(): String? {
        val serverEffort = normalize(status?.reasoningEffort)
        return if (status != null) serverEffort else current?.effort
    }

    /** Header-chip label; null while the control must stay hidden (no status and no pick). */
    fun buttonLabel(): String? {
        if (status == null && current == null) return null
        if (status != null && normalizedSupported().isEmpty()) return null
        return label(effectiveEffort())
    }

    /** Display label of a level: capitalized server value; null/blank = «Авто» (WebUI i18n parity). */
    fun label(effort: String?): String =
        normalize(effort)?.replaceFirstChar(Char::uppercaseChar) ?: "Авто"

    private fun normalizedSupported(): List<String> {
        if (status == null) return emptyList()
        return status?.supportedEfforts.orEmpty().mapNotNull { normalize(it) }.distinct()
    }

    private fun normalize(effort: String?): String? = effort?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
}
