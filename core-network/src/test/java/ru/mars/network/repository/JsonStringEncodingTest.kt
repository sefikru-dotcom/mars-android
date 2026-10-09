package ru.mars.network.repository

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit contract for [JsonStringEncoding]: the output must be a valid JSON string
 * literal under the STRICT reading the server applies (Python `json.loads`),
 * i.e. no raw character below 0x20 may survive, and parsing must round-trip.
 */
class JsonStringEncodingTest {

    @Test
    fun ordinaryTextIsPassedThroughUnchanged() {
        assertEquals("\"hello мир 123 /{}[]:,.\"", JsonStringEncoding.escape("hello мир 123 /{}[]:,."))
    }

    @Test
    fun escapesQuoteAndBackslash() {
        assertEquals("\"a\\\"b\\\\c\"", JsonStringEncoding.escape("a\"b\\c"))
    }

    @Test
    fun usesShortNamedEscapesForCommonWhitespace() {
        assertEquals("\"\\b\\f\\n\\r\\t\"", JsonStringEncoding.escape("\b\u000C\n\r\t"))
    }

    @Test
    fun escapesEveryControlCharacterBelow0x20() {
        for (code in 0 until 0x20) {
            val escaped = JsonStringEncoding.escape(code.toChar().toString())
            assertTrue(
                "U+%04x survived raw in $escaped".format(code),
                escaped.none { it.code < 0x20 }
            )
            // Named escapes for \b \f \n \r \t, \u00XX for the rest.
            val expected = when (code) {
                0x08 -> "\"\\b\""
                0x09 -> "\"\\t\""
                0x0A -> "\"\\n\""
                0x0C -> "\"\\f\""
                0x0D -> "\"\\r\""
                else -> "\"\\u%04x\"".format(code)
            }
            assertEquals(expected, escaped)
        }
    }

    @Test
    fun delAndC1AreLegalRawAndNotEscaped() {
        // JSON only mandates escaping below 0x20; DEL (0x7F) may stay verbatim.
        assertEquals("\"\u007F\"", JsonStringEncoding.escape("\u007F"))
    }

    @Test
    fun moshiRoundTripsNastyCorpus() {
        val moshi = Moshi.Builder().build()
        val adapter = moshi.adapter(String::class.java)
        val corpus = listOf(
            "plain",
            "with \"quotes\" and \\backslashes\\",
            "tab\there\nnewline\r\nwindows",
            "ansi \u001B[31;1mRED\u001B[0m reset",
            "nul\u0000inside",
            "vt\u000B ff\u000C bs\b",
            "кириллица 😀 emoji",
            "\u0001\u0002\u001E\u001F boundary controls",
        )
        for (original in corpus) {
            val escaped = JsonStringEncoding.escape(original)
            assertTrue("raw control char survived: $escaped", escaped.none { it.code < 0x20 })
            assertEquals(original, adapter.fromJson(escaped))
        }
    }
}
