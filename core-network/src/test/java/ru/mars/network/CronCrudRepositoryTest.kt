package ru.mars.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.repository.CronMutationResult
import ru.mars.network.repository.CronRequestBodies
import ru.mars.network.repository.CronRepository

/**
 * JVM-тесты CRUD-планировщика по реальным серверным контрактам /api/crons
 * (hermes-webui api/routes.py: _handle_cron_create/update/delete/run/pause/resume,
 * _handle_cron_history, _handle_cron_run_detail, _handle_cron_delivery_options).
 * Без сети и эмулятора: проверяются только тела запросов и парсеры ответов.
 */
class CronCrudRepositoryTest {

    private val repo = CronRepository()

    // ── Тела запросов (POST-контракты сервера: body.get("job_id")) ─────────────

    @Test
    fun idBody_usesServerContractKey_jobId() {
        assertEquals("""{"job_id":"abc123"}""", CronRequestBodies.idBody("abc123"))
    }

    @Test
    fun idBody_escapesQuotes() {
        assertEquals("""{"job_id":"a\"b"}""", CronRequestBodies.idBody("a\"b"))
    }

    @Test
    fun createBody_minimalContract_promptAndSchedule() {
        assertEquals(
            """{"prompt":"Check weather","schedule":"30m"}""",
            CronRequestBodies.createBody(prompt = "Check weather", schedule = "30m"),
        )
    }

    @Test
    fun createBody_optionalFields_appendedWhenPresent() {
        val body = CronRequestBodies.createBody(
            prompt = "P",
            schedule = "0 9 * * *",
            name = "Morning",
            deliver = "telegram",
            model = "gpt-x",
        )
        assertEquals(
            """{"prompt":"P","schedule":"0 9 * * *","name":"Morning","deliver":"telegram","model":"gpt-x"}""",
            body,
        )
    }

    @Test
    fun createBody_blankOptionals_omitted() {
        val body = CronRequestBodies.createBody(prompt = "P", schedule = "30m", name = "  ", deliver = "", model = null)
        assertEquals("""{"prompt":"P","schedule":"30m"}""", body)
    }

    @Test
    fun createBody_escapesSpecialCharacters() {
        val body = CronRequestBodies.createBody(prompt = "Say \"hi\"\nline2\\", schedule = "30m")
        assertEquals("""{"prompt":"Say \"hi\"\nline2\\","schedule":"30m"}""", body)
    }

    @Test
    fun updateBody_alwaysCarriesJobId_onlyProvidedFields() {
        assertEquals(
            """{"job_id":"j1","schedule":"every 2h"}""",
            CronRequestBodies.updateBody(jobId = "j1", schedule = "every 2h"),
        )
        assertEquals("""{"job_id":"j1"}""", CronRequestBodies.updateBody(jobId = "j1"))
        assertEquals(
            """{"job_id":"j1","prompt":"P","name":"N"}""",
            CronRequestBodies.updateBody(jobId = "j1", name = "N", prompt = "P"),
        )
    }

    // ── Парсеры ответов мутаций (create/update/delete/run/pause/resume) ────────

    @Test
    fun parseMutationResult_okWithJob() {
        val json = """{"ok":true,"job":{"id":"j9","name":"brief","schedule_display":"0 7 * * *","enabled":true,"state":"scheduled","prompt":"hi"}}"""
        val result = repo.parseMutationResult(json)
        assertTrue(result is CronMutationResult.Ok)
        val job = (result as CronMutationResult.Ok).job
        assertEquals("j9", job?.id)
        assertEquals("brief", job?.name)
        assertEquals("0 7 * * *", job?.scheduleDisplay)
        assertEquals("hi", job?.prompt)
    }

    @Test
    fun parseMutationResult_okWithoutJob_delete() {
        // delete: {"ok":true,"job_id":"..."} (routes.py _handle_cron_delete)
        val result = repo.parseMutationResult("""{"ok":true,"job_id":"j1"}""")
        assertTrue(result is CronMutationResult.Ok)
        assertNull((result as CronMutationResult.Ok).job)
    }

    @Test
    fun parseMutationResult_errorCarriesServerMessage() {
        val result = repo.parseMutationResult("""{"error":"prompt required"}""")
        assertTrue(result is CronMutationResult.Failed)
        assertEquals("prompt required", (result as CronMutationResult.Failed).error)
    }

    @Test
    fun parseMutationResult_notFoundError() {
        val result = repo.parseMutationResult("""{"error":"Job not found","id":"x"}""")
        assertTrue(result is CronMutationResult.Failed)
        assertEquals("Job not found", (result as CronMutationResult.Failed).error)
    }

    @Test
    fun parseMutationResult_runAlreadyRunning_isFailureWithStatus() {
        // run: HTTP 200, но {"ok":false,"status":"already_running"} — не успех.
        val result = repo.parseMutationResult("""{"ok":false,"job_id":"j","status":"already_running","elapsed":12.3}""")
        assertTrue(result is CronMutationResult.Failed)
        assertEquals("already_running", (result as CronMutationResult.Failed).error)
    }

    @Test
    fun parseMutationResult_malformed_isFailure() {
        assertTrue(repo.parseMutationResult("not json") is CronMutationResult.Failed)
    }

    // ── История прогонов: GET /api/crons/history ───────────────────────────────

    @Test
    fun parseHistory_parsesRunsUsageAndPaging() {
        val json = """
            {"job_id":"j1","runs":[
              {"filename":"2026-10-14T07-00-00.md","size":1536,"modified":1792000000.0,
               "usage":{"model":"gpt-x","provider":"openrouter","duration_seconds":12.5,
                        "estimated_cost_usd":0.002,"input_tokens":80,"output_tokens":20,"total_tokens":100}},
              {"filename":"2026-10-13T07-00-00.md"}
            ],"total":12,"offset":0}
        """.trimIndent()
        val history = repo.parseHistory(json)
        assertEquals("j1", history.jobId)
        assertEquals(12, history.total)
        assertEquals(0, history.offset)
        assertEquals(2, history.runs.size)
        val run0 = history.runs[0]
        assertEquals("2026-10-14T07-00-00.md", run0.filename)
        assertEquals(1536L, run0.sizeBytes)
        assertEquals(1792000000.0, run0.modifiedEpochSeconds!!, 0.001)
        assertEquals("gpt-x", run0.usage?.model)
        assertEquals("openrouter", run0.usage?.provider)
        assertEquals(12.5, run0.usage?.durationSeconds!!, 0.001)
        assertEquals(0.002, run0.usage?.estimatedCostUsd!!, 0.0001)
        assertEquals(100, run0.usage?.totalTokens)
        assertNull(history.runs[1].sizeBytes)
        assertNull(history.runs[1].usage)
    }

    @Test
    fun parseHistory_emptyUsageDict_isNull() {
        val history = repo.parseHistory("""{"job_id":"j1","runs":[{"filename":"a.md","usage":{}}],"total":1,"offset":0}""")
        assertNull(history.runs[0].usage)
    }

    @Test
    fun parseHistory_serverErrorOrMalformed_isEmpty() {
        val failed = repo.parseHistory("""{"error":"history unavailable","job_id":"j1"}""")
        assertTrue(failed.runs.isEmpty())
        assertEquals(0, failed.total)
        assertTrue(repo.parseHistory("{{").runs.isEmpty())
    }

    // ── Вывод прогона: GET /api/crons/run?job_id&filename ──────────────────────

    @Test
    fun parseRunDetail_parsesContentAndUsage() {
        val json = """{"job_id":"j1","filename":"f.md","content":"Отчёт готов","snippet":"Отчёт готов","usage":{"model":"m1","total_tokens":42}}"""
        val detail = repo.parseRunDetail(json)
        assertEquals("j1", detail?.jobId)
        assertEquals("f.md", detail?.filename)
        assertEquals("Отчёт готов", detail?.content)
        assertEquals("Отчёт готов", detail?.snippet)
        assertEquals("m1", detail?.usage?.model)
        assertEquals(42, detail?.usage?.totalTokens)
    }

    @Test
    fun parseRunDetail_errorResponse_isNull() {
        // 404 {"error":"run not found"} или битый JSON — детали нет.
        assertNull(repo.parseRunDetail("""{"error":"run not found","job_id":"j1"}"""))
        assertNull(repo.parseRunDetail("garbage"))
    }

    // ── Опции доставки: GET /api/crons/delivery-options ────────────────────────

    @Test
    fun parseDeliveryOptions_parsesPlatforms() {
        val json = """{"platforms":[{"value":"local","label":"Local (save output only)"},{"value":"origin","label":"Origin (reply to creator)"},{"value":"telegram","label":"Telegram"}]}"""
        val options = repo.parseDeliveryOptions(json)
        assertEquals(3, options.size)
        assertEquals("local", options[0].value)
        assertEquals("Local (save output only)", options[0].label)
        assertEquals("telegram", options[2].value)
    }

    @Test
    fun parseDeliveryOptions_malformed_isEmpty() {
        assertTrue(repo.parseDeliveryOptions("nope").isEmpty())
        assertTrue(repo.parseDeliveryOptions("{}").isEmpty())
    }
}
