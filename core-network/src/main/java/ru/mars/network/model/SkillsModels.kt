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
    /**
     * `disabled` flag of the `GET /api/skills` entry (route_skills.py `_skills_list_from_dir`):
     * the skill exists on disk but is switched off in the profile config. Absent/non-boolean
     * on the wire collapses to `false` (enabled) — same default the web frontend uses.
     */
    val disabled: Boolean = false,
)

/**
 * Parsed `GET /api/skills/content?name=...` response:
 * `{"name": "...", "content": "..."}` — the full SKILL.md body of one skill.
 */
data class SkillDetail(
    val name: String,
    val content: String = "",
)
