package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ChatEvent

class ChatParamRowPresenterTest {
    private val presenter = ChatParamRowPresenter()

    private fun usage(percent: Int, used: Int = percent * 1000, max: Int = 100_000) =
        ChatEvent.ContextUsage(usedTokens = used, maxTokens = max, percent = percent)

    // --- Состав и порядок строки ----------------------------------------------------------------

    @Test fun rowHasExactlyModelThinkingContextInOrder() {
        val state = presenter.resolve()
        assertEquals(
            listOf(ParamRowControlId.MODEL, ParamRowControlId.THINKING, ParamRowControlId.CONTEXT),
            state.controls.map { it.id },
        )
    }

    @Test fun noYoloOrSubagentControlAnywhere() {
        val ids = ParamRowControlId.entries.map { it.name }
        assertFalse(ids.any { it.contains("YOLO") })
        assertFalse(ids.any { it.contains("SUBAGENT") })
        assertFalse(presenter.resolve().controls.any { it.label.contains("YOLO", ignoreCase = true) })
    }

    @Test fun allControlsAlwaysVisibleEvenOnFreshPresenter() {
        val state = presenter.resolve()
        assertTrue(state.controls.all { it.visible })
    }

    // --- Модель: сессионная правда отдельно от локального выбора --------------------------------

    @Test fun modelFallsBackToNeutralLabelWhileUnknown() {
        val model = presenter.resolve().model
        assertEquals(ChatParamRowPresenter.MODEL_FALLBACK_LABEL, model.label)
        assertTrue(model.enabled)
        assertEquals("Выбор модели", model.contentDescription)
    }

    @Test fun localChoiceShowsUntilSessionModelArrives() {
        presenter.setLocalModelChoice("qwen3-max")
        assertEquals("qwen3-max", presenter.effectiveModelLabel())
        assertEquals("qwen3-max", presenter.resolve().model.label)

        presenter.setSessionModel("gemini-2.5-pro")
        assertEquals("gemini-2.5-pro", presenter.effectiveModelLabel())
        assertEquals("gemini-2.5-pro", presenter.resolve().model.label)
        // Локальный выбор не затёрт серверной моделью сессии.
        assertEquals("qwen3-max", presenter.localModelLabel)
    }

    @Test fun sessionModelUpdateKeepsLocalChoiceAndViceVersa() {
        presenter.setSessionModel("session-model")
        presenter.setLocalModelChoice("picked-model")
        // Сессия важнее выбора — паритет с ModelSelection.wireChoice.
        assertEquals("session-model", presenter.resolve().model.label)

        presenter.setSessionModel(null)
        assertEquals("picked-model", presenter.effectiveModelLabel())
        assertEquals("picked-model", presenter.resolve().model.label)
    }

    @Test fun blankAndWhitespaceModelLabelsAreIgnored() {
        presenter.setSessionModel("   ")
        presenter.setLocalModelChoice("")
        assertNull(presenter.effectiveModelLabel())
        assertEquals(ChatParamRowPresenter.MODEL_FALLBACK_LABEL, presenter.resolve().model.label)
    }

    @Test fun longModelLabelIsShortenedFromTheFront() {
        val long = "very-long-provider/model-name-that-does-not-fit"
        presenter.setSessionModel(long)
        val label = presenter.resolve().model.label
        assertTrue(label.length <= ChatParamRowPresenter.LABEL_MAX)
        assertTrue(label.startsWith("…"))
        assertTrue(long.endsWith(label.removePrefix("…")))
    }

    @Test fun modelLabelCollapsesInnerWhitespace() {
        presenter.setSessionModel("  qwen3   max  ")
        assertEquals("qwen3 max", presenter.resolve().model.label)
    }

    // --- Уровень мышления ------------------------------------------------------------------------

    @Test fun thinkingFallsBackToAutoLabel() {
        val thinking = presenter.resolve().thinking
        assertEquals(ChatParamRowPresenter.THINKING_FALLBACK_LABEL, thinking.label)
        assertTrue(thinking.enabled)
        assertEquals("Уровень мышления: Авто", thinking.contentDescription)
    }

    @Test fun thinkingLabelPassesThroughSafe() {
        presenter.setThinkingLabel(" high ")
        assertEquals("high", presenter.resolve().thinking.label)
        assertEquals("Уровень мышления: high", presenter.resolve().thinking.contentDescription)
    }

    @Test fun thinkingControlStaysVisibleForAutoAndBlank() {
        assertTrue(ChatParamRowPresenter.thinkingControl(null).visible)
        assertTrue(ChatParamRowPresenter.thinkingControl("Авто").visible)
        assertEquals("Авто", ChatParamRowPresenter.thinkingControl("  ").label)
    }

    // --- Индикатор контекста: виден всегда, без скрытия по порогу --------------------------------

    @Test fun contextVisibleWithPlaceholderBeforeFirstMetering() {
        val context = presenter.resolve().context
        assertTrue(context.visible)
        assertEquals(ChatParamRowPresenter.CONTEXT_UNKNOWN_LABEL, context.label)
        assertEquals(ComposerChipTone.NEUTRAL, context.tone)
        assertEquals(ChatParamRowPresenter.CONTEXT_UNKNOWN_DESCRIPTION, context.contentDescription)
    }

    @Test fun contextPercentDrivesLabelAndTone() {
        presenter.setContextUsage(usage(42))
        var context = presenter.resolve().context
        assertEquals("42%", context.label)
        assertEquals(ComposerChipTone.NEUTRAL, context.tone)

        presenter.setContextUsage(usage(70))
        context = presenter.resolve().context
        assertEquals(ComposerChipTone.WARNING, context.tone)

        presenter.setContextUsage(usage(91))
        context = presenter.resolve().context
        assertEquals("91%", context.label)
        assertEquals(ComposerChipTone.CRITICAL, context.tone)
        assertEquals("Заполнение контекста: 91%", context.contentDescription)
    }

    @Test fun contextThresholdsMatchContextBudgetPolicy() {
        assertEquals(ComposerChipTone.NEUTRAL, ChatParamRowPresenter.contextControl(50, false).tone)
        assertEquals(ComposerChipTone.WARNING, ChatParamRowPresenter.contextControl(51, false).tone)
        assertEquals(ComposerChipTone.WARNING, ChatParamRowPresenter.contextControl(75, false).tone)
        assertEquals(ComposerChipTone.CRITICAL, ChatParamRowPresenter.contextControl(76, false).tone)
    }

    @Test fun insanePercentsAreClampedNotHidden() {
        val low = ChatParamRowPresenter.contextControl(-12, compressionRunning = false)
        assertTrue(low.visible)
        assertEquals("0%", low.label)
        val high = ChatParamRowPresenter.contextControl(148, compressionRunning = false)
        assertTrue(high.visible)
        assertEquals("100%", high.label)
    }

    @Test fun contextHiddenNeverEvenWithoutMaxTokens() {
        // usage с maxTokens <= 0 не даёт процент, но контрол остаётся видимым с плейсхолдером.
        presenter.setContextUsage(usage(50, max = 0))
        val context = presenter.resolve().context
        assertTrue(context.visible)
        assertEquals(ChatParamRowPresenter.CONTEXT_UNKNOWN_LABEL, context.label)
    }

    @Test fun compressionOverridesMeasurement() {
        presenter.setContextUsage(usage(30))
        presenter.setCompressionRunning(true)
        val context = presenter.resolve().context
        assertTrue(context.visible)
        assertEquals(ContextBudgetPolicy.COMPRESSION_LABEL, context.label)
        assertEquals(ComposerChipTone.WARNING, context.tone)
        assertEquals("Идёт сжатие контекста", context.contentDescription)
    }

    // --- Чистая резолюция из снимка ---------------------------------------------------------------

    @Test fun resolveWithSnapshotIsPureAndMatchesPresenter() {
        val snapshot = ParamRowSnapshot(
            sessionModelLabel = "sess",
            localModelLabel = "local",
            thinkingLabel = "low",
            contextPercent = 60,
        )
        val state = ChatParamRowPresenter.resolveWith(snapshot)
        assertEquals("sess", state.model.label)
        assertEquals("low", state.thinking.label)
        assertEquals("60%", state.context.label)
        assertEquals(state, ChatParamRowPresenter.resolveWith(snapshot))
    }

    @Test fun snapshotRoundTripsPresenterState() {
        presenter.setLocalModelChoice("m")
        presenter.setThinkingLabel("high")
        presenter.setContextUsage(usage(17))
        presenter.setCompressionRunning(true)
        val snapshot = presenter.snapshot()
        assertEquals("m", snapshot.localModelLabel)
        assertEquals("high", snapshot.thinkingLabel)
        assertEquals(17, snapshot.contextPercent)
        assertTrue(snapshot.compressionRunning)
        assertEquals(presenter.resolve(), ChatParamRowPresenter.resolveWith(snapshot))
    }

    @Test fun safeLabelGuards() {
        assertNull(ChatParamRowPresenter.safeLabel(null))
        assertNull(ChatParamRowPresenter.safeLabel("\t\n "))
        assertEquals("a b", ChatParamRowPresenter.safeLabel("a\n\n  b"))
        assertEquals(
            ChatParamRowPresenter.LABEL_MAX,
            ChatParamRowPresenter.safeLabel("x".repeat(80))?.length,
        )
    }
}
