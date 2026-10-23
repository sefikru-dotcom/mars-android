package ru.mars.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт проводки data-долгов волны 2010 в РЕАЛЬНЫХ production-исходниках.
 *
 * Instrumented-слоя в проекте нет, поэтому «фича есть в UI» закрепляется чтением
 * исходника Activity (тот же паттерн, что [KanbanShellContractTest]): удаление
 * вызова, подмена серверной ручки на заглушку или возврат polling-пути ломают тест.
 *
 * Покрываются: публичная share-ссылка (O4), импорт диалога (O5), живой поток
 * канбана (O2), карточка цели и `/goal` (O14), карточка офлайн-очереди (O8).
 */
class DataParityWiringContractTest {

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

    private val sessions by lazy { source("src/main/java/ru/mars/mobile/ChatSessionsActivity.kt") }
    private val chat by lazy {
        source("src/main/java/ru/mars/mobile/ChatActivity.kt") + "\n" +
            // Секции-расширения Activity (разбиение файла W2): тот же production-код экрана,
            // поэтому проводка проверяется по Activity + секциям, а не только по одному файлу.
            listOf(
                "ChatInitSection.kt",
                "ChatShellSection.kt",
                "ChatComposerSection.kt",
                "ChatCardHostSection.kt",
                "ChatStatusSection.kt",
                "ChatTurnSection.kt",
                "ChatSessionSection.kt",
                "ChatSessionLifecycleSection.kt",
                "ChatTranscriptSection.kt",
                "ChatSearchSection.kt",
                "ChatDraftSection.kt",
                "ChatModelSection.kt",
                "ChatUsageSection.kt",
                "ChatTodoSection.kt",
                "ChatMessageActionsSection.kt",
                "ChatOfflineQueueSection.kt",
                "ChatVoiceInputSection.kt",
                "ChatVoiceSpeechSection.kt",
            ).joinToString("\n") { source("src/main/java/ru/mars/mobile/$it") }
    }
    private val kanban by lazy { source("src/main/java/ru/mars/mobile/KanbanActivity.kt") }
    private val presenter by lazy { source("src/main/java/ru/mars/mobile/KanbanPresenter.kt") }

    // --- O4: публичная share-ссылка -------------------------------------------------------

    @Test
    fun `share goes through the allowlisted server routes, not a local stub`() {
        assertTrue(
            "Создание ссылки — POST /api/share/create через репозиторий",
            sessions.contains("SessionRepository().createShare(ChatActivity.BASE_URL")
        )
        assertTrue(
            "Отзыв ссылки — POST /api/share/revoke через репозиторий",
            sessions.contains("SessionRepository().revokeShare(ChatActivity.BASE_URL")
        )
        // Старый «блокер 401» не должен вернуться ни комментарием, ни текстом отказа.
        assertFalse(
            "Устаревшее объяснение «share недоступен device-токену» не должно остаться в UI",
            sessions.contains("SHARE_UNAVAILABLE_NOTE") || sessions.contains("401")
        )
    }

    @Test
    fun `share link is copied as an absolute url`() {
        assertTrue(
            "В буфер обмена уходит абсолютный адрес, а не относительный /share/<token>",
            sessions.contains("SessionSharePolicy.clipboardText(")
        )
        assertTrue("Отзыв доступен из диалога ссылки", sessions.contains("revokeShare(session)"))
    }

    // --- O5: импорт диалога ---------------------------------------------------------------

    @Test
    fun `import opens a system file picker and posts the document`() {
        assertTrue(
            "Файл выбирается системным пикером (OpenDocument даёт стабильный content:// uri)",
            sessions.contains("ActivityResultContracts.OpenDocument()")
        )
        assertTrue("Выбранный файл уходит в разбор/импорт", sessions.contains("importSessionFrom(uri)"))
        assertTrue(
            "Импорт — POST /api/session/import через репозиторий",
            sessions.contains("SessionRepository().importSession(ChatActivity.BASE_URL")
        )
        // Импорт не должен подменяться локальным «разбором в новый чат».
        assertFalse("Импорт не собирает сессию локально", sessions.contains("createSession("))
    }

    // --- O2: живой поток канбана ----------------------------------------------------------

    @Test
    fun `kanban live stream is reused from the chat transport and reconnects`() {
        assertTrue(
            "Поток поднимается из презентера (та же SSE-обвязка, что у чата)",
            kanban.contains("liveJob = presenter.bindLiveStream")
        )
        assertTrue(
            "Презентер слушает repository.streamEvents, а не собственный парсер",
            presenter.contains("events(since, selectedBoard)") || presenter.contains("streamEvents(")
        )
        assertTrue(
            "Обрыв потока не оставляет экран на polling навсегда — есть переподключение",
            presenter.contains("KanbanStreamReconnectPolicy.delayMs(")
        )
        assertTrue(
            "Пока поток жив, таймер polling не дублирует запросы",
            kanban.contains("if (!presenter.state.live) presenter.refresh()")
        )
    }

    // --- O14: карточка цели и /goal -------------------------------------------------------

    @Test
    fun `goal frames drive the card and the command is handled locally`() {
        assertTrue("Кадр goal рисует карточку", chat.contains("GoalLoopPolicy.card(event)"))
        assertTrue("Кадр goal_continue рисует карточку", chat.contains("GoalLoopPolicy.continuationCard(event)"))
        assertTrue(
            "Карточка перерисовывается",
            Regex("(?:private|internal) fun (?:ChatActivity\\.)?updateGoalCard\\(\\)").containsMatchIn(chat)
        )
        assertTrue(
            "Промпт продолжения уходит в обычную очередь, а не вторым путём отправки",
            chat.contains("offlineQueue.enqueue(text, currentSessionId)")
        )
        assertTrue("`/goal` перехватывается до отправки в модель", chat.contains("GoalLoopPolicy.isCommand(text)"))
        assertTrue("Команда обслуживается локально", chat.contains("handleGoalCommand(text)"))
    }

    // --- O8: карточка офлайн-очереди ------------------------------------------------------

    @Test
    fun `offline queue presenter drives the strip instead of ad-hoc state`() {
        assertTrue("Панель очереди считает презентер", chat.contains("offlineQueuePresenter.panelFor("))
        assertTrue("Начало отправки отмечается", chat.contains("offlineQueuePresenter.onSendStarted("))
        assertTrue("Провал хода переезжает в карточку", chat.contains("offlineQueuePresenter.onTurnFailed("))
        assertTrue("Успех снимает строку", chat.contains("offlineQueuePresenter.onSendSucceeded("))
        assertTrue("Ручной повтор", chat.contains("offlineQueuePresenter.requestRetry("))
        assertTrue("Удаление с подтверждением", chat.contains("offlineQueuePresenter.requestDiscard("))
        assertTrue("Применение подтверждённого удаления", chat.contains("offlineQueuePresenter.confirmDiscard()"))
        assertTrue(
            "Удалённое сообщение не оставляет PENDING-эха в транскрипте",
            chat.contains("uiState.dropQueuedEcho(")
        )
        assertTrue(
            "Карточка перерисовывается по тем же триггерам, что и баннер сети",
            chat.contains("updateOfflineQueuePanel()")
        )
    }
}
