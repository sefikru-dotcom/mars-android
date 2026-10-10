package ru.mars.network.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ActiveTurnInfo
import ru.mars.network.model.SessionAttention
import ru.mars.network.model.SessionItem

/**
 * JVM-тесты [OfflineSessionPersistencePolicy]: round-trip снимков списка сессий и
 * активного хода (включая activeTurn/attention), метаданные времени, TTL-валидация
 * (протухшие записи удаляются), восстановление после повреждений (битый JSON, чужая
 * версия, мусорные строки, невозможные часы), падающее хранилище и гигиена очистки.
 */
class OfflineSessionPersistencePolicyTest {

    /** In-memory [KeyValueStore]; [failing] имитирует падающее хранилище (диск/IO). */
    private class FakeStore(var failing: Boolean = false) : KeyValueStore {
        val map = HashMap<String, String>()
        override fun getString(key: String): String? {
            if (failing) throw IllegalStateException("store down")
            return map[key]
        }

        override fun putString(key: String, value: String) {
            if (failing) throw IllegalStateException("store down")
            map[key] = value
        }

        override fun remove(key: String) {
            if (failing) throw IllegalStateException("store down")
            map.remove(key)
        }

        override fun keys(): Set<String> {
            if (failing) throw IllegalStateException("store down")
            return map.keys.toSet()
        }

        override fun clearAll() {
            if (failing) throw IllegalStateException("store down")
            map.clear()
        }
    }

    private var now = 1_700_000_000_000L

    private fun newPolicy(
        store: KeyValueStore = FakeStore(),
        ttlMillis: Long = OfflineSessionPersistencePolicy.DEFAULT_TTL_MILLIS,
    ) = OfflineSessionPersistencePolicy(store, clock = { now }, ttlMillis = ttlMillis)

    private fun session(
        id: String,
        title: String? = "Диалог $id",
        activeTurn: ActiveTurnInfo = ActiveTurnInfo(),
    ) = SessionItem(
        id = id,
        title = title,
        preview = "последнее сообщение $id",
        createdAt = "2026-10-01T10:00:00Z",
        updatedAt = "2026-10-02T11:30:00Z",
        messageCount = 42,
        activeTurn = activeTurn,
        sessionSource = "webui",
        sourceTag = "subagent",
        rawSource = "subagent",
        sourceLabel = "Subagent",
        readOnly = true,
        parentSessionId = "parent-$id",
    )

    private fun fullTurn() = ActiveTurnInfo(
        activeStreamId = "stream-123",
        isStreaming = true,
        hasPendingUserMessage = true,
        pendingStartedAtEpochSeconds = 1_700_000_123.5,
        attention = SessionAttention(kind = "approval", count = 2, severity = "critical"),
    )

    // ---------- round-trip ----------

    @Test
    fun sessionsRoundTripPreservesAllFields() {
        val policy = newPolicy()
        val items = listOf(session("s1"), session("s2", title = null))
        assertTrue(policy.saveSessions(items))

        val snapshot = policy.loadSessions()
        assertNotNull(snapshot)
        assertEquals(now, snapshot!!.savedAtEpochMillis)
        assertEquals(items, snapshot.sessions)
    }

    @Test
    fun sessionRowKeepsActiveTurnAndAttention() {
        val policy = newPolicy()
        val item = session("s1", activeTurn = fullTurn())
        assertTrue(policy.saveSessions(listOf(item)))
        assertEquals(item, policy.loadSessions()?.sessions?.single())
    }

    @Test
    fun activeTurnRoundTrip() {
        val policy = newPolicy()
        assertTrue(policy.saveActiveTurn("s1", fullTurn()))

        val snapshot = policy.loadActiveTurn("s1")
        assertNotNull(snapshot)
        assertEquals("s1", snapshot!!.sessionId)
        assertEquals(now, snapshot.savedAtEpochMillis)
        assertEquals(fullTurn(), snapshot.turn)
        assertTrue(snapshot.turn.canReattach)
    }

    @Test
    fun idleActiveTurnRoundTrips() {
        val policy = newPolicy()
        assertTrue(policy.saveActiveTurn("s1", ActiveTurnInfo()))
        val snapshot = policy.loadActiveTurn("s1")
        assertNotNull(snapshot)
        assertEquals(ActiveTurnInfo(), snapshot!!.turn)
        assertFalse(snapshot.turn.canReattach)
    }

    @Test
    fun emptySessionListIsValidSnapshot() {
        val policy = newPolicy()
        assertTrue(policy.saveSessions(emptyList()))
        val snapshot = policy.loadSessions()
        assertNotNull(snapshot)
        assertEquals(emptyList<SessionItem>(), snapshot!!.sessions)
    }

    @Test
    fun loadWithoutSaveReturnsNull() {
        assertNull(newPolicy().loadSessions())
        assertNull(newPolicy().loadActiveTurn("s1"))
    }

    @Test
    fun snapshotIsCappedAtMaxSessions() {
        val policy = newPolicy()
        val many = (1..OfflineSessionPersistencePolicy.MAX_SNAPSHOT_SESSIONS + 50)
            .map { session("s$it") }
        assertTrue(policy.saveSessions(many))
        val loaded = policy.loadSessions()!!.sessions
        assertEquals(OfflineSessionPersistencePolicy.MAX_SNAPSHOT_SESSIONS, loaded.size)
        // Серверный список отсортирован от свежих к старым — хвост отбрасывается.
        assertEquals(many.take(OfflineSessionPersistencePolicy.MAX_SNAPSHOT_SESSIONS), loaded)
    }

    @Test
    fun blankSessionIdIsRejected() {
        val policy = newPolicy()
        assertFalse(policy.saveActiveTurn("  ", fullTurn()))
        assertNull(policy.loadActiveTurn(""))
        policy.clearActiveTurn("") // no-op, не бросает
    }

    // ---------- TTL ----------

    @Test
    fun expiredSessionSnapshotIsDroppedAndRemoved() {
        val store = FakeStore()
        val policy = newPolicy(store, ttlMillis = 60_000L)
        assertTrue(policy.saveSessions(listOf(session("s1"))))

        now += 60_001L // за пределами TTL
        assertNull(policy.loadSessions())
        // Recovery: протухшая запись удалена из хранилища, а не просто скрыта.
        assertFalse(store.map.containsKey(OfflineSessionPersistencePolicy.KEY_SESSIONS))
    }

    @Test
    fun snapshotWithinTtlIsReturned() {
        val policy = newPolicy(ttlMillis = 60_000L)
        assertTrue(policy.saveSessions(listOf(session("s1"))))
        now += 59_999L
        assertNotNull(policy.loadSessions())
    }

    @Test
    fun expiredActiveTurnIsDroppedAndRemoved() {
        val store = FakeStore()
        val policy = newPolicy(store, ttlMillis = 30_000L)
        assertTrue(policy.saveActiveTurn("s1", fullTurn()))

        now += 31_000L
        assertNull(policy.loadActiveTurn("s1"))
        assertFalse(store.map.containsKey(OfflineSessionPersistencePolicy.KEY_TURN_PREFIX + "s1"))
    }

    @Test
    fun ttlWrittenAtSaveTimeGovernsLaterReads() {
        val store = FakeStore()
        // Сохранили с TTL 10 с; позже политику пересоздали с щедрым TTL —
        // запись всё равно протухает по ttl_ms из конверта.
        newPolicy(store, ttlMillis = 10_000L).saveActiveTurn("s1", fullTurn())
        now += 15_000L
        assertNull(newPolicy(store, ttlMillis = 600_000L).loadActiveTurn("s1"))
    }

    @Test
    fun zeroTtlMakesEverySnapshotExpireImmediately() {
        val policy = newPolicy(ttlMillis = 0L)
        assertTrue(policy.saveSessions(listOf(session("s1"))))
        now += 1L
        assertNull(policy.loadSessions())
    }

    // ---------- повреждённые данные ----------

    @Test
    fun corruptJsonIsDroppedAndKeyRemoved() {
        val store = FakeStore()
        store.map[OfflineSessionPersistencePolicy.KEY_SESSIONS] = "{не json"
        val policy = newPolicy(store)
        assertNull(policy.loadSessions())
        assertFalse(store.map.containsKey(OfflineSessionPersistencePolicy.KEY_SESSIONS))
    }

    @Test
    fun foreignFormatVersionIsDroppedAndKeyRemoved() {
        val store = FakeStore()
        val key = OfflineSessionPersistencePolicy.KEY_TURN_PREFIX + "s1"
        store.map[key] = """{"v":99,"saved_at":$now,"active_turn":{"is_streaming":true}}"""
        assertNull(newPolicy(store).loadActiveTurn("s1"))
        assertFalse(store.map.containsKey(key))
    }

    @Test
    fun missingTimestampIsTreatedAsCorrupt() {
        val store = FakeStore()
        store.map[OfflineSessionPersistencePolicy.KEY_SESSIONS] = """{"v":1,"sessions":[]}"""
        assertNull(newPolicy(store).loadSessions())
        assertFalse(store.map.containsKey(OfflineSessionPersistencePolicy.KEY_SESSIONS))
    }

    @Test
    fun nonObjectPayloadIsTreatedAsCorrupt() {
        val store = FakeStore()
        store.map[OfflineSessionPersistencePolicy.KEY_SESSIONS] = "[1,2,3]"
        assertNull(newPolicy(store).loadSessions())
    }

    @Test
    fun futureTimestampBeyondSkewToleranceIsDropped() {
        val store = FakeStore()
        val policy = newPolicy(store)
        assertTrue(policy.saveSessions(listOf(session("s1"))))
        // Часы уехали в прошлое: сохранённая метка «из будущего» дальше допуска.
        now -= OfflineSessionPersistencePolicy.CLOCK_SKEW_TOLERANCE_MS + 1_000L
        assertNull(policy.loadSessions())
        assertFalse(store.map.containsKey(OfflineSessionPersistencePolicy.KEY_SESSIONS))
    }

    @Test
    fun smallClockSkewIntoFutureIsTolerated() {
        val policy = newPolicy()
        assertTrue(policy.saveSessions(listOf(session("s1"))))
        now -= 1_000L // в пределах допуска
        assertNotNull(policy.loadSessions())
    }

    @Test
    fun garbageRowsAreSkippedButValidOnesSurvive() {
        val store = FakeStore()
        val policy = newPolicy(store)
        assertTrue(policy.saveSessions(listOf(session("s1"), session("s2"))))
        // Внедряем мусорные строки в сохранённый JSON: частичное повреждение.
        val key = OfflineSessionPersistencePolicy.KEY_SESSIONS
        val raw = store.map[key]!!
        store.map[key] = raw.replace(
            "\"sessions\":[",
            "\"sessions\":[42,\"мусор\",null,{\"id\":\"\"},{\"no\":\"id\"},",
        )
        val snapshot = policy.loadSessions()
        assertNotNull(snapshot)
        assertEquals(listOf("s1", "s2"), snapshot!!.sessions.map { it.id })
    }

    @Test
    fun garbageActiveTurnBlockFallsBackToIdleInSessionRow() {
        val store = FakeStore()
        val policy = newPolicy(store)
        assertTrue(policy.saveSessions(listOf(session("s1", activeTurn = fullTurn()))))
        val key = OfflineSessionPersistencePolicy.KEY_SESSIONS
        // active_turn пропал из строки — rowToTurn вернёт idle-состояние.
        store.map[key] = store.map[key]!!.replace(
            "\"active_turn\":{",
            "\"active_turn_garbage\":{",
        )
        val snapshot = policy.loadSessions()
        assertNotNull(snapshot)
        assertEquals(ActiveTurnInfo(), snapshot!!.sessions.single().activeTurn)
    }

    @Test
    fun missingActiveTurnBlockInTurnSnapshotIsCorrupt() {
        val store = FakeStore()
        val key = OfflineSessionPersistencePolicy.KEY_TURN_PREFIX + "s1"
        store.map[key] = """{"v":1,"saved_at":$now,"session_id":"s1"}"""
        assertNull(newPolicy(store).loadActiveTurn("s1"))
        assertFalse(store.map.containsKey(key))
    }

    // ---------- падающее хранилище ----------

    @Test
    fun failingStoreNeverThrows() {
        val store = FakeStore(failing = true)
        val policy = newPolicy(store)
        assertFalse(policy.saveSessions(listOf(session("s1"))))
        assertFalse(policy.saveActiveTurn("s1", fullTurn()))
        assertNull(policy.loadSessions())
        assertNull(policy.loadActiveTurn("s1"))
        policy.clearActiveTurn("s1")
        policy.clear()
    }

    // ---------- гигиена и ключи ----------

    @Test
    fun clearRemovesOnlyPolicyKeys() {
        val store = FakeStore()
        val policy = newPolicy(store)
        // Чужие ключи (например, SessionOfflineCache) должны пережить clear().
        store.putString("sessions", "чужой кэш")
        store.putString("messages:s9", "чужой кэш")

        assertTrue(policy.saveSessions(listOf(session("s1"))))
        assertTrue(policy.saveActiveTurn("s1", fullTurn()))
        assertTrue(policy.saveActiveTurn("s2", fullTurn()))

        policy.clear()
        assertNull(policy.loadSessions())
        assertNull(policy.loadActiveTurn("s1"))
        assertNull(policy.loadActiveTurn("s2"))
        assertEquals(setOf("sessions", "messages:s9"), store.map.keys)
    }

    @Test
    fun clearActiveTurnRemovesOnlyThatSession() {
        val store = FakeStore()
        val policy = newPolicy(store)
        assertTrue(policy.saveActiveTurn("s1", fullTurn()))
        assertTrue(policy.saveActiveTurn("s2", fullTurn()))
        policy.clearActiveTurn("s1")
        assertNull(policy.loadActiveTurn("s1"))
        assertNotNull(policy.loadActiveTurn("s2"))
    }

    @Test
    fun keysDoNotCollideWithSessionOfflineCache() {
        val store = FakeStore()
        val policy = newPolicy(store)
        val cache = SessionOfflineCache(store, clock = { now })

        assertTrue(cache.saveSessions(listOf(session("cache-only"))))
        assertTrue(policy.saveSessions(listOf(session("policy-only"))))
        assertTrue(policy.saveActiveTurn("s1", fullTurn()))

        // Каждый видит только своё.
        assertEquals(listOf("cache-only"), cache.loadSessions()?.sessions?.map { it.id })
        assertEquals(listOf("policy-only"), policy.loadSessions()?.sessions?.map { it.id })

        policy.clear()
        assertNotNull(cache.loadSessions())
    }

    @Test
    fun newerSnapshotOverwritesOlder() {
        val policy = newPolicy()
        assertTrue(policy.saveSessions(listOf(session("s1"))))
        now += 5_000L
        assertTrue(policy.saveSessions(listOf(session("s2"))))
        val snapshot = policy.loadSessions()!!
        assertEquals(now, snapshot.savedAtEpochMillis)
        assertEquals(listOf("s2"), snapshot.sessions.map { it.id })
    }

    @Test
    fun negativeTtlIsNormalizedToZero() {
        val policy = newPolicy(ttlMillis = -100L)
        assertEquals(0L, policy.ttlMillis)
    }
}
