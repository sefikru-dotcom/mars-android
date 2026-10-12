package ru.mars.network.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TolerantJson] unit tests: the WebUI payloads are hand-serialised Python dicts, so
 * every accessor must degrade safely on absent/null/mis-typed fields instead of
 * throwing — this is the parse-safety contract the three wave-3 repositories rely on.
 */
class TolerantJsonTest {

    @Test
    fun `parseMap returns null on malformed or non-object bodies`() {
        assertNull(TolerantJson.parseMap(""))
        assertNull(TolerantJson.parseMap("not json"))
        assertNull(TolerantJson.parseMap("[1,2]"))
        assertEquals(mapOf<String, Any?>("a" to 1.0), TolerantJson.parseMap("{\"a\":1}"))
    }

    @Test
    fun `parseList returns null on malformed or non-array bodies`() {
        assertNull(TolerantJson.parseList("{\"a\":1}"))
        assertEquals(listOf<Any?>(1.0, "x"), TolerantJson.parseList("[1,\"x\"]"))
    }

    @Test
    fun `string treats blank and null as absent`() {
        val map = TolerantJson.parseMap("{\"a\":\"x\",\"b\":\"  \",\"c\":null,\"d\":7}")!!
        assertEquals("x", TolerantJson.string(map, "a"))
        assertNull(TolerantJson.string(map, "b"))
        assertNull(TolerantJson.string(map, "c"))
        assertEquals("7", TolerantJson.string(map, "d"))
        assertNull(TolerantJson.string(map, "missing"))
        assertNull(TolerantJson.string(null, "a"))
    }

    @Test
    fun `bool accepts python-ish spellings and falls back to default`() {
        val map = TolerantJson.parseMap(
            "{\"t\":true,\"f\":false,\"one\":1,\"zero\":0,\"str\":\"true\",\"strOne\":\"1\",\"junk\":\"yes?\"}"
        )!!
        assertTrue(TolerantJson.bool(map, "t"))
        assertFalse(TolerantJson.bool(map, "f"))
        assertTrue(TolerantJson.bool(map, "one"))
        assertFalse(TolerantJson.bool(map, "zero"))
        assertTrue(TolerantJson.bool(map, "str"))
        assertTrue(TolerantJson.bool(map, "strOne"))
        assertFalse(TolerantJson.bool(map, "junk"))
        assertFalse(TolerantJson.bool(map, "missing"))
        // changed:true default — a payload without the flag is a FULL board.
        assertTrue(TolerantJson.bool(map, "missing", default = true))
    }

    @Test
    fun `numeric accessors tolerate numbers and numeric strings, reject junk`() {
        val map = TolerantJson.parseMap(
            "{\"n\":42,\"s\":\"43\",\"f\":4.9,\"fs\":\"4.5\",\"empty\":\"\",\"junk\":\"abc\",\"big\":1700000000}"
        )!!
        assertEquals(42, TolerantJson.int(map, "n"))
        assertEquals(43, TolerantJson.int(map, "s"))
        assertEquals(4L, TolerantJson.longOrNull(map, "f"))
        assertEquals(4L, TolerantJson.longOrNull(map, "fs"))
        assertEquals(1700000000L, TolerantJson.longOrNull(map, "big"))
        // kanban "empty string is null" columns (goal_max_turns) must not become 0.
        assertNull(TolerantJson.longOrNull(map, "empty"))
        assertNull(TolerantJson.intOrNull(map, "empty"))
        assertNull(TolerantJson.longOrNull(map, "junk"))
        assertNull(TolerantJson.longOrNull(map, "missing"))
        assertEquals(-1, TolerantJson.int(map, "missing", default = -1))
        assertEquals(4.5, TolerantJson.doubleOrNull(map, "fs")!!, 0.0001)
        assertNull(TolerantJson.doubleOrNull(map, "junk"))
    }

    @Test
    fun `stringListOrNull distinguishes absent from explicitly empty`() {
        val map = TolerantJson.parseMap("{\"none\":null,\"empty\":[],\"mixed\":[\"a\",null,3]}")!!
        assertNull(TolerantJson.stringListOrNull(map, "none"))
        assertNull(TolerantJson.stringListOrNull(map, "missing"))
        assertEquals(emptyList<String>(), TolerantJson.stringListOrNull(map, "empty"))
        assertEquals(listOf("a", "3"), TolerantJson.stringListOrNull(map, "mixed"))
        assertEquals(emptyList<String>(), TolerantJson.stringListOrEmpty(map, "none"))
    }

    @Test
    fun `intCounts keeps parsable entries and drops junk`() {
        val map = TolerantJson.parseMap(
            "{\"by_status\":{\"ready\":3,\"done\":\"2\",\"bad\":\"x\",\"\":9},\"not_a_map\":5}"
        )!!
        assertEquals(mapOf("ready" to 3, "done" to 2), TolerantJson.intCounts(map, "by_status"))
        assertEquals(emptyMap<String, Int>(), TolerantJson.intCounts(map, "not_a_map"))
        assertEquals(emptyMap<String, Int>(), TolerantJson.intCounts(map, "missing"))
    }

    @Test
    fun `mapField and listField degrade on wrong types`() {
        val map = TolerantJson.parseMap("{\"obj\":{\"k\":1},\"str\":\"x\",\"arr\":[1],\"num\":2}")!!
        assertEquals(mapOf<String, Any?>("k" to 1.0), TolerantJson.mapField(map, "obj"))
        assertNull(TolerantJson.mapField(map, "str"))
        assertNull(TolerantJson.mapField(map, "num"))
        assertEquals(listOf<Any?>(1.0), TolerantJson.listField(map, "arr"))
        assertEquals(emptyList<Any?>(), TolerantJson.listField(map, "obj"))
    }

    @Test
    fun `stringMap drops blank keys and values`() {
        val map = TolerantJson.parseMap("{\"aliases\":{\"a\":\"b\",\"\":\"x\",\"c\":\"\",\"d\":null}}")!!
        assertEquals(mapOf("a" to "b"), TolerantJson.stringMap(map, "aliases"))
    }

    @Test
    fun `toJson round-trips a map`() {
        val json = TolerantJson.toJson(mapOf("id" to "x"))
        assertEquals(mapOf<String, Any?>("id" to "x"), TolerantJson.parseMap(json))
    }
}
