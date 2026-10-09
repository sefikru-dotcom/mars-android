package ru.mars.network.model

/**
 * One entry of `GET /api/skills` (`skills[]`):
 * `{"name": "...", "description": "...", "category": "..."}`.
 *
 * [name] is the skill identifier used to fetch the full body via
 * `GET /api/skills/content?name=...`; [description] and [category] are display
 * metadata and default to empty strings when the server omits them.
 */
data class SkillItem(
    val name: String,
    val description: String = "",
    val category: String = "",
)

/**
 * Parsed `GET /api/skills/content?name=...` response:
 * `{"name": "...", "content": "..."}` — the full SKILL.md body of one skill.
 */
data class SkillDetail(
    val name: String,
    val content: String = "",
)
