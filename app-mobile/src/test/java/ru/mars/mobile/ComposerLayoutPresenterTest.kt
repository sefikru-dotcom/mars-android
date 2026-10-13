package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerLayoutPresenterTest {
    private val presenter = ComposerLayoutPresenter()

    private fun chipIds(state: ComposerViewState) = state.chips.map { it.id }

    // --- Плашка цитаты/ответа -------------------------------------------------------------------

    @Test fun freshPresenterHidesQuotePlate() {
        assertFalse(presenter.quotePlate.visible)
        assertNull(presenter.activeQuote)
        assertEquals(ComposerQuotePlate.HIDDEN, presenter.quotePlate)
    }

    @Test fun setQuoteShowsPlateWithAuthorAndExcerpt() {
        val plate = presenter.setQuote("Посмотри этот код", author = "Марс")
        assertTrue(plate.visible)
        assertEquals(ComposerQuoteMode.QUOTE, plate.mode)
        assertEquals("Цитата · Марс", plate.title)
        assertEquals("Посмотри этот код", plate.excerpt)
        assertEquals("Отменить цитату", plate.cancelDescription)
        assertEquals(ComposerQuoteMode.QUOTE, presenter.activeQuote?.mode)
    }

    @Test fun setReplyUsesReplyModeAndCancelWording() {
        val plate = presenter.setReply("Сделаю", author = "Марс")
        assertTrue(plate.visible)
        assertEquals(ComposerQuoteMode.REPLY, plate.mode)
        assertEquals("Ответ · Марс", plate.title)
        assertEquals("Отменить ответ", plate.cancelDescription)
    }

    @Test fun blankAuthorFallsBackToModeTitle() {
        assertEquals("Цитата", presenter.setQuote("Текст", author = "   ").title)
    }

    @Test fun blankMessageIsIgnoredAndPlateStaysHidden() {
        val plate = presenter.setQuote("   \n ")
        assertFalse(plate.visible)
        assertNull(presenter.activeQuote)
    }

    @Test fun blankMessageDoesNotClobberActiveQuote() {
        presenter.setQuote("Первая цитата")
        val plate = presenter.setQuote("")
        assertTrue(plate.visible)
        assertEquals("Первая цитата", plate.excerpt)
    }

    @Test fun secondQuoteReplacesFirst() {
        presenter.setQuote("Первая")
        val plate = presenter.setQuote("Вторая")
        assertEquals("Вторая", plate.excerpt)
        assertEquals("Вторая", presenter.activeQuote?.messageText)
    }

    @Test fun clearQuoteHidesPlateAndResetsState() {
        presenter.setQuote("Цитата")
        val plate = presenter.clearQuote()
        assertEquals(ComposerQuotePlate.HIDDEN, plate)
        assertFalse(plate.visible)
        assertNull(presenter.activeQuote)
    }

    @Test fun clearQuoteOnEmptyStateIsSafe() {
        assertEquals(ComposerQuotePlate.HIDDEN, presenter.clearQuote())
    }

    @Test fun multilineExcerptCollapsesIntoSingleLine() {
        val plate = presenter.setQuote("Строка раз\n\n  Строка два  \nСтрока три")
        assertEquals("Строка раз Строка два Строка три", plate.excerpt)
    }

    @Test fun longExcerptIsTruncatedWithEllipsis() {
        val plate = presenter.setQuote("а".repeat(300))
        assertTrue(plate.excerpt.endsWith("…"))
        assertTrue(plate.excerpt.length <= ComposerLayoutPresenter.PLATE_EXCERPT_CHARS + 1)
    }

    @Test fun plateExcerptIsPureForRawInputs() {
        assertEquals("", ComposerLayoutPresenter.plateExcerpt("  \n "))
        assertEquals("а · б", ComposerLayoutPresenter.plateExcerpt("а\n· б"))
    }

    // --- Исходящий текст с цитатой ---------------------------------------------------------------

    @Test fun outgoingTextWithoutQuoteIsDraftVerbatim() {
        assertEquals("Привет", presenter.outgoingText("Привет"))
    }

    @Test fun outgoingTextPrependsMarkdownQuoteBlock() {
        presenter.setQuote("Привет")
        assertEquals("> Привет\n\nОтвет", presenter.outgoingText("Ответ"))
    }

    @Test fun outgoingTextForReplyUsesSameBlockFormat() {
        presenter.setReply("Привет")
        assertEquals("> Привет\n\n", presenter.outgoingText(""))
    }

    @Test fun outgoingTextAfterClearIsPlainDraft() {
        presenter.setQuote("Привет")
        presenter.clearQuote()
        assertEquals("Ответ", presenter.outgoingText("Ответ"))
    }

    // --- Кнопка отправки/остановки ----------------------------------------------------------------

    @Test fun idleWithBlankDraftDisablesSend() {
        val send = ComposerLayoutPresenter.sendButton(ChatStatus.IDLE, draft = "", hasAttachments = false)
        assertEquals(ComposerSendMode.SEND, send.mode)
        assertFalse(send.enabled)
        assertFalse(send.showStopIcon)
        assertEquals("Отправить", send.contentDescription)
    }

    @Test fun whitespaceOnlyDraftDoesNotEnableSend() {
        assertFalse(ComposerLayoutPresenter.sendButton(ChatStatus.IDLE, "  \n ", false).enabled)
    }

    @Test fun idleWithTextEnablesSend() {
        val send = ComposerLayoutPresenter.sendButton(ChatStatus.IDLE, draft = "Поехали", hasAttachments = false)
        assertTrue(send.enabled)
        assertEquals(ComposerSendMode.SEND, send.mode)
    }

    @Test fun attachmentsOnlyEnableSend() {
        assertTrue(ComposerLayoutPresenter.sendButton(ChatStatus.IDLE, draft = "", hasAttachments = true).enabled)
    }

    @Test fun typingSwitchesButtonToStop() {
        val send = ComposerLayoutPresenter.sendButton(ChatStatus.TYPING, draft = "", hasAttachments = false)
        assertEquals(ComposerSendMode.STOP, send.mode)
        assertTrue(send.enabled)
        assertTrue(send.showStopIcon)
        assertEquals("Остановить", send.contentDescription)
    }

    @Test fun receivedAndErrorFallBackToSendMode() {
        for (status in listOf(ChatStatus.RECEIVED, ChatStatus.ERROR)) {
            val send = ComposerLayoutPresenter.sendButton(status, draft = "текст", hasAttachments = false)
            assertEquals(ComposerSendMode.SEND, send.mode)
            assertFalse(send.showStopIcon)
        }
    }

    // --- Чипы сессии ------------------------------------------------------------------------------

    @Test fun modelChipHiddenWithoutLabel() {
        val chip = ComposerLayoutPresenter.modelChip(null)
        assertFalse(chip.visible)
        assertEquals(ComposerChipId.MODEL, chip.id)
        assertFalse(ComposerLayoutPresenter.modelChip("  ").visible)
    }

    @Test fun modelChipShowsLabelWithChevron() {
        val chip = ComposerLayoutPresenter.modelChip("Gemini 3.8 Flash")
        assertTrue(chip.visible)
        assertEquals("Gemini 3.8 Flash", chip.label)
        assertTrue(chip.showChevron)
        assertEquals("Модель: Gemini 3.8 Flash", chip.contentDescription)
        assertEquals(ComposerChipTone.NEUTRAL, chip.tone)
    }

    @Test fun reasoningChipHiddenForNullBlankAndAuto() {
        assertFalse(ComposerLayoutPresenter.reasoningChip(null).visible)
        assertFalse(ComposerLayoutPresenter.reasoningChip("  ").visible)
        assertFalse(ComposerLayoutPresenter.reasoningChip("Авто").visible)
        assertFalse(ComposerLayoutPresenter.reasoningChip("авто").visible)
    }

    @Test fun reasoningChipVisibleForActiveLevel() {
        val chip = ComposerLayoutPresenter.reasoningChip("High")
        assertTrue(chip.visible)
        assertEquals("High", chip.label)
        assertTrue(chip.showChevron)
        assertEquals("Рассуждения: High", chip.contentDescription)
    }

    @Test fun workspaceChipShortensPathAndKeepsFullDescription() {
        val chip = ComposerLayoutPresenter.workspaceChip("/root/workspace/mars-android")
        assertTrue(chip.visible)
        assertEquals("mars-android", chip.label)
        assertTrue(chip.showChevron)
        assertEquals("Воркспейс: /root/workspace/mars-android", chip.contentDescription)
    }

    @Test fun workspaceChipHiddenForBlankPath() {
        assertFalse(ComposerLayoutPresenter.workspaceChip(null).visible)
        assertFalse(ComposerLayoutPresenter.workspaceChip("   ").visible)
    }

    @Test fun longWorkspaceTailIsTrimmedWithEllipsis() {
        val long = "очень-длинный-каталог-проекта-марс"
        val label = ComposerLayoutPresenter.shortenWorkspacePath("/root/$long")
        assertTrue(label.startsWith("…"))
        assertEquals(ComposerLayoutPresenter.WORKSPACE_LABEL_MAX, label.length)
        assertTrue(long.endsWith(label.removePrefix("…")))
    }

    @Test fun rootPathFallsBackToSlashLabel() {
        assertEquals("/", ComposerLayoutPresenter.shortenWorkspacePath("/"))
    }

    @Test fun autonomyChipAlwaysVisibleWithRubyToneWhenOn() {
        val on = ComposerLayoutPresenter.autonomyChip(enabled = true)
        assertTrue(on.visible)
        assertEquals("Автономность", on.label)
        assertEquals(ComposerChipTone.RUBY, on.tone)
        assertFalse(on.showChevron)
        assertTrue(on.contentDescription.contains("включена"))

        val off = ComposerLayoutPresenter.autonomyChip(enabled = false)
        assertTrue(off.visible)
        assertEquals(ComposerChipTone.NEUTRAL, off.tone)
        assertTrue(off.contentDescription.contains("выключена"))
    }

    @Test fun contextChipHiddenWithoutMeasurement() {
        assertFalse(ComposerLayoutPresenter.contextChip(null).visible)
    }

    @Test fun contextChipHiddenForOutOfRangePercent() {
        assertFalse(ComposerLayoutPresenter.contextChip(-1).visible)
        assertFalse(ComposerLayoutPresenter.contextChip(101).visible)
    }

    @Test fun contextChipShowsPercentAndBoundaryTones() {
        val normal = ComposerLayoutPresenter.contextChip(50)
        assertTrue(normal.visible)
        assertEquals("50%", normal.label)
        assertEquals(ComposerChipTone.NEUTRAL, normal.tone)
        assertEquals("Заполнение контекста: 50%", normal.contentDescription)

        assertEquals(ComposerChipTone.WARNING, ComposerLayoutPresenter.contextChip(51).tone)
        assertEquals(ComposerChipTone.WARNING, ComposerLayoutPresenter.contextChip(75).tone)
        assertEquals(ComposerChipTone.CRITICAL, ComposerLayoutPresenter.contextChip(76).tone)
    }

    @Test fun chipsRowKeepsFixedOrderAndFiltersHidden() {
        val state = ComposerLayoutPresenter.resolveWith(
            quote = null,
            snapshot = ComposerSnapshot(
                modelLabel = "Gemini 3.8 Flash",
                reasoningLabel = "High",
                workspacePath = "/root/workspace",
                autonomyEnabled = true,
                contextPercent = 12,
            ),
        )
        assertEquals(
            listOf(
                ComposerChipId.MODEL,
                ComposerChipId.REASONING,
                ComposerChipId.WORKSPACE,
                ComposerChipId.AUTONOMY,
                ComposerChipId.CONTEXT,
            ),
            chipIds(state),
        )
    }

    @Test fun emptySnapshotLeavesOnlyAutonomyChip() {
        val state = ComposerLayoutPresenter.resolveWith(null, ComposerSnapshot())
        assertEquals(listOf(ComposerChipId.AUTONOMY), chipIds(state))
    }

    // --- Полная резолюция состояния ----------------------------------------------------------------

    @Test fun defaultStateCarriesHintAndLineLimit() {
        val state = presenter.resolve()
        assertEquals("Сообщение Марсу…", state.hint)
        assertEquals(5, state.maxInputLines)
        assertFalse(state.quotePlate.visible)
        assertFalse(state.send.enabled)
    }

    @Test fun resolveIncludesActiveQuotePlate() {
        presenter.setQuote("Важная строка")
        val state = presenter.resolve(ComposerSnapshot(draft = "Ответ"))
        assertTrue(state.quotePlate.visible)
        assertEquals("Важная строка", state.quotePlate.excerpt)
        assertTrue(state.send.enabled)
    }

    @Test fun attachAndMicDisabledWhileStreaming() {
        val state = presenter.resolve(ComposerSnapshot(status = ChatStatus.TYPING))
        assertFalse(state.attach.enabled)
        assertEquals("Прикрепить файл", state.attach.contentDescription)
        assertFalse(state.mic.enabled)
        assertEquals("Голосовой ввод", state.mic.contentDescription)
        assertEquals(ComposerSendMode.STOP, state.send.mode)
    }

    @Test fun micWhileRecordingBecomesStopButton() {
        val state = presenter.resolve(ComposerSnapshot(recordingVoice = true))
        assertTrue(state.mic.enabled)
        assertTrue(state.mic.active)
        assertEquals("Остановить запись", state.mic.contentDescription)
    }

    @Test fun micEnabledWhenIdle() {
        val state = presenter.resolve(ComposerSnapshot(status = ChatStatus.IDLE))
        assertTrue(state.mic.enabled)
        assertFalse(state.mic.active)
        assertTrue(state.attach.enabled)
    }
}
