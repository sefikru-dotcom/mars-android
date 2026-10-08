package ru.mars.network.model

/**
 * Parsed payload of `GET/POST /api/reasoning` (hermes-webui `api/routes.py`
 * `_reasoning_status_payload` → `api/config.get_reasoning_status`).
 *
 * Live shape (captured 2026-10-08):
 * `{show_reasoning, reasoning_effort, source, supported_efforts[],
 *   supports_reasoning_effort, supports_thinking_toggle, model_overrides}`.
 *
 * [reasoningEffort] is the server-COERCED effective effort (`''`/null = default);
 * [supportedEfforts] is the per-model ladder the UI must offer verbatim — the
 * client never invents levels. [supportsThinkingToggle] is true for ZAI GLM
 * toggle-only models whose [supportedEfforts] is empty (no ladder → no selector).
 * `model_overrides` (per-model pins edited from the WebUI model picker) is out of
 * the mobile P1 slice and intentionally not modeled.
 */
data class ReasoningStatus(
    val showReasoning: Boolean = true,
    val reasoningEffort: String? = null,
    val source: String? = null,
    val supportedEfforts: List<String> = emptyList(),
    val supportsReasoningEffort: Boolean = false,
    val supportsThinkingToggle: Boolean = false,
)
