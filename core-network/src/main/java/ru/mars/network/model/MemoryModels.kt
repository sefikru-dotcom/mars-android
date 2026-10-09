package ru.mars.network.model

/**
 * Parsed `GET /api/memory` response of the WebUI backend:
 * `{"memory": "...", "user": "...", "soul": "...", "project_context": "..."}`.
 *
 * Each section is a plain markdown/text blob maintained server-side (MEMORY.md,
 * USER.md, SOUL.md and the active project context). The server may omit or null
 * any section, so every field defaults to an empty string and the model never
 * carries nulls. The wire key `project_context` maps to [projectContext].
 */
data class MemoryContent(
    val memory: String = "",
    val user: String = "",
    val soul: String = "",
    val projectContext: String = "",
)
