package ru.mars.network.model

/**
 * Aggregate token usage and estimated cost of one chat session
 * (`GET /api/session/usage?session_id=<id>`).
 *
 * Wire form (verified live, hermes-webui api/metering.py): the response is
 * `{"ok": true, "usage": {...}}` and the block carries cumulative counters for
 * the whole session plus an optional price. The price field is genuinely
 * OPTIONAL on the wire — providers without a price table answer `null` — so
 * [cost] stays nullable instead of collapsing to `0.0`, which the UI would
 * have to render as a fake "free" answer.
 *
 * Token aliases are accepted on parse (`prompt_tokens`/`input_tokens`,
 * `completion_tokens`/`output_tokens`) because the server has used both
 * namings; the domain model exposes one pair of names.
 */
data class SessionUsage(
    /** All tokens of the session (prompt + completion). */
    val totalTokens: Int = 0,
    /** Tokens sent to the model (`prompt_tokens`, alias `input_tokens`). */
    val promptTokens: Int = 0,
    /** Tokens produced by the model (`completion_tokens`, alias `output_tokens`). */
    val completionTokens: Int = 0,
    /** Estimated spend in USD; `null` when the server reports no price for the provider. */
    val cost: Double? = null,
    /** Model identity the counters belong to, when the server names one. */
    val model: String? = null,
) {
    /** True when the counters say something happened — a fresh session reports all zeros. */
    val hasTokens: Boolean get() = totalTokens > 0

    /** True when a positive price is known; `null`/`0` cost must stay invisible in the UI. */
    val hasCost: Boolean get() = cost != null && cost > 0.0
}
