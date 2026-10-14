package ru.mars.network.repository

import com.squareup.moshi.Moshi

/**
 * Построитель тел POST-запросов /api/crons/&lt;action&gt; строго по серверному контракту
 * (hermes-webui api/routes.py):
 * - create: require(body, "prompt", "schedule"); опционально name/deliver/model/provider;
 * - update: require(body, "job_id"); остальные поля — только непустые;
 * - delete/run/pause/resume: body.get("job_id").
 * Чистая JVM-функция (без Android/сети) — покрыта CronCrudRepositoryTest.
 */
object CronRequestBodies {

    private val stringAdapter = Moshi.Builder().build().adapter(String::class.java)

    /** JSON-строка с кавычками и экранированием (Moshi). */
    private fun q(value: String): String = stringAdapter.toJson(value)

    private fun optional(parts: MutableList<String>, key: String, value: String?) {
        value?.trim()?.takeIf(String::isNotBlank)?.let { parts.add("\"$key\":${q(it)}") }
    }

    /** {"job_id":"..."} — контракт run/pause/resume/delete. */
    fun idBody(jobId: String): String = "{\"job_id\":${q(jobId)}}"

    fun createBody(
        prompt: String,
        schedule: String,
        name: String? = null,
        deliver: String? = null,
        model: String? = null,
    ): String {
        val parts = mutableListOf("\"prompt\":${q(prompt)}", "\"schedule\":${q(schedule)}")
        optional(parts, "name", name)
        optional(parts, "deliver", deliver)
        optional(parts, "model", model)
        return parts.joinToString(",", "{", "}")
    }

    fun updateBody(
        jobId: String,
        prompt: String? = null,
        schedule: String? = null,
        name: String? = null,
        deliver: String? = null,
        model: String? = null,
    ): String {
        val parts = mutableListOf("\"job_id\":${q(jobId)}")
        optional(parts, "prompt", prompt)
        optional(parts, "schedule", schedule)
        optional(parts, "name", name)
        optional(parts, "deliver", deliver)
        optional(parts, "model", model)
        return parts.joinToString(",", "{", "}")
    }
}
