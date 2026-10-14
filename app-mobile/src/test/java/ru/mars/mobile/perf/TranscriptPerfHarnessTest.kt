package ru.mars.mobile.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.mobile.ChatUiState
import ru.mars.mobile.MessageTimePolicy
import ru.mars.mobile.TranscriptCapPolicy
import java.io.File

/**
 * Контрактные тесты perf-харнесса транскрипта (wire 100/500/1000, чистая JVM).
 *
 * Политика утверждений: защищаются ДЕТЕРМИНИРОВАННЫЕ счётчики работы — cap-размеры,
 * счётчик якоря, форма плана рендера, число markdown-блоков, детерминизм фикстур.
 * Wall-clock пороги НЕ утверждаются (flaky на общем CI): медианы nanoTime попадают
 * только в отчёт (stdout + build/reports/transcript-perf/transcript-perf-report.md).
 *
 * Ключевой продуктовый контракт: cap = [TranscriptCapPolicy.MAX_MESSAGES] (300),
 * поэтому wire=500 и wire=1000 дают ОДИНАКОВЫЙ UI-размер 301 (якорь + 300) —
 * харнесс измеряет поглощение избытка, а не «1000 инфлейченных карточек».
 * Стоимость самого инфлейта/лэйаута/скролла JVM не измеряет — это приборная
 * съёмка на устройстве (docs/transcript-perf-harness.md).
 */
class TranscriptPerfHarnessTest {

    private companion object {
        /** Отчёты по всем wire-размерам: один прогон на JVM (JUnit не параллелит класс). */
        val REPORTS: Map<Int, TranscriptPerfHarness.TranscriptPerfReport> by lazy {
            TranscriptPerfHarness.WIRE_SIZES.associateWith { TranscriptPerfHarness.run(it) }
        }

        fun expectedUiSize(wireSize: Int): Int =
            minOf(wireSize, TranscriptCapPolicy.MAX_MESSAGES) +
                if (wireSize > TranscriptCapPolicy.MAX_MESSAGES) 1 else 0
    }

    @Test
    fun wireSizes100_500_1000ReportCappedUiSize() {
        val reports = REPORTS
        assertEquals(listOf(100, 500, 1000), TranscriptPerfHarness.WIRE_SIZES)

        for ((wireSize, report) in reports) {
            val cap = report.cap
            assertEquals("wire=$wireSize ui-size", expectedUiSize(wireSize), cap.uiSize)
            assertTrue("wire=$wireSize ui-size обязан быть ≤ cap+1", cap.uiSize <= TranscriptCapPolicy.MAX_MESSAGES + 1)
            if (wireSize > TranscriptCapPolicy.MAX_MESSAGES) {
                assertTrue("wire=$wireSize якорь обязателен", cap.anchorPresent)
                assertEquals(
                    "wire=$wireSize trimmedCount",
                    wireSize - TranscriptCapPolicy.MAX_MESSAGES,
                    cap.trimmedCount,
                )
            } else {
                assertTrue("wire=$wireSize без якоря", !cap.anchorPresent)
                assertEquals(0, cap.trimmedCount)
            }
            // Пузырьковый cap: ни один UI-пузырь не длиннее лимита + якорный префикс.
            assertTrue(
                "wire=$wireSize largestUiBubble",
                cap.largestUiBubbleChars <= TranscriptCapPolicy.MAX_BUBBLE_CHARS +
                    TranscriptCapPolicy.BUBBLE_TRIM_ANCHOR.length + 1,
            )
            assertTrue("wire=$wireSize UI-символов не больше wire-символов", cap.uiBubbleChars <= report.fixture.bubbleChars)
            // Гарантированный oversized-пузырь фикстуры exercised и подрезан.
            assertTrue("wire=$wireSize oversized в фикстуре", report.fixture.oversizedBubbles >= 1)
        }

        // Cap отвязывает UI-размер от wire-размера: 500 и 1000 неразличимы в UI.
        assertEquals(reports.getValue(500).cap.uiSize, reports.getValue(1000).cap.uiSize)
        assertEquals(TranscriptCapPolicy.MAX_MESSAGES + 1, reports.getValue(1000).cap.uiSize)

        printReports(reports.values.toList())
    }

    @Test
    fun uiStateBindSessionAppliesSameCapAsHarness() {
        for (wireSize in TranscriptPerfHarness.WIRE_SIZES) {
            val fixture = TranscriptPerfFixtures.wireTranscript(wireSize)
            val state = ChatUiState()
            state.bindSession("perf-$wireSize", "Perf $wireSize", fixture.messages)
            assertEquals(
                "bindSession wire=$wireSize обязан дать тот же UI-размер, что харнесс",
                REPORTS.getValue(wireSize).cap.uiSize,
                state.messages.size,
            )
            assertEquals(
                TranscriptCapPolicy.capMessages(fixture.messages),
                state.messages.toList(),
            )
        }
    }

    @Test
    fun initialRenderPlanRebuildsExactlyCappedCardsNeverWireSize() {
        for ((wireSize, report) in REPORTS) {
            val plan = report.renderPlan
            assertEquals("wire=$wireSize первый рендер", "FullRebuild", plan.initialPlan)
            assertEquals("wire=$wireSize карточек полного рендера", expectedUiSize(wireSize), plan.initialFullRebuildCards)
            assertTrue(
                "wire=$wireSize план не может превысить cap+1",
                plan.initialFullRebuildCards <= TranscriptCapPolicy.MAX_MESSAGES + 1,
            )
            if (wireSize == 1000) {
                // Прямая защита от регресса «инфлейтим 1000 карточек».
                assertNotEquals("wire=1000 не рендерит 1000 карточек", 1000, plan.initialFullRebuildCards)
            }
        }
    }

    @Test
    fun streamFramePlanIsOneTextOnlyPatchAtEveryWireSize() {
        for ((wireSize, report) in REPORTS) {
            val plan = report.renderPlan
            assertEquals("wire=$wireSize кадр стрима", "Patch", plan.streamFramePlan)
            assertEquals("wire=$wireSize ровно одна TEXT_ONLY-заплатка", 1, plan.streamFrameTextOnlyPatches)
            assertEquals("wire=$wireSize без FULL_CARD на кадре стрима", 0, plan.streamFrameFullCardPatches)
            assertEquals("wire=$wireSize без достройки на токен-кадре", 0, plan.streamFrameAppendedCards)
            assertEquals(
                "wire=$wireSize appendFrom = конец списка",
                expectedUiSize(wireSize),
                plan.streamFrameAppendFrom,
            )
            assertEquals("wire=$wireSize noop", "NoChange", plan.noopPlan)
            assertEquals("wire=$wireSize новое сообщение", "Patch", plan.appendedPlan)
            assertEquals("wire=$wireSize достроена одна карточка", 1, plan.appendedCards)
            assertEquals("wire=$wireSize ребайнд сессии", "FullRebuild", plan.rebindPlan)
        }
    }

    @Test
    fun snapshotScanBoundedByCapNotWireSize() {
        for ((wireSize, report) in REPORTS) {
            assertEquals("wire=$wireSize снимков = UI-размер", expectedUiSize(wireSize), report.snapshot.cards)
            // День-разделители: независимый пересчёт той же политикой (защита зеркала
            // ChatActivity.currentSnapshots внутри харнесса).
            val fixture = TranscriptPerfFixtures.wireTranscript(wireSize)
            val capped = TranscriptCapPolicy.capMessages(fixture.messages)
            var expectedSeparators = 0
            var previousTimestamp = MessageTimePolicy.UNKNOWN_TIMESTAMP
            for (message in capped) {
                if (MessageTimePolicy.showDaySeparator(previousTimestamp, message.timestamp)) expectedSeparators++
                if (message.timestamp > MessageTimePolicy.UNKNOWN_TIMESTAMP) previousTimestamp = message.timestamp
            }
            assertEquals("wire=$wireSize день-разделители", expectedSeparators, report.snapshot.daySeparators)
            assertTrue("wire=$wireSize есть пересечения суток", report.snapshot.daySeparators >= 1)
        }
    }

    @Test
    fun markdownStageIsDeterministicAndCacheBounded() {
        for ((wireSize, report) in REPORTS) {
            val md = report.markdown
            assertTrue("wire=$wireSize блоки разобраны", md.totalBlocks > 0)
            // Непустые тексты = UI-карточки минус якорь (у якоря пустой текст).
            val anchorCount = if (report.cap.anchorPresent) 1 else 0
            assertEquals(
                "wire=$wireSize текстов = непустые UI-пузыри",
                expectedUiSize(wireSize) - anchorCount,
                md.distinctTexts,
            )
            assertTrue(
                "wire=$wireSize warm-кэш в пределах LRU",
                md.warmCacheSize <= 64,
            )
            assertTrue("wire=$wireSize cache-hit возвращает тот же список", md.warmCacheHitSameInstance)
            if (wireSize > TranscriptCapPolicy.MAX_MESSAGES) {
                // Oversized-хвост фикстуры пережил cap и НЕ кэшируется (> PARSE_CACHE_MAX_LENGTH).
                assertTrue("wire=$wireSize oversized не кэшируется", md.oversizedTextUncached)
            }
        }
        // Детерминизм разбора: два независимых прогона одной фикстуры дают равные блоки.
        val fixture = TranscriptPerfFixtures.wireTranscript(100)
        val capped = TranscriptCapPolicy.capMessages(fixture.messages)
        ru.mars.mobile.MarkdownFormatter.clearParseCache()
        val first = capped.map { ru.mars.mobile.MarkdownFormatter.parseBlocks(it.text) }
        ru.mars.mobile.MarkdownFormatter.clearParseCache()
        val second = capped.map { ru.mars.mobile.MarkdownFormatter.parseBlocks(it.text) }
        assertEquals("разбор markdown детерминирован", first, second)
        ru.mars.mobile.MarkdownFormatter.clearParseCache()
    }

    @Test
    fun fixturesAreDeterministicPerSeed() {
        val a = TranscriptPerfFixtures.wireTranscript(500)
        val b = TranscriptPerfFixtures.wireTranscript(500)
        assertEquals("тот же seed → та же история", a.messages, b.messages)
        val c = TranscriptPerfFixtures.wireTranscript(500, seed = TranscriptPerfFixtures.DEFAULT_SEED + 1)
        assertNotEquals("другой seed → другая история", a.messages, c.messages)
        assertEquals(a.bubbleChars, b.bubbleChars)
        assertEquals("последнее сообщение — стримящийся assistant", "assistant", a.messages.last().role)
        assertTrue("хвостовой пузырь непустой (TEXT_ONLY-путь)", a.messages.last().text.isNotEmpty())
    }

    @Test
    fun reportFormatContractMentionsCappedUiNotWireInflation() {
        val report = REPORTS.getValue(1000)
        val text = report.format()
        // Контракт формата отчёта: wire-размер и CAPPED UI-размер названы явно.
        assertTrue("отчёт называет wire", text.contains("wire=1000"))
        assertTrue("отчёт называет capped ui", text.contains("ui 301"))
        assertTrue("отчёт называет якорь", text.contains("anchor=true"))
        assertTrue("отчёт называет trimmed", text.contains("trimmed=700"))
        assertTrue("отчёт называет форму плана", text.contains("FullRebuild") && text.contains("Patch"))
        assertTrue("в отчёте нет заявления о 1000 карточек", !text.contains("1000 cards"))
    }

    /** Печать отчёта (evidence в stdout) + запись в build/reports/transcript-perf/. */
    private fun printReports(reports: List<TranscriptPerfHarness.TranscriptPerfReport>) {
        val body = buildString {
            append("# Transcript perf harness — JVM report\n\n")
            append("Прогон: wire 100/500/1000; cap=").append(TranscriptCapPolicy.MAX_MESSAGES)
                .append("; тайминги справочные (медианы nanoTime), утверждаются счётчики работы.\n\n")
            for (report in reports) {
                append(report.format()).append('\n')
            }
            append("JVM harness меряет только политики; инфлейт/лэйаут/скролл/FPS/память — ")
            append("приборная съёмка на устройстве (docs/transcript-perf-harness.md).\n")
        }
        println(body)
        runCatching {
            val out = File("build/reports/transcript-perf")
            out.mkdirs()
            File(out, "transcript-perf-report.md").writeText(body)
        }
    }
}
