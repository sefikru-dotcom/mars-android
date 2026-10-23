package ru.mars.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт проводки R5 в UI-зоне: «скрепка» в новом чате обязана сначала гарантировать
 * сессию, и только потом грузить файл.
 *
 * Instrumented-слоя в проекте нет, поэтому проводка закреплена как source-контракт (тот же
 * стиль, что [ChatVoiceUiWiringContractTest]): тест читает РЕАЛЬНЫЙ production-исходник.
 *
 * Что защищается:
 * 1. upload идёт ПОСЛЕ мятки и получает id из её результата, а не `currentSessionId`;
 * 2. отказ мятки не превращается в 404 от upload: статус с причиной либо переавторизация;
 * 3. сессию создаёт ОДИН шов ([FirstSessionGate]) для обоих жестов — иначе гонка мятки
 *    возвращается, и вложение уезжает в осиротевшую сессию.
 */
class AttachmentSessionWiringContractTest {

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

    private val composer by lazy { source("src/main/java/ru/mars/mobile/ChatComposerSection.kt") }
    private val turn by lazy { source("src/main/java/ru/mars/mobile/ChatTurnSection.kt") }
    private val chat by lazy { source("src/main/java/ru/mars/mobile/ChatActivity.kt") }
    private val gate by lazy { source("src/main/java/ru/mars/mobile/FirstSessionGate.kt") }

    private val ensureAt = lazy { composer.indexOf("firstSessionGate.ensure(") }
    private val uploadAt = lazy { composer.indexOf("ChatRepository().uploadAttachment(") }

    @Test
    fun `attachment mints the session before uploading`() {
        assertTrue("uploadAttachment обязан гарантировать сессию", ensureAt.value >= 0)
        assertTrue("мятка должна идти ДО загрузки файла", uploadAt.value > ensureAt.value)
    }

    @Test
    fun `upload uses the ensured session id instead of the raw activity field`() {
        val callLine = composer.substring(uploadAt.value).substringBefore("\n")
        assertTrue("upload обязан слать id из результата мятки: $callLine", callLine.contains("sessionId)"))
        assertFalse("сырой currentSessionId снова даст 404 в новом чате: $callLine", callLine.contains("currentSessionId"))
    }

    @Test
    fun `failed mint is surfaced or routed to reauthentication instead of a 404`() {
        val failedAt = composer.indexOf("is EnsureSessionResult.Failed", ensureAt.value)
        assertTrue("отказ мятки обязан обрабатываться явно", failedAt > ensureAt.value)
        val branch = composer.substring(failedAt, uploadAt.value)
        assertTrue("причина показывается пользователю", branch.contains("Сессия не создана:"))
        assertTrue("мёртвый credential уводит на переавторизацию", branch.contains("AuthRejectionPolicy.isCredentialRejected"))
        assertTrue(branch.contains("requestReauthentication()"))
        assertTrue("после отказа файл не грузится", branch.contains("return@launch"))
    }

    @Test
    fun `both gestures mint through one gate`() {
        assertTrue(chat.contains("FirstSessionGate(firstSessionFlow)"))
        assertTrue("первый ход идёт через тот же лок", turn.contains("firstSessionGate.ensure("))
        assertFalse("прямой вызов в обход лока вернёт гонку мятки", turn.contains("firstSessionFlow.ensureSession("))
        assertFalse("привязка id живёт в гейте, а не рядом с ним", turn.contains("currentSessionId = ensured.sessionId"))
        assertFalse("у прикрепления нет второго способа создать сессию", composer.contains("session/new"))
    }

    @Test
    fun `gate binds the id inside its critical section`() {
        val lockAt = gate.indexOf("mutex.withLock")
        val bindAt = gate.indexOf("bind(result.sessionId)")
        assertTrue(lockAt >= 0)
        assertTrue("привязка обязана быть под локом, иначе окно гонки остаётся", bindAt > lockAt)
        assertTrue("после привязки лок ещё держится", bindAt < gate.indexOf("is EnsureSessionResult.Failed", lockAt))
    }
}
