package ru.mars.network.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.mars.network.model.TodoItem
import ru.mars.network.model.TodoSnapshot
import ru.mars.network.model.TodoSummary

/**
 * Contract of [TodoStateParser] — the SINGLE deserialization point for
 * `todo_state` shared by the cold path (`GET /api/session` →
 * `session.todo_state`) and the live SSE path (`event: todo_state` with the
 * `{session_id, stream_id, source, ts}` envelope, api/todo_state.py).
 */
class TodoStateParserTest {

    /** The exact live SSE payload verified against the server (envelope + snapshot). */
    private val livePayload = """
        {"session_id":"s-1","stream_id":"st-9","source":"tool","ts":1759900001.25,
         "todos":[{"id":"1","content":"Шаг один","status":"completed"},
                  {"id":"2","content":"Шаг два","status":"in_progress"}],
         "summary":{"total":2,"pending":0,"in_progress":1,"completed":1,"cancelled":0},
         "version":1}
    """.trimIndent()

    @Test fun parsesLiveEnvelopeAndSnapshot() {
        assertEquals(
            TodoSnapshot(
                todos = listOf(
                    TodoItem(id = "1", content = "Шаг один", status = "completed"),
                    TodoItem(id = "2", content = "Шаг два", status = "in_progress"),
                ),
                summary = TodoSummary(total = 2, pending = 0, inProgress = 1, completed = 1, cancelled = 0),
                version = 1,
                tsEpochSeconds = 1759900001.25,
                sessionId = "s-1",
                streamId = "st-9",
                source = "tool",
            ),
            TodoStateParser.parsePayload(livePayload),
        )
    }

    /** Cold shape (session detail field): no envelope, ts tolerated — envelope fields stay null. */
    @Test fun parsesColdSnapshotWithoutEnvelope() {
        val snapshot = TodoStateParser.parse(
            StreamEventMapper.parseObject(
                """{"todos":[{"id":"a","content":"x","status":"pending"}],
                    "summary":{"total":1,"pending":1},"version":1,"ts":1759900002.5}"""
            )
        )!!
        assertEquals(listOf(TodoItem("a", "x", "pending")), snapshot.todos)
        assertEquals(TodoSummary(total = 1, pending = 1), snapshot.summary)
        assertEquals(1759900002.5, snapshot.tsEpochSeconds!!, 0.0)
        assertNull(snapshot.sessionId)
        assertNull(snapshot.streamId)
        assertNull(snapshot.source)
    }

    /** Every known status is preserved verbatim — the parser never rewrites wire values. */
    @Test fun preservesAllKnownStatusesVerbatim() {
        val snapshot = TodoStateParser.parsePayload(
            """{"todos":[
                {"id":"1","content":"a","status":"pending"},
                {"id":"2","content":"b","status":"in_progress"},
                {"id":"3","content":"c","status":"completed"},
                {"id":"4","content":"d","status":"cancelled"}
               ],"summary":{}}"""
        )!!
        assertEquals(listOf("pending", "in_progress", "completed", "cancelled"), snapshot.todos.map { it.status })
    }

    /**
     * An UNKNOWN status is preserved verbatim too: mapping it onto a rendering
     * fallback is UI-layer work (WebUI `todoStatusKey`), not a data-layer decision.
     */
    @Test fun preservesUnknownStatusVerbatim() {
        val snapshot = TodoStateParser.parsePayload(
            """{"todos":[{"id":"1","content":"a","status":"blocked"}],"summary":{}}"""
        )!!
        assertEquals("blocked", snapshot.todos.single().status)
    }

    /** Server contract (`_normalize_snapshot`): an EMPTY todos list is a valid snapshot — the latest write cleared it. */
    @Test fun emptyTodosListIsValidSnapshot() {
        val snapshot = TodoStateParser.parsePayload(
            """{"todos":[],"summary":{"total":0,"pending":0,"in_progress":0,"completed":0,"cancelled":0},"version":1}"""
        )!!
        assertEquals(emptyList<TodoItem>(), snapshot.todos)
        assertEquals(TodoSummary(), snapshot.summary)
    }

    /** A payload without a `todos` list is not a snapshot — null, never a crash. */
    @Test fun missingOrNonListTodosIsNotASnapshot() {
        assertNull(TodoStateParser.parsePayload("""{"summary":{"total":1}}"""))
        assertNull(TodoStateParser.parsePayload("""{"todos":"oops"}"""))
        assertNull(TodoStateParser.parse(null))
        assertNull(TodoStateParser.parsePayload("not json at all"))
    }

    /** Lenient item parsing: numeric ids normalized (Moshi Double → "7"), `text` alias, status defaults to pending. */
    @Test fun lenientItemParsing() {
        val snapshot = TodoStateParser.parsePayload(
            """{"todos":[{"id":7,"text":"Через text"},"not-a-map",{"content":"c","status":"completed"}],"summary":{}}"""
        )!!
        assertEquals(
            listOf(
                TodoItem(id = "7", content = "Через text", status = "pending"),
                TodoItem(id = null, content = "c", status = "completed"),
            ),
            snapshot.todos,
        )
    }

    /** Missing summary → zero counts; missing version → 1 (VERSION contract); blank envelope strings → null. */
    @Test fun defaultsForMissingFields() {
        val snapshot = TodoStateParser.parsePayload(
            """{"todos":[],"session_id":"  ","stream_id":"","source":""}"""
        )!!
        assertEquals(TodoSummary(), snapshot.summary)
        assertEquals(1, snapshot.version)
        assertNull(snapshot.tsEpochSeconds)
        assertNull(snapshot.sessionId)
        assertNull(snapshot.streamId)
        assertNull(snapshot.source)
    }
}
