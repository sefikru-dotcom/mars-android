package ru.mars.mobile.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SpeechContentFilter] — the TTS speech filter that turns Mars's technical
 * markdown into clean speakable text. Pure JVM, no Android framework.
 */
class SpeechContentFilterTest {

    private val filter = SpeechContentFilter()

    // --- fenced code blocks ---------------------------------------------------------------

    @Test
    fun `fenced code block replaced with natural phrase`() {
        val input = "Вот решение:\n```kotlin\nfun main() {\n    println(\"hi\")\n}\n```\nГотово."
        val out = filter.filterForSpeech(input)
        assertFalse("code must not be spoken", out.contains("fun main"))
        assertFalse("fences must not be spoken", out.contains("```"))
        assertTrue(out.contains(SpeechContentFilter.CODE_PLACEHOLDER))
        assertTrue(out.contains("Вот решение:"))
        assertTrue(out.contains("Готово."))
    }

    @Test
    fun `fence without language tag is also removed`() {
        val out = filter.filterForSpeech("Смотрите:\n```\nrm -rf /tmp/x\n```")
        assertFalse(out.contains("rm -rf"))
        assertTrue(out.contains(SpeechContentFilter.CODE_PLACEHOLDER))
    }

    @Test
    fun `unclosed fence from streaming answer is not read verbatim`() {
        val out = filter.filterForSpeech("Начинаю так:\n```python\nimport os")
        assertFalse(out.contains("import os"))
        assertTrue(out.contains(SpeechContentFilter.CODE_PLACEHOLDER))
    }

    @Test
    fun `adjacent fences collapse into a single placeholder`() {
        val input = "Сделал две вещи:\n```a\nx\n```\n```b\ny\n```"
        val out = filter.filterForSpeech(input)
        assertEquals(1, Regex("\\(Блок кода выведен на экран\\)").findAll(out).count())
        assertTrue(out.contains("Сделал две вещи:"))
    }

    @Test
    fun `answer consisting only of code gets the dedicated sentence`() {
        val out = filter.filterForSpeech("```java\nSystem.out.println(1);\n```")
        assertEquals(SpeechContentFilter.CODE_ONLY_REPLY, out)
    }

    @Test
    fun `answer of only multiple code blocks gets the dedicated sentence`() {
        val out = filter.filterForSpeech("```a\nx\n```\n\n```b\ny\n```")
        assertEquals(SpeechContentFilter.CODE_ONLY_REPLY, out)
    }

    // --- inline code -----------------------------------------------------------------------

    @Test
    fun `inline code keeps clean name without backticks`() {
        val out = filter.filterForSpeech("Запусти `./gradlew build` и проверь `local.properties`.")
        assertFalse(out.contains("`"))
        assertTrue(out.contains("./gradlew build"))
        assertTrue(out.contains("local.properties"))
    }

    // --- links and images --------------------------------------------------------------------

    @Test
    fun `markdown link becomes plain anchor text`() {
        val out = filter.filterForSpeech("Смотри [документацию Марса](https://hermes-agent.nousresearch.com/docs).")
        assertTrue(out.contains("документацию Марса"))
        assertFalse("url must not be spoken", out.contains("hermes-agent"))
        assertFalse(out.contains("]"))
        assertFalse(out.contains("("))
    }

    @Test
    fun `image syntax is dropped entirely`() {
        val out = filter.filterForSpeech("Вот скрин: ![снимок экрана](https://x.ru/a.png) — всё видно.")
        assertFalse(out.contains("снимок экрана"))
        assertFalse(out.contains("x.ru"))
        assertTrue(out.contains("Вот скрин:"))
        assertTrue(out.contains("всё видно."))
    }

    @Test
    fun `bare url replaced with speakable word`() {
        val out = filter.filterForSpeech("Подробности: https://github.com/NousResearch/hermes-agent/issues/42 тут.")
        assertFalse(out.contains("github.com"))
        assertTrue(out.contains("ссылка"))
    }

    @Test
    fun `autolink in angle brackets is removed`() {
        val out = filter.filterForSpeech("Пиши на <https://mars.ru> или мне.")
        assertFalse(out.contains("mars.ru"))
    }

    // --- markdown decoration ------------------------------------------------------------------

    @Test
    fun `headings lose hashes`() {
        val out = filter.filterForSpeech("## Итоги\n### Детали\nТекст.")
        assertFalse(out.contains("#"))
        assertTrue(out.contains("Итоги"))
        assertTrue(out.contains("Детали"))
    }

    @Test
    fun `bold italic and strikethrough markers removed but words kept`() {
        val out = filter.filterForSpeech("Это **жирный**, *курсив*, __подчёркнутый__ и ~~зачёркнутый~~ текст.")
        assertFalse(out.contains("*"))
        assertFalse(out.contains("~~"))
        assertTrue(out.contains("жирный"))
        assertTrue(out.contains("курсив"))
        assertTrue(out.contains("подчёркнутый"))
        assertTrue(out.contains("зачёркнутый"))
    }

    @Test
    fun `blockquote markers removed`() {
        val out = filter.filterForSpeech("> Цитата Марса\n> вторая строка")
        assertFalse(out.contains(">"))
        assertTrue(out.contains("Цитата Марса"))
        assertTrue(out.contains("вторая строка"))
    }

    @Test
    fun `list bullets removed`() {
        val out = filter.filterForSpeech("- первый\n* второй\n+ третий")
        assertFalse(out.contains("-"))
        assertFalse(out.contains("*"))
        assertTrue(out.contains("первый"))
        assertTrue(out.contains("третий"))
    }

    @Test
    fun `horizontal rules removed`() {
        val out = filter.filterForSpeech("До\n\n---\n\nПосле")
        assertTrue(out.contains("До"))
        assertTrue(out.contains("После"))
        val lines = out.lines().map { it.trim() }
        assertFalse(lines.contains("---"))
    }

    @Test
    fun `table pipes and separator row smoothed out`() {
        val input = "| Параметр | Значение |\n|---|---|\n| Токены | 1200 |"
        val out = filter.filterForSpeech(input)
        assertFalse("pipes must not be spoken", out.contains("|"))
        assertFalse("separator row must be gone", out.contains("---"))
        assertTrue(out.contains("Параметр"))
        assertTrue(out.contains("Токены"))
        assertTrue(out.contains("1200"))
    }

    @Test
    fun `residual html tags removed`() {
        val out = filter.filterForSpeech("Текст <b>важно</b> и <br/> продолжение.")
        assertFalse(out.contains("<b>"))
        assertFalse(out.contains("<br/>"))
        assertTrue(out.contains("важно"))
        assertTrue(out.contains("продолжение"))
    }

    @Test
    fun `snake_case identifiers keep their underscores`() {
        val out = filter.filterForSpeech("Проверь файл `mars_local_config` и поле _курсив_.")
        assertTrue(out.contains("mars_local_config"))
        assertTrue(out.contains("курсив"))
        assertFalse("emphasis underscores must be stripped", out.contains("_курсив_"))
    }

    // --- edge cases ------------------------------------------------------------------------------

    @Test
    fun `blank and whitespace input produce empty string`() {
        assertEquals("", filter.filterForSpeech(""))
        assertEquals("", filter.filterForSpeech("   \n\t  "))
    }

    @Test
    fun `plain prose passes through almost unchanged`() {
        val out = filter.filterForSpeech("Привет! Я Марс. Чем помочь?")
        assertEquals("Привет! Я Марс. Чем помочь?", out)
    }

    @Test
    fun `windows line endings are normalized`() {
        val out = filter.filterForSpeech("Строка один\r\n## Заголовок\r\n```py\r\ncode\r\n```")
        assertFalse(out.contains("code"))
        assertTrue(out.contains("Заголовок"))
        assertTrue(out.contains(SpeechContentFilter.CODE_PLACEHOLDER))
    }

    @Test
    fun `mixed technical answer keeps all prose and no syntax`() {
        val input = """
            # Отчёт

            Я проверил **сервер**: [логи](https://mars.ru/logs) чистые.

            ```bash
            systemctl status mars
            ```

            | Метрика | Значение |
            |---|---|
            | CPU | 12% |

            Запусти `docker compose up -d` — подробнее на https://mars.ru/docs.
        """.trimIndent()
        val out = filter.filterForSpeech(input)
        assertTrue(out.contains("Отчёт"))
        assertTrue(out.contains("сервер"))
        assertTrue(out.contains("логи"))
        assertTrue(out.contains(SpeechContentFilter.CODE_PLACEHOLDER))
        assertTrue(out.contains("CPU"))
        assertTrue(out.contains("12%"))
        assertTrue(out.contains("docker compose up -d"))
        listOf("#", "**", "```", "|", "https://").forEach {
            assertFalse("output must not contain «$it»", out.contains(it))
        }
    }

    // --- emoji and decorative glyphs -------------------------------------------------------------

    @Test
    fun `emoji are dropped but sentence words kept`() {
        val out = filter.filterForSpeech("Готово ✅\n🚀 Запустил **деплой** ⭐")
        assertFalse(out.contains("✅"))
        assertFalse(out.contains("🚀"))
        assertFalse(out.contains("⭐"))
        assertTrue(out.contains("Готово"))
        assertTrue(out.contains("Запустил деплой"))
    }

    @Test
    fun `zwj sequences and variation selectors leave no residue`() {
        val out = filter.filterForSpeech("Отчёт 👨\u200D💻 обновлён ✔\uFE0F")
        assertFalse(out.contains("👨"))
        assertFalse(out.contains("💻"))
        assertFalse(out.contains("✔"))
        assertFalse(out.contains("\u200D"))
        assertFalse(out.contains("\uFE0F"))
        assertTrue(out.contains("Отчёт"))
        assertTrue(out.contains("обновлён"))
    }

    @Test
    fun `unicode arrow glyph is removed`() {
        val out = filter.filterForSpeech("было → стало")
        assertFalse(out.contains("→"))
        assertTrue(out.contains("было"))
        assertTrue(out.contains("стало"))
    }

    @Test
    fun `emoji-only message yields empty speakable text`() {
        assertEquals("", filter.filterForSpeech("✅🎉👍"))
    }

    @Test
    fun `code-only answer with emoji still gets the dedicated sentence`() {
        val out = filter.filterForSpeech("✅\n```sh\n./deploy.sh\n```")
        assertEquals(SpeechContentFilter.CODE_ONLY_REPLY, out)
    }

    // --- more markdown edge cases ------------------------------------------------------------------

    @Test
    fun `link with empty anchor leaves no brackets`() {
        val out = filter.filterForSpeech("Смотри [](https://x.ru) сюда.")
        assertFalse(out.contains("["))
        assertFalse(out.contains("]"))
        assertFalse(out.contains("x.ru"))
        assertTrue(out.contains("Смотри"))
        assertTrue(out.contains("сюда."))
    }

    @Test
    fun `reference-style link keeps anchor text only`() {
        val out = filter.filterForSpeech("Читай [доки][d] и всё.\n\n[d]: https://mars.ru/docs")
        assertTrue(out.contains("доки"))
        assertFalse(out.contains("[d]"))
        assertFalse(out.contains("mars.ru"))
    }

    @Test
    fun `double-backtick inline code keeps clean flag name`() {
        val out = filter.filterForSpeech("Флаг ``--force`` работает.")
        assertFalse(out.contains("`"))
        assertTrue(out.contains("--force"))
    }

    @Test
    fun `numbered list content is preserved`() {
        val out = filter.filterForSpeech("План:\n1. первое\n2. второе")
        assertTrue(out.contains("первое"))
        assertTrue(out.contains("второе"))
        assertFalse(out.contains("#"))
    }

    // --- волна 20: отступы, списки, пунктуация -----------------------------------------------

    @Test
    fun `bullet markers never reach the engine as punctuation`() {
        val out = filter.stripForSpeech("- первый\n  - вложенный\n• третий")
        assertFalse("маркер списка не должен дойти до синтеза: $out", out.contains("-"))
        assertFalse("bullet glyph must be gone", out.contains("•"))
        assertTrue(out.contains("первый"))
        assertTrue(out.contains("вложенный"))
        assertTrue(out.contains("третий"))
    }

    @Test
    fun `numbered list keeps its number and loses the indent`() {
        val out = filter.stripForSpeech("План:\n   1. первое\n   2. второе")
        assertTrue("номер пункта сохраняется: $out", out.contains("1. первое"))
        assertFalse("отступ не должен остаться", out.contains("   1."))
        assertTrue(out.contains("2. второе"))
    }

    @Test
    fun `line breaks become one speakable line`() {
        val out = filter.stripForSpeech("Готово\nДальше проверяю логи\n\nи всё.")
        assertFalse("переводы строк дают синтезатору долгие паузы: $out", out.contains("\n"))
        assertTrue("строка без точки получает точку: $out", out.contains("Готово."))
        assertTrue(out.contains("Дальше проверяю логи."))
        assertTrue(out.endsWith("и всё."))
    }

    @Test
    fun `line break after punctuation does not double the pause`() {
        val out = filter.stripForSpeech("Первое предложение.\nВторое предложение.")
        assertEquals("Первое предложение. Второе предложение.", out)
    }

    @Test
    fun `indented block loses leading spaces`() {
        val out = filter.stripForSpeech("    Отступ четыре пробела\n        и восемь.")
        assertFalse("двойных пробелов быть не должно: $out", out.contains("  "))
        assertTrue(out.startsWith("Отступ"))
    }

    @Test
    fun `doubled punctuation collapses`() {
        val out = filter.stripForSpeech("Привет .. как дела ,, — — ?!")
        assertFalse("сдвоенная пунктуация остаётся: $out", out.contains(".."))
        assertFalse(out.contains(",,"))
        assertFalse(out.contains("—"))
        assertFalse("пробел перед знаком", out.contains(" ?"))
        assertEquals("Привет. как дела?", out)
    }

    @Test
    fun `dash becomes a comma pause and is never spoken`() {
        val out = filter.stripForSpeech("Марс — это ассистент.")
        assertFalse("тире не должно доехать до синтеза: $out", out.contains("—"))
        assertEquals("Марс, это ассистент.", out)
    }

    @Test
    fun `box drawing and status emoji are dropped`() {
        val out = filter.stripForSpeech("│ шаг 1 ─── шаг 2 ═══ конец ⏳ 🎙")
        listOf("│", "─", "═", "⏳", "🎙").forEach {
            assertFalse("декоративный символ «$it» остался: $out", out.contains(it))
        }
        assertTrue(out.contains("шаг 1"))
        assertTrue(out.contains("конец"))
    }

    @Test
    fun `quotes and brackets are removed but their text is kept`() {
        val out = filter.stripForSpeech("Он сказал «проверь» (и это важно).")
        assertFalse(out.contains("«"))
        assertFalse(out.contains("»"))
        assertFalse(out.contains("("))
        assertTrue(out.contains("проверь"))
        assertTrue(out.contains("и это важно"))
    }

    @Test
    fun `code placeholder keeps its parentheses`() {
        val out = filter.stripForSpeech("Смотри:\n```kt\nval x = 1\n```\nГотово.")
        assertTrue("фраза о коде произносится целиком: $out", out.contains(SpeechContentFilter.CODE_PLACEHOLDER))
        assertFalse(out.contains("val x"))
    }

    @Test
    fun `stripForSpeech and filterForSpeech agree`() {
        val input = "# Итоги\n\n- **первый** пункт\n- второй пункт\n\nГотово ✅"
        assertEquals(filter.filterForSpeech(input), filter.stripForSpeech(input))
    }

    @Test
    fun `long markdown answer becomes flat speech`() {
        val input = """
            ## Отчёт

            - Проверил **три** сервера — всё чисто.
            - Перезапустил сервис: 12% CPU.

            Готово.
        """.trimIndent()
        val out = filter.stripForSpeech(input)
        assertFalse("переводы строк остались: $out", out.contains("\n"))
        assertFalse(out.contains("  "))
        assertFalse(out.contains("-"))
        assertFalse(out.contains("#"))
        assertTrue(out.contains("Проверил три сервера, всё чисто."))
        assertTrue(out.contains("Перезапустил сервис: 12% CPU."))
    }
}
