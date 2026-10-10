package ru.mars.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.repository.CronRepository

class CronRepositoryTest {

    private val repo = CronRepository()

    @Test
    fun parseCronJobs_parsesListProperly() {
        val json = """
            {
              "jobs": [
                {
                  "id": "18d1a888e316",
                  "name": "morning-brief",
                  "schedule_display": "0 7 * * *",
                  "enabled": true,
                  "state": "scheduled",
                  "next_run_at": "2026-10-11T07:00:00+03:00",
                  "last_status": "ok",
                  "model": "deepseek-flash"
                },
                {
                  "id": "9fa8dd502b98",
                  "name": "memory-consolidation",
                  "schedule": {
                    "display": "30 0 * * *"
                  },
                  "enabled": false,
                  "state": "paused",
                  "last_status": "ok"
                }
              ],
              "active_profile": "default"
            }
        """.trimIndent()

        val response = repo.parseCronJobs(json)
        assertEquals("default", response.activeProfile)
        assertFalse(response.cronUnavailable)
        assertEquals(2, response.jobs.size)

        val job1 = response.jobs[0]
        assertEquals("18d1a888e316", job1.id)
        assertEquals("morning-brief", job1.name)
        assertEquals("0 7 * * *", job1.scheduleDisplay)
        assertTrue(job1.enabled)
        assertEquals("scheduled", job1.state)
        assertEquals("ok", job1.lastStatus)
        assertEquals("deepseek-flash", job1.model)

        val job2 = response.jobs[1]
        assertEquals("9fa8dd502b98", job2.id)
        assertEquals("30 0 * * *", job2.scheduleDisplay)
        assertFalse(job2.enabled)
        assertEquals("paused", job2.state)
    }

    @Test
    fun parseCronJobs_emptyOrMalformed_returnsEmpty() {
        val emptyResp = repo.parseCronJobs("{}")
        assertTrue(emptyResp.jobs.isEmpty())

        val invalidResp = repo.parseCronJobs("invalid json")
        assertTrue(invalidResp.jobs.isEmpty())
    }
}
