package ru.mars.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт проводки слэш-команд: команда перехватывается ДО отправки в модель, ответ
 * идёт строкой статуса (а не пузырём транскрипта), и исполнение использует уже
 * существующие транспорты, а не собственные HTTP-вызовы из Activity.
 *
 * Instrumented-слоя в проекте нет, поэтому контракт читает production-исходник так же,
 * как остальные click-path тесты.
 */
class ChatSlashCommandsContractTest {

    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            val nested = File(dir, "app-mobile/$relative")
            if (nested.isFile) return nested.readText()
            dir = dir.parentFile
        }
        throw AssertionError("Не найден исходник: $relative")
    }

    private val turn by lazy { source("src/main/java/ru/mars/mobile/ChatTurnSection.kt") }
    private val section by lazy { source("src/main/java/ru/mars/mobile/ChatSlashCommandsSection.kt") }
    private val policy by lazy { source("src/main/java/ru/mars/mobile/SlashCommandPolicy.kt") }

    @Test
    fun `slash commands are intercepted before the message reaches the model`() {
        val guard = turn.indexOf("SlashCommandPolicy.isCommand(rawInput)")
        val dispatch = turn.indexOf("handleSlashCommand(rawInput)")
        val steer = turn.indexOf("steerMessage(); return }")
        val send = turn.indexOf("uiState.begin(")
        assertTrue("sendMessage обязан разбирать слэш-команду", guard >= 0)
        assertTrue("команда обслуживается диспетчером", dispatch > guard)
        assertTrue("команда обязана разбираться до ветки steer (/stop и /steer живут в ходе)", steer > dispatch)
        assertTrue("перехват обязан стоять до старта хода", send > dispatch)
    }

    @Test
    fun `command replies go to the status line, not to the transcript`() {
        assertTrue("ответ пишется в строку статуса", section.contains("explicitStatus = message"))
        assertTrue("строка статуса перерисовывается", section.contains("setStatus()"))
        assertTrue("ответ самоскрывается по таймауту", section.contains("SLASH_REPLY_TTL_MS"))
        assertFalse("ответ не добавляется пузырём в транскрипт", section.contains("uiState.begin("))
        assertTrue("композер очищается после команды", section.contains("input.text.clear()"))
        assertTrue("кнопки обновляются после команды", section.contains("updateControls()"))
    }

    @Test
    fun `unknown and unavailable commands are answered locally`() {
        assertTrue(section.contains("SlashCommandResolution.Unknown"))
        assertTrue(section.contains("SlashCommandPolicy.unknownMessage("))
        assertTrue(section.contains("SlashCommandResolution.Unavailable"))
        assertTrue(section.contains("SlashCommandPolicy.unavailableMessage("))
    }

    @Test
    fun `execution reuses existing transports instead of new endpoints`() {
        assertTrue("сессии — через SessionRepository", section.contains("SessionRepository()"))
        assertTrue("undo/clear/truncate — через общий TurnOpsRunner", section.contains("TurnOpsRunner.run("))
        assertTrue("остановка и steer — через ChatRepository", section.contains("ChatRepository()"))
        assertTrue("уровень мышления — через ReasoningRepository", section.contains("ReasoningRepository()"))
        assertFalse("никаких собственных URL в секции", section.contains("addPathSegments("))
        assertFalse("никаких собственных URL в секции", section.contains("toHttpUrl("))
    }

    @Test
    fun `policy stays pure JVM code`() {
        assertFalse("политика без Android", policy.contains("import android."))
        assertFalse("политика без сети", policy.contains("import okhttp3"))
        assertFalse(
            "политика без репозиториев: ни одного import из слоя сети",
            Regex("(?m)^import ru\\.mars\\.network").containsMatchIn(policy)
        )
    }

    @Test
    fun `goal keeps its own owner`() {
        assertTrue(
            "ветка /goal остаётся у GoalLoopPolicy",
            turn.contains("GoalLoopPolicy.isCommand(rawInput)") && turn.contains("handleGoalCommand(rawInput)")
        )
        assertTrue("диспетчер делегирует /goal владельцу", section.contains("\"goal\" -> handleGoalCommand(text)"))
    }
}
