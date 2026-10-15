package ru.mars.network.repository

import ru.mars.network.model.SessionUsage

/**
 * Single parse point for the session-usage payload
 * (`GET /api/session/usage?session_id=<id>`, contract verified live).
 *
 * Accepted shapes:
 * - `{"ok": true, "usage": {…}}` — the canonical envelope;
 * - the bare usage block `{…}` — tolerated so an older/simplified responder
 *   does not turn a valid measurement into "unavailable".
 *
 * Field aliases (both namings are live on the server): `total_tokens`,
 * `prompt_tokens`/`input_tokens`, `completion_tokens`/`output_tokens`,
 * `cost`/`estimated_cost`, `model`. Missing numbers decode as `0` — a session
 * that never ran a turn legitimately reports zeros, and that is NOT an error.
 * Returns `null` only when the payload carries no usage block at all.
 */
internal object SessionUsageParser {

    fun parse(root: Map<String, Any?>?): SessionUsage? {
        root ?: return null
        @Suppress("UNCHECKED_CAST")
        val block = (root["usage"] as? Map<String, Any?>) ?: root
        // Envelope without a usage block (`{"ok": false}` / error body) — no measurement.
        if (!root.containsKey("usage") && !looksLikeUsageBlock(block)) return null
        return SessionUsage(
            totalTokens = int(block, "total_tokens", "totalTokens") ?: 0,
            promptTokens = int(block, "prompt_tokens", "input_tokens") ?: 0,
            completionTokens = int(block, "completion_tokens", "output_tokens") ?: 0,
            cost = double(block, "cost", "estimated_cost"),
            model = text(block, "model"),
        )
    }

    /** A bare payload is treated as a usage block only when it carries a known counter. */
    private fun looksLikeUsageBlock(block: Map<String, Any?>): Boolean =
        int(block, "total_tokens", "prompt_tokens", "input_tokens", "completion_tokens", "output_tokens") != null

    /** Numbers arrive as JSON number or as a numeric string (state.db sidecar style). */
    private fun int(block: Map<*, *>, vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
        when (val value = block[key]) {
            is Number -> value.toLong().toInt()
            is String -> value.trim().toDoubleOrNull()?.toInt()
            else -> null
        }
    }

    private fun double(block: Map<*, *>, vararg keys: String): Double? = keys.firstNotNullOfOrNull { key ->
        when (val value = block[key]) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }
    }

    private fun text(block: Map<*, *>, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> block[key]?.toString()?.takeIf(String::isNotBlank) }
}
