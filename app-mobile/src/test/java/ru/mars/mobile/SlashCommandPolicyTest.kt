package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чистая политика слэш-команд: разбор, таблица `/help`, валидация аргументов.
 *
 * Тесты держат контракт «что клиент считает командой и что делает с аргументами»:
 * неизвестное имя обязано отличаться от обычного текста (иначе оно уйдёт в модель
 * промптом), а недопустимый аргумент — от валидного (иначе `/truncate -1` уехал бы
 * на сервер).
 */
class SlashCommandPolicyTest {

    // --- Разбор -------------------------------------------------------------------------

    @Test
    fun everySpecNameIsParsedAsItself() {
        SlashCommandPolicy.SPECS.forEach { spec ->
            val parsed = SlashCommandPolicy.parse("/${spec.name}")
            assertEquals("имя команды ${spec.name}", spec.name, parsed?.name)
            assertEquals("аргументы /${spec.name}", "", parsed?.args)
        }
    }

    @Test
    fun parseKeepsArgumentsVerbatimAfterName() {
        assertEquals("Сдать отчёт", SlashCommandPolicy.parse("/title Сдать отчёт")?.args)
        assertEquals("название", SlashCommandPolicy.parse("/branch название")?.args)
        assertEquals("уточни цифры", SlashCommandPolicy.parse("/steer уточни цифры")?.args)
        // Внутренние пробелы аргумента не схлопываются: заголовок остаётся как набран.
        assertEquals("Сдать   отчёт", SlashCommandPolicy.parse("/title   Сдать   отчёт")?.args)
    }

    @Test
    fun parseTrimsSurroundingWhitespaceAndLowercasesName() {
        val parsed = SlashCommandPolicy.parse("   /TITLE Отчёт  ")
        assertEquals("title", parsed?.name)
        assertEquals("Отчёт", parsed?.args)
    }

    @Test
    fun tabsSeparateNameAndArguments() {
        val parsed = SlashCommandPolicy.parse("/truncate\t12")
        assertEquals("truncate", parsed?.name)
        assertEquals("12", parsed?.args)
    }

    @Test
    fun plainTextIsNotACommand() {
        assertNull(SlashCommandPolicy.parse("привет"))
        assertNull(SlashCommandPolicy.parse("/"))
        assertNull(SlashCommandPolicy.parse(""))
        assertNull(SlashCommandPolicy.parse("   "))
        assertFalse(SlashCommandPolicy.isCommand("слэш /title внутри текста"))
        assertFalse(SlashCommandPolicy.isCommand("//"))
    }

    @Test
    fun loneSlashIsNotACommandButSlashWithNameIs() {
        // Одинокий `/` — недописанный ввод, а не имя команды.
        assertNull(SlashCommandPolicy.parse("/   "))
        assertTrue(SlashCommandPolicy.isCommand("/help"))
    }

    @Test
    fun unknownNameIsNotCommandTextAndIsReportedHonestly() {
        val resolution = SlashCommandPolicy.resolve("/goalkeeper")
        assertTrue(resolution is SlashCommandResolution.Unknown)
        assertEquals("goalkeeper", (resolution as SlashCommandResolution.Unknown).command.name)
        val message = SlashCommandPolicy.unknownMessage("goalkeeper")
        assertTrue(message.contains("/goalkeeper"))
        assertTrue(message.contains("/help"))
    }

    @Test
    fun knownCommandResolvesReadyWithItsSpec() {
        val resolution = SlashCommandPolicy.resolve("/TITLE Отчёт")
        assertTrue(resolution is SlashCommandResolution.Ready)
        val ready = resolution as SlashCommandResolution.Ready
        assertEquals("title", ready.command.name)
        assertEquals("Отчёт", ready.command.args)
        assertEquals("/title <текст>", ready.spec.usage)
    }

    @Test
    fun textWithoutSlashResolvesToNull() {
        assertNull(SlashCommandPolicy.resolve("просто текст"))
    }

    // --- Команды без транспорта ---------------------------------------------------------

    @Test
    fun webUiOnlyCommandsAreUnavailableNotUnknown() {
        SlashCommandPolicy.UNAVAILABLE_NAMES.forEach { name ->
            val resolution = SlashCommandPolicy.resolve("/$name")
            assertTrue("/$name обязан быть Unavailable", resolution is SlashCommandResolution.Unavailable)
            assertEquals(name, (resolution as SlashCommandResolution.Unavailable).command.name)
        }
    }

    @Test
    fun unavailableMessageSaysPhoneAndPointsToHelp() {
        val message = SlashCommandPolicy.unavailableMessage("model")
        assertTrue(message.contains("/model"))
        assertTrue(message.contains("недоступна на телефоне"))
        assertTrue(message.contains("/help"))
    }

    @Test
    fun unavailableAndAvailableSetsDoNotOverlap() {
        val available = SlashCommandPolicy.SPECS.map { it.name }.toSet()
        val overlap = available intersect SlashCommandPolicy.UNAVAILABLE_NAMES.toSet()
        assertEquals("команда не может быть и доступной, и недоступной", emptySet<String>(), overlap)
    }

    // --- /help --------------------------------------------------------------------------

    @Test
    fun helpListsEveryAvailableCommandWithDescription() {
        val help = SlashCommandPolicy.helpText()
        SlashCommandPolicy.SPECS.forEach { spec ->
            assertTrue("в /help нет ${spec.usage}", help.contains(spec.usage))
            assertTrue("у ${spec.name} нет описания", help.contains(spec.description))
        }
    }

    @Test
    fun helpMentionsUnavailableCommandsAsUnavailable() {
        val help = SlashCommandPolicy.helpText()
        assertTrue(help.contains("Недоступно на телефоне"))
        SlashCommandPolicy.UNAVAILABLE_NAMES.forEach { name ->
            assertTrue("в /help нет упоминания /$name", help.contains("/$name"))
        }
    }

    // --- /truncate ----------------------------------------------------------------------

    @Test
    fun truncateAcceptsNonNegativeIntegers() {
        assertEquals(0, SlashCommandPolicy.truncateCount("0") ?: -1)
        assertEquals(5, SlashCommandPolicy.truncateCount("5") ?: -1)
        assertEquals(42, SlashCommandPolicy.truncateCount("  42  ") ?: -1)
        assertEquals(7, SlashCommandPolicy.truncateCount("007") ?: -1)
    }

    @Test
    fun truncateRejectsAnythingButADigitRun() {
        assertNull(SlashCommandPolicy.truncateCount(""))
        assertNull(SlashCommandPolicy.truncateCount("   "))
        assertNull(SlashCommandPolicy.truncateCount("-1"))
        assertNull(SlashCommandPolicy.truncateCount("+3"))
        assertNull(SlashCommandPolicy.truncateCount("5.0"))
        assertNull(SlashCommandPolicy.truncateCount("5 5"))
        assertNull(SlashCommandPolicy.truncateCount("5 сообщений"))
        assertNull(SlashCommandPolicy.truncateCount("abc"))
        assertNull(SlashCommandPolicy.truncateCount("99999999999999999999"))
    }

    @Test
    fun truncateUsageShowsTheExpectedShape() {
        assertTrue(SlashCommandPolicy.TRUNCATE_USAGE.contains("/truncate <N>"))
        assertTrue(SlashCommandPolicy.TRUNCATE_USAGE.contains("неотрицательное"))
    }

    // --- /reasoning ---------------------------------------------------------------------

    @Test
    fun reasoningWithoutArgsAsksForCurrentState() {
        assertEquals(ReasoningRequest.ShowCurrent, SlashCommandPolicy.reasoningRequest(""))
        assertEquals(ReasoningRequest.ShowCurrent, SlashCommandPolicy.reasoningRequest("   "))
    }

    @Test
    fun reasoningShowAndHideMapToDisplayFlags() {
        assertEquals(ReasoningRequest.Show, SlashCommandPolicy.reasoningRequest("show"))
        assertEquals(ReasoningRequest.Show, SlashCommandPolicy.reasoningRequest("on"))
        assertEquals(ReasoningRequest.Hide, SlashCommandPolicy.reasoningRequest("hide"))
        assertEquals(ReasoningRequest.Hide, SlashCommandPolicy.reasoningRequest("OFF"))
    }

    @Test
    fun reasoningEffortsMatchTheServerLadder() {
        listOf("none", "minimal", "low", "medium", "high", "xhigh", "max").forEach { effort ->
            assertEquals(effort, (SlashCommandPolicy.reasoningRequest(effort) as ReasoningRequest.Effort).effort)
        }
        // Регистр не важен: команда нормализуется к серверным значениям.
        assertEquals("high", (SlashCommandPolicy.reasoningRequest(" HIGH ") as ReasoningRequest.Effort).effort)
    }

    @Test
    fun reasoningRejectsUnknownValues() {
        assertNull(SlashCommandPolicy.reasoningRequest("ultra"))
        assertNull(SlashCommandPolicy.reasoningRequest("high max"))
        assertNull(SlashCommandPolicy.reasoningRequest("medium,"))
        assertTrue(SlashCommandPolicy.REASONING_USAGE.contains("show|hide"))
    }

    // --- Согласованность с /goal ---------------------------------------------------------

    @Test
    fun goalIsInTheTableAndOwnedByGoalLoopPolicy() {
        assertTrue(SlashCommandPolicy.isCommand("/goal status"))
        // Свою логику `/goal` не теряет: оба парсера видят одну и ту же команду.
        assertTrue(GoalLoopPolicy.isCommand("/goal status"))
        assertEquals("status", GoalLoopPolicy.commandArgs("/goal status"))
        assertEquals("status", SlashCommandPolicy.parse("/goal status")?.args)
    }
}
