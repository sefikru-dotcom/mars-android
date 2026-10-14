package ru.mars.network.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.SubagentState
import ru.mars.network.model.SubagentStatus
import ru.mars.network.model.SubagentStatusUpdate
import ru.mars.network.model.SubagentUpdate

/**
 * Задача B (P0-2): live-события субагентов как типизированный носитель.
 *
 * Кадры `subagent.*`/`delegate.*` (hermes-agent tools/delegate_tool_progress.py
 * `_ChildProgressRelay`, gateway api_server_runs.py `_SUBAGENT_EVENT_KEYS`) сегодня
 * попадают только в общий [ru.mars.network.model.ChatEvent.Thought] — по ним нельзя
 * понять состояние конкретного ребёнка. [StreamEventMapper.subagentUpdate] разбирает
 * кадр в [SubagentUpdate] (адресуемый ребёнок + факт), а
 * [ru.mars.network.model.StreamSideEvent]-носитель [SubagentStatusUpdate] доставляет
 * его через `ChatRepository.onSideEvent` — БЕЗ нового варианта sealed ChatEvent
 * (исчерпывающие `when` в app-mobile/app-tv остаются целы).
 *
 * Терминальные кадры хода (`cancel`/`error`/`apperror`) дают [SubagentUpdate.Kind.TurnStopped]:
 * ход умер/остановлен, активный ребёнок остаться не может. Нормальный `stream_end`/`done`
 * НЕ даёт TurnStopped — дети к этому моменту отчитываются сами, а сайд-канал
 * `stream_end` закреплён существующими тестами доставки.
 */
class StreamEventMapperSubagentUpdateTest {

    private val startFrame = """
        {"event":"subagent.start","run_id":"r-1","preview":"Собрать релиз",
         "goal":"Собрать релиз","task_index":1,"task_count":2,
         "subagent_id":"sa-1-ab12cd34","child_session_id":"ch-9","delegation_id":"d-1",
         "parent_id":null,"depth":1,"model":"gpt-6.1-sol","tool_count":0}
    """.trimIndent()

    // ---- subagent.start -------------------------------------------------------------------------

    @Test
    fun startFrameCarriesIdentityGoalAndModel() {
        val update = StreamEventMapper.subagentUpdate("subagent.start", startFrame)
        assertNotNull(update)
        assertEquals(SubagentUpdate.Kind.Start, update!!.kind)
        assertEquals("sa-1-ab12cd34", update.childId)
        assertEquals("Собрать релиз", update.displayName)
        assertEquals("gpt-6.1-sol", update.model)
        assertNull(update.rawStatus)
        assertNull(update.failureReason)
    }

    @Test
    fun delegateTaskSpawnedIsAStartAlias() {
        val update = StreamEventMapper.subagentUpdate("delegate.task_spawned", startFrame)
        assertNotNull(update)
        assertEquals(SubagentUpdate.Kind.Start, update!!.kind)
        assertEquals("sa-1-ab12cd34", update.childId)
    }

    @Test
    fun childIdFallsBackAcrossIdentityKeys() {
        val noSubagentId = startFrame.replace("\"subagent_id\":\"sa-1-ab12cd34\",", "")
        assertEquals(
            "ch-9",
            StreamEventMapper.subagentUpdate("subagent.start", noSubagentId)!!.childId,
        )
        val onlyDelegation = startFrame
            .replace("\"subagent_id\":\"sa-1-ab12cd34\",", "")
            .replace("\"child_session_id\":\"ch-9\",", "")
        assertEquals(
            "d-1",
            StreamEventMapper.subagentUpdate("subagent.start", onlyDelegation)!!.childId,
        )
    }

    @Test
    fun startFrameWithoutAnyIdentityStillDeliversUpdate() {
        val anonymous = startFrame
            .replace("\"subagent_id\":\"sa-1-ab12cd34\",", "")
            .replace("\"child_session_id\":\"ch-9\",", "")
            .replace("\"delegation_id\":\"d-1\",", "")
        val update = StreamEventMapper.subagentUpdate("subagent.start", anonymous)
        assertNotNull(update)
        assertNull(update!!.childId)
        // Политика такой апдейт игнорирует — но транспорт его честно доставляет.
        assertEquals(emptyMap<String, SubagentStatus>(), SubagentStatusPolicy.reduce(listOf(update)))
    }

    @Test
    fun displayNameFallsBackFromGoalToPreviewToTaskLabel() {
        val noGoal = startFrame.replace("\"goal\":\"Собрать релиз\",", "")
        assertEquals(
            "Собрать релиз",
            StreamEventMapper.subagentUpdate("subagent.start", noGoal)!!.displayName,
        )
        val noGoalNoPreview = noGoal.replace("\"preview\":\"Собрать релиз\",", "")
        assertEquals(
            "Субагент 1/2",
            StreamEventMapper.subagentUpdate("subagent.start", noGoalNoPreview)!!.displayName,
        )
    }

    // ---- subagent.progress ------------------------------------------------------------------------

    @Test
    fun progressFrameCarriesPhaseAndStatus() {
        val frame = """
            {"subagent_id":"sa-1","status":"running","progress":0.4,
             "preview":"🔀 [1/2] terminal: ./gradlew test"}
        """.trimIndent()
        val update = StreamEventMapper.subagentUpdate("subagent.progress", frame)
        assertNotNull(update)
        assertEquals(SubagentUpdate.Kind.Progress, update!!.kind)
        assertEquals("sa-1", update.childId)
        assertEquals("🔀 [1/2] terminal: ./gradlew test", update.phase)
        assertEquals("running", update.rawStatus)
    }

    @Test
    fun waitingStatusOnProgressBecomesWaitingKind() {
        val frame = """{"subagent_id":"sa-1","status":"waiting","preview":"ждёт подтверждение"}"""
        val update = StreamEventMapper.subagentUpdate("subagent.progress", frame)
        assertEquals(SubagentUpdate.Kind.Waiting, update!!.kind)
        assertEquals("waiting", update.rawStatus)
    }

    @Test
    fun delegateTaskProgressIsAProgressAlias() {
        val frame = """{"delegation_id":"d-1","preview":"пишет отчёт"}"""
        val update = StreamEventMapper.subagentUpdate("delegate.task_progress", frame)
        assertEquals(SubagentUpdate.Kind.Progress, update!!.kind)
        assertEquals("d-1", update.childId)
        assertEquals("пишет отчёт", update.phase)
    }

    // ---- subagent.complete / delegate.task_completed / delegate.task_failed -----------------------

    @Test
    fun completeFrameCarriesRawStatusVerbatim() {
        val frame = """
            {"subagent_id":"sa-1","status":"completed","duration_seconds":12.5,
             "goal":"Деплой","summary":"Готово","output_tail":"секрет-хвост"}
        """.trimIndent()
        val update = StreamEventMapper.subagentUpdate("subagent.complete", frame)
        assertNotNull(update)
        assertEquals(SubagentUpdate.Kind.Complete, update!!.kind)
        assertEquals("sa-1", update.childId)
        assertEquals("completed", update.rawStatus)
        assertNull(update.failureReason)
        // Сырой output_tail ребёнка никогда не покидает транспорт.
        assertTrue(update.toString().contains("output_tail").not())
    }

    @Test
    fun failedCompleteCarriesFailureReason() {
        val frame = """
            {"subagent_id":"sa-1","status":"failed","goal":"Деплой",
             "failure_reason":"child timeout","summary":"⚠️ ..."}
        """.trimIndent()
        val update = StreamEventMapper.subagentUpdate("subagent.complete", frame)
        assertEquals(SubagentUpdate.Kind.Complete, update!!.kind)
        assertEquals("failed", update.rawStatus)
        assertEquals("child timeout", update.failureReason)
        assertEquals("Деплой", update.displayName)
    }

    @Test
    fun delegateTaskFailedDefaultsToFailedStatus() {
        val frame = """{"delegation_id":"d-2","goal":"Исследование"}"""
        val update = StreamEventMapper.subagentUpdate("delegate.task_failed", frame)
        assertEquals(SubagentUpdate.Kind.Complete, update!!.kind)
        assertEquals("failed", update.rawStatus)
        assertEquals("d-2", update.childId)
    }

    @Test
    fun delegateTaskCompletedIsACompleteAlias() {
        val frame = """{"subagent_id":"sa-3","status":"success"}"""
        val update = StreamEventMapper.subagentUpdate("delegate.task_completed", frame)
        assertEquals(SubagentUpdate.Kind.Complete, update!!.kind)
        assertEquals("success", update.rawStatus)
    }

    // ---- Шум и чужие кадры ------------------------------------------------------------------------

    @Test
    fun childNoiseFramesYieldNothing() {
        for (name in listOf(
            "subagent.text", "subagent.thinking", "subagent.tool",
            "delegate.tool_started", "delegate.tool_completed",
        )) {
            assertNull(name, StreamEventMapper.subagentUpdate(name, """{"subagent_id":"sa-1","preview":"шум"}"""))
        }
    }

    @Test
    fun unrelatedFramesYieldNothing() {
        for (name in listOf("token", "title", "todo_state", "goal", "context_status", "done", "stream_end")) {
            assertNull(name, StreamEventMapper.subagentUpdate(name, """{"text":"а"}"""))
        }
    }

    @Test
    fun nonJsonPayloadOnSubagentFrameYieldsNothing() {
        assertNull(StreamEventMapper.subagentUpdate("subagent.start", "не json"))
        assertNull(StreamEventMapper.subagentUpdate("subagent.complete", ""))
    }

    // ---- Терминальные кадры хода ------------------------------------------------------------------

    @Test
    fun cancelErrorApperrorYieldTurnStoppedEvenWithGarbagePayload() {
        for (name in listOf("cancel", "error", "apperror")) {
            val update = StreamEventMapper.subagentUpdate(name, """{"session_id":"s-1"}""")
            assertNotNull(name)
            assertEquals(name, SubagentUpdate.Kind.TurnStopped, update!!.kind)
            assertNull(name, update.childId)
            // TurnStopped должен работать и вовсе без JSON-payload.
            assertEquals(
                SubagentUpdate.Kind.TurnStopped,
                StreamEventMapper.subagentUpdate(name, "")!!.kind,
            )
        }
    }

    @Test
    fun normalTurnEndFramesDoNotYieldTurnStopped() {
        // `stream_end`/`done` — нормальное завершение: дети отчитались сами,
        // а сайд-канал этих кадров закреплён ChatRepositorySideEventsTest (4 носителя).
        assertNull(StreamEventMapper.subagentUpdate("stream_end", "{}"))
        assertNull(StreamEventMapper.subagentUpdate("done", "{}"))
    }

    // ---- Доставка через sideEvent -----------------------------------------------------------------

    @Test
    fun sideEventWrapsSubagentUpdates() {
        val side = StreamEventMapper.sideEvent("subagent.start", startFrame)
        assertTrue(side is SubagentStatusUpdate)
        assertEquals("sa-1-ab12cd34", (side as SubagentStatusUpdate).update.childId)
        assertEquals(
            SubagentUpdate.Kind.TurnStopped,
            (StreamEventMapper.sideEvent("cancel", "{}") as SubagentStatusUpdate).update.kind,
        )
        // Существующие носители не задеты.
        assertNull(StreamEventMapper.sideEvent("token", """{"text":"а"}"""))
        assertNull(StreamEventMapper.sideEvent("subagent.text", """{"preview":"шум"}"""))
    }

    // ---- Сквозной сценарий: провод -> редьюсер ------------------------------------------------------

    @Test
    fun wireFramesReduceIntoLiveBoard() {
        val frames = listOf(
            "subagent.start" to """
                {"goal":"Собрать релиз","task_index":1,"task_count":2,"subagent_id":"sa-1","model":"gpt-6.1-sol"}
            """.trimIndent(),
            "subagent.start" to """{"goal":"Прогнать тесты","task_index":2,"task_count":2,"subagent_id":"sa-2"}""",
            "subagent.progress" to """{"subagent_id":"sa-2","status":"running","preview":"пишет тесты"}""",
            "subagent.complete" to """{"subagent_id":"sa-2","status":"completed","duration_seconds":9.0}""",
            "subagent.complete" to """{"subagent_id":"sa-1","status":"timeout","failure_reason":"child timeout"}""",
        )
        val board = SubagentStatusPolicy.reduce(frames.mapNotNull { (name, payload) ->
            StreamEventMapper.subagentUpdate(name, payload)
        })
        assertEquals(setOf("sa-1", "sa-2"), board.keys)
        assertEquals(SubagentState.Failed, board.getValue("sa-1").state)
        assertEquals("child timeout", board.getValue("sa-1").failureReason)
        assertEquals("gpt-6.1-sol", board.getValue("sa-1").model)
        assertEquals(SubagentState.Completed, board.getValue("sa-2").state)
        assertEquals("пишет тесты", board.getValue("sa-2").phase)
        assertTrue(board.values.none { it.state.isActive })
    }

    @Test
    fun cancelFrameStopsRunningChildEndToEnd() {
        val updates = listOfNotNull(
            StreamEventMapper.subagentUpdate("subagent.start", """{"subagent_id":"sa-1","goal":"Долгая задача"}"""),
            StreamEventMapper.subagentUpdate("cancel", "{}"),
        )
        val board = SubagentStatusPolicy.reduce(updates)
        assertEquals(SubagentState.Stopped, board.getValue("sa-1").state)
    }
}
