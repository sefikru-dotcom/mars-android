package ru.mars.mobile

import android.view.View

/**
 * Хост рендерера карточек и проверки готовности view-дерева.
 *
 * `ChatMessageCardRenderer` строит пузыри через этот хост (клик по вложению, лайтбокс,
 * markdown, действия над сообщением). Раньше хост был анонимным объектом-полем внутри
 * [ChatActivity]; вынесен вместе с readiness-предикатами, чтобы Activity не держала
 * детали рендера. Поведение и порядок инициализации не менялись.
 */

internal fun ChatActivity.createCardRendererHost(): ChatMessageCardRenderer.Host = object : ChatMessageCardRenderer.Host {
        override fun messageAt(index: Int): ChatMessage? = uiState.messages.getOrNull(index)

        override fun modelLabel(): String? = modelSelection.buttonLabel()

        override val activityMode: ActivityDisplayMode get() = uiState.activityMode

        override fun isToolGroupExpanded(messageIndex: Int): Boolean? = uiState.isToolGroupExpanded(messageIndex)

        override fun toggleThoughts(messageIndex: Int) {
            uiState.toggleThoughts(messageIndex)
        }

        override fun toggleTool(messageIndex: Int, toolIndex: Int) {
            uiState.toggleTool(messageIndex, toolIndex)
        }

        override fun toggleToolGroup(messageIndex: Int, currentlyExpanded: Boolean) {
            uiState.toggleToolGroup(messageIndex, currentlyExpanded)
        }

        override fun isCodeBlockExpanded(messageIndex: Int, blockIndex: Int): Boolean =
            uiState.isCodeBlockExpanded(messageIndex, blockIndex)

        // Фолд code-блока меняет «эпоху хрома» (все снимки становятся «грязными») — как раньше.
        override fun toggleCodeBlock(messageIndex: Int, blockIndex: Int) {
            uiState.toggleCodeBlock(messageIndex, blockIndex)
            transcriptChromeEpoch++
            renderMessages()
        }

        override fun rerenderTranscript() {
            renderMessages()
        }

        override fun copyMessage(text: String) {
            this@createCardRendererHost.copyMessage(text)
        }

        override fun speakMessage(text: String) {
            this@createCardRendererHost.speakMessage(text)
        }

        override fun showMessageActions(message: ChatMessage, index: Int) {
            this@createCardRendererHost.showMessageActions(message, index)
        }

        override fun openImage(rawUrl: String) {
            lightbox.open(rawUrl)
        }

        override fun openUrl(url: String) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }

        override fun a11yHeader(header: View, description: String, state: String) {
            applyExpandableHeaderA11y(header, description, state)
        }
    }

/** View-дерево экрана готово к отрисовке транскрипта. */
internal fun isMessagesReady(): Boolean = ::messages.isInitialized
internal fun isInputReady(): Boolean = ::input.isInitialized
internal fun isDraftsReady(): Boolean = ::drafts.isInitialized
internal fun isAuthExitCleanupReady(): Boolean = ::authExitCleanup.isInitialized
internal fun isTodoWidgetPlateReady(): Boolean = ::todoWidgetPlate.isInitialized
internal fun isSearchPlateReady(): Boolean = ::searchPlate.isInitialized
internal fun isQueuePlateReady(): Boolean = ::queuePlate.isInitialized
internal fun isCompressionControllerReady(): Boolean = ::compressionController.isInitialized
