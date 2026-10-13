package ru.mars.network.repository

/**
 * Single RFC 8259 §7–compliant escaper for every hand-built JSON request body in
 * this module (`/api/chat/start`, `/api/chat`, `/api/chat/steer`, approval/clarify
 * respond, session-autonomy toggle, pairing exchange/login).
 *
 * Why this exists: the server parses bodies with Python `json.loads` in STRICT
 * mode (hermes-webui api/helpers.py `read_body` → `ValueError('Invalid JSON
 * body')` → HTTP 400 in routes.py), and strict JSON forbids RAW control
 * characters U+0000..U+001F inside strings. The previous per-repository copies
 * of this escaper handled only `\n`, `\r` and `\t`, so pasted terminal output
 * (ANSI `ESC[…` sequences) or clipboard text with NUL/form-feed/vertical-tab
 * produced a malformed body and the request died with 400 before reaching the
 * handler.
 *
 * Rules:
 * - `"` → `\"`, `\` → `\\`;
 * - `\b`, `\f`, `\n`, `\r`, `\t` use the short named escapes;
 * - every other character below 0x20 → `\u00XX` (lowercase hex, Moshi/Jackson style);
 * - everything else (including DEL and non-ASCII) is passed through verbatim —
 *   the body is UTF-8 encoded, which the JSON spec allows unescaped.
 *
 * Internal, stateless, allocation-light: keeps the hand-built request payloads
 * byte-identical for ordinary text (backward compatible), escaping ONLY what the
 * spec requires in addition.
 */
internal object JsonStringEncoding {

    private val HEX = "0123456789abcdef".toCharArray()

    /** Wraps [value] in double quotes with all mandatory JSON escapes applied. */
    fun escape(value: String): String = buildString(value.length + 16) {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\u0008' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) {
                    append("\\u00")
                    append(HEX[(c.code shr 4) and 0xF])
                    append(HEX[c.code and 0xF])
                } else {
                    append(c)
                }
            }
        }
        append('"')
    }
}
