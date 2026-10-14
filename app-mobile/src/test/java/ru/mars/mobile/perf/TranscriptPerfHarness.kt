package ru.mars.mobile.perf

import ru.mars.mobile.CardSnapshot
import ru.mars.mobile.CardSnapshotPolicy
import ru.mars.mobile.ChatMessage
import ru.mars.mobile.IncrementalTranscriptPolicy
import ru.mars.mobile.MarkdownFormatter
import ru.mars.mobile.MessageTimePolicy
import ru.mars.mobile.TranscriptCapPolicy
import ru.mars.mobile.TranscriptPlan
import java.util.Locale

/**
 * JVM-харнесс производительности транскрипта: измеряет СТОИМОСТЬ ПОЛИТИК рендера
 * на историях «с провода» размером 100/500/1000 сообщений. Никаких Android-view:
 * карточки здесь — элементы ПЛАНА рендера, а не инфлейты. Что реально меряется и
 * что требует приборной съёмки на устройстве — docs/transcript-perf-harness.md.
 *
 * Контракт харнесса (переиспользуемый):
 * - вход: wire-размер + seed ([TranscriptPerfFixtures]) — детерминированная история;
 * - выход: [TranscriptPerfReport] — структурированные метрики по четырём стадиям
 *   (cap → снимки карточек → план рендера → разбор markdown), каждая стадия
 *   вызывает ИСКЛЮЧИТЕЛЬНО прод-код (TranscriptCapPolicy, CardSnapshotPolicy,
 *   IncrementalTranscriptPolicy, MarkdownFormatter);
 * - тайминги (nanoTime) — СПРАВОЧНЫЕ медианы: тесты не утверждают wall-clock
 *   пороги (flaky на общем CI-раннере); утверждаются детерминированные счётчики
 *   работы (размеры, счётчики якорей, форма плана, число блоков);
 * - wire=500/1000 измеряют ПОГЛОЩЕНИЕ избытка cap'ом: UI-размер всегда ≤ 301
 *   (якорь + 300), харнесс никогда не заявляет «1000 инфлейченных карточек».
 */
object TranscriptPerfHarness {

    /** Обязательные wire-размеры бенчмарка: 100 (под cap), 500/1000 (поглощение cap'ом). */
    val WIRE_SIZES = listOf(100, 500, 1000)

    /** Метрики стадии cap-подрезки ([TranscriptCapPolicy.capMessages]). */
    data class CapStage(
        val uiSize: Int,
        val uiBubbleChars: Long,
        val anchorPresent: Boolean,
        val trimmedCount: Int,
        val largestUiBubbleChars: Int,
        val capApplyMedianNs: Long,
        val timingRuns: Int,
    )

    /** Метрики стадии снимков карточек (зеркало ChatActivity.currentSnapshots). */
    data class SnapshotStage(
        val cards: Int,
        val daySeparators: Int,
        val snapshotScanMedianNs: Long,
    )

    /** Форма планов рендера ([IncrementalTranscriptPolicy.plan]) на ключевых переходах. */
    data class RenderPlanStage(
        val initialPlan: String,
        val initialFullRebuildCards: Int,
        val streamFramePlan: String,
        val streamFrameTextOnlyPatches: Int,
        val streamFrameFullCardPatches: Int,
        val streamFrameAppendFrom: Int,
        val streamFrameAppendedCards: Int,
        val noopPlan: String,
        val appendedPlan: String,
        val appendedCards: Int,
        val rebindPlan: String,
        val snapshotRescanMedianNs: Long,
        val planMedianNs: Long,
    )

    /** Метрики разбора markdown ([MarkdownFormatter.parseBlocks] + LRU-кэш). */
    data class MarkdownStage(
        val distinctTexts: Int,
        val cacheableTexts: Int,
        val totalBlocks: Int,
        val warmCacheSize: Int,
        val warmCacheHitSameInstance: Boolean,
        val oversizedTextUncached: Boolean,
        val coldParseMedianNs: Long,
        val warmParseMedianNs: Long,
    )

    data class FixtureStage(
        val wireSize: Int,
        val seed: Long,
        val bubbleChars: Long,
        val largestBubbleChars: Int,
        val oversizedBubbles: Int,
        val messagesWithThoughts: Int,
        val messagesWithTools: Int,
        val messagesWithAttachments: Int,
    )

    /** Итоговый отчёт одного wire-размера. */
    data class TranscriptPerfReport(
        val fixture: FixtureStage,
        val cap: CapStage,
        val snapshot: SnapshotStage,
        val renderPlan: RenderPlanStage,
        val markdown: MarkdownStage,
    ) {
        /** Человекочитаемая форма — печатается тестом и пишется в build/reports/transcript-perf/. */
        fun format(): String {
            val f = fixture; val c = cap; val s = snapshot; val p = renderPlan; val m = markdown
            return buildString {
                append("## wire=").append(f.wireSize).append(" seed=").append(f.seed).append('\n')
                append("fixture : bubbles=").append(f.wireSize)
                    .append(", chars=").append(f.bubbleChars)
                    .append(", largestBubble=").append(f.largestBubbleChars)
                    .append(", oversizedBubbles=").append(f.oversizedBubbles)
                    .append(", thoughts=").append(f.messagesWithThoughts)
                    .append(", tools=").append(f.messagesWithTools)
                    .append(", attachments=").append(f.messagesWithAttachments).append('\n')
                append("cap     : wire ").append(f.wireSize).append(" -> ui ").append(c.uiSize)
                    .append(" (anchor=").append(c.anchorPresent)
                    .append(", trimmed=").append(c.trimmedCount).append(')')
                    .append(", chars ").append(f.bubbleChars).append(" -> ").append(c.uiBubbleChars)
                    .append(", largestUiBubble=").append(c.largestUiBubbleChars)
                    .append(", capMessages median ").append(ms(c.capApplyMedianNs))
                    .append(" ms (").append(c.timingRuns).append(" runs)\n")
                append("snapshot: cards=").append(s.cards)
                    .append(", daySeparators=").append(s.daySeparators)
                    .append(", rescan median ").append(ms(s.snapshotScanMedianNs)).append(" ms\n")
                append("plan    : initial=").append(p.initialPlan)
                    .append('(').append(p.initialFullRebuildCards).append(" cards)")
                    .append("; streamFrame=").append(p.streamFramePlan)
                    .append("(textOnly=").append(p.streamFrameTextOnlyPatches)
                    .append(", fullCard=").append(p.streamFrameFullCardPatches)
                    .append(", appended=").append(p.streamFrameAppendedCards).append(')')
                    .append("; noop=").append(p.noopPlan)
                    .append("; append=").append(p.appendedPlan).append("(+").append(p.appendedCards).append(')')
                    .append("; rebind=").append(p.rebindPlan).append('\n')
                append("plan    : snapshotRescan median ").append(ms(p.snapshotRescanMedianNs))
                    .append(" ms, plan median ").append(ms(p.planMedianNs)).append(" ms\n")
                append("markdown: texts=").append(m.distinctTexts)
                    .append(", cacheable=").append(m.cacheableTexts)
                    .append(", blocks=").append(m.totalBlocks)
                    .append(", warmCache=").append(m.warmCacheSize)
                    .append(", warmHitSameInstance=").append(m.warmCacheHitSameInstance)
                    .append(", oversizedUncached=").append(m.oversizedTextUncached)
                    .append(", cold median ").append(ms(m.coldParseMedianNs))
                    .append(" ms, warm median ").append(ms(m.warmParseMedianNs)).append(" ms\n")
            }
        }

        private fun ms(ns: Long): String = String.format(Locale.US, "%.3f", ns / 1_000_000.0)
    }

    /** Полный прогон одного wire-размера: фикстура → cap → снимки → планы → markdown. */
    fun run(
        wireSize: Int,
        seed: Long = TranscriptPerfFixtures.DEFAULT_SEED,
        timingRuns: Int = 5,
    ): TranscriptPerfReport {
        val fixture = TranscriptPerfFixtures.wireTranscript(wireSize, seed)
        val wire = fixture.messages

        // --- Стадия 1: cap-подрезка истории (прод: TranscriptCapPolicy.capMessages). ---
        val capped = TranscriptCapPolicy.capMessages(wire)
        val capNs = measureMedianNs(timingRuns) { TranscriptCapPolicy.capMessages(wire) }
        val cap = CapStage(
            uiSize = capped.size,
            uiBubbleChars = capped.sumOf { it.text.length.toLong() },
            anchorPresent = capped.firstOrNull()?.isTrimAnchor == true,
            trimmedCount = capped.firstOrNull()?.trimmedCount ?: 0,
            largestUiBubbleChars = capped.maxOf { it.text.length },
            capApplyMedianNs = capNs,
            timingRuns = timingRuns,
        )

        // --- Стадия 2: снимки карточек (зеркало ChatActivity.currentSnapshots). ---
        val rendered = snapshots(capped, chromeEpoch = 1L, typing = true)
        val snapshotNs = measureMedianNs(timingRuns) { snapshots(capped, chromeEpoch = 1L, typing = true) }
        val snapshot = SnapshotStage(
            cards = rendered.size,
            daySeparators = rendered.count { it.showDaySeparator },
            snapshotScanMedianNs = snapshotNs,
        )

        // --- Стадия 3: планы рендера на ключевых переходах. ---
        val initialPlan = IncrementalTranscriptPolicy.plan(emptyList(), rendered)

        // Кадр стрима: к хвостовому пузырю приехал токен — единственная грязная карточка.
        val last = capped.last()
        val streamed = capped.dropLast(1) + last.copy(text = last.text + TranscriptPerfFixtures.streamToken(seed, frame = 1))
        val streamedSnaps = snapshots(streamed, chromeEpoch = 1L, typing = true)
        val streamPlan = IncrementalTranscriptPolicy.plan(rendered, streamedSnaps)

        // Ничего не изменилось — дерево не трогаем вообще.
        val noopPlan = IncrementalTranscriptPolicy.plan(rendered, rendered)

        // Новое сообщение в хвост — достройка одной карточки.
        val appendedMsgs = streamed + TranscriptPerfFixtures.userMessage(wireSize + 1, seed)
        val appendedSnaps = snapshots(appendedMsgs, chromeEpoch = 1L, typing = true)
        val appendedPlan = IncrementalTranscriptPolicy.plan(streamedSnaps, appendedSnaps)

        // Ребайнд ДРУГОЙ сессии того же размера — полный пересбор (контракт policy).
        // Метки времени сдвинуты: якорная карточка двух транскриптов с идентичными
        // timestamp'ами дала бы равные снимки, и план выродился бы в Patch(FULL_CARD…)
        // вместо FullRebuild — реальный ребайнд всегда несёт другие метки.
        val otherCapped = TranscriptCapPolicy.capMessages(
            TranscriptPerfFixtures.wireTranscript(wireSize, seed + 1).messages.map {
                it.copy(timestamp = it.timestamp + TranscriptPerfFixtures.TIMESTAMP_STEP_MS)
            }
        )
        val rebindPlan = IncrementalTranscriptPolicy.plan(rendered, snapshots(otherCapped, chromeEpoch = 1L, typing = true))

        val planNs = measureMedianNs(timingRuns) { IncrementalTranscriptPolicy.plan(rendered, streamedSnaps) }
        val rescanNs = measureMedianNs(timingRuns) {
            IncrementalTranscriptPolicy.plan(snapshots(capped, 1L, true), streamedSnaps)
        }
        val renderPlan = RenderPlanStage(
            initialPlan = planName(initialPlan),
            initialFullRebuildCards = (initialPlan as? TranscriptPlan.FullRebuild)?.let { rendered.size } ?: -1,
            streamFramePlan = planName(streamPlan),
            streamFrameTextOnlyPatches = (streamPlan as? TranscriptPlan.Patch)?.updates?.count { it.kind == ru.mars.mobile.CardUpdateKind.TEXT_ONLY } ?: -1,
            streamFrameFullCardPatches = (streamPlan as? TranscriptPlan.Patch)?.updates?.count { it.kind == ru.mars.mobile.CardUpdateKind.FULL_CARD } ?: -1,
            streamFrameAppendFrom = (streamPlan as? TranscriptPlan.Patch)?.appendFrom ?: -1,
            streamFrameAppendedCards = (streamPlan as? TranscriptPlan.Patch)?.let { streamedSnaps.size - it.appendFrom } ?: -1,
            noopPlan = planName(noopPlan),
            appendedPlan = planName(appendedPlan),
            appendedCards = (appendedPlan as? TranscriptPlan.Patch)?.let { appendedSnaps.size - it.appendFrom } ?: -1,
            rebindPlan = planName(rebindPlan),
            snapshotRescanMedianNs = rescanNs,
            planMedianNs = planNs,
        )

        // --- Стадия 4: разбор markdown capped-транскрипта (прод: MarkdownFormatter). ---
        val texts = capped.map { it.text }.filter { it.isNotEmpty() }
        val cacheable = texts.filter { it.length <= MarkdownFormatter.PARSE_CACHE_MAX_LENGTH }
        val warmTexts = cacheable.takeLast(minOf(MarkdownFormatter.PARSE_CACHE_MAX_ENTRIES, cacheable.size))
        val coldNs = measureMedianNs(3) {
            MarkdownFormatter.clearParseCache()
            for (t in texts) MarkdownFormatter.parseBlocks(t)
        }
        MarkdownFormatter.clearParseCache()
        var totalBlocks = 0
        for (t in texts) totalBlocks += MarkdownFormatter.parseBlocks(t).size
        val warmNs = measureMedianNs(timingRuns) {
            for (t in warmTexts) MarkdownFormatter.parseBlocks(t)
        }
        val warmCacheSize = MarkdownFormatter.parseCacheSize()
        val warmSample = warmTexts.last()
        val warmHit = MarkdownFormatter.parseBlocks(warmSample) === MarkdownFormatter.parseBlocks(warmSample)
        val oversizedText = texts.firstOrNull { it.length > MarkdownFormatter.PARSE_CACHE_MAX_LENGTH }
        val oversizedUncached = oversizedText != null &&
            MarkdownFormatter.parseBlocks(oversizedText) !== MarkdownFormatter.parseBlocks(oversizedText)
        MarkdownFormatter.clearParseCache()

        val markdown = MarkdownStage(
            distinctTexts = texts.size,
            cacheableTexts = cacheable.size,
            totalBlocks = totalBlocks,
            warmCacheSize = warmCacheSize,
            warmCacheHitSameInstance = warmHit,
            oversizedTextUncached = oversizedUncached,
            coldParseMedianNs = coldNs,
            warmParseMedianNs = warmNs,
        )

        return TranscriptPerfReport(
            fixture = FixtureStage(
                wireSize = fixture.wireSize,
                seed = fixture.seed,
                bubbleChars = fixture.bubbleChars,
                largestBubbleChars = fixture.largestBubbleChars,
                oversizedBubbles = fixture.oversizedBubbles,
                messagesWithThoughts = fixture.messagesWithThoughts,
                messagesWithTools = fixture.messagesWithTools,
                messagesWithAttachments = fixture.messagesWithAttachments,
            ),
            cap = cap,
            snapshot = snapshot,
            renderPlan = renderPlan,
            markdown = markdown,
        )
    }

    /**
     * Зеркало ChatActivity.currentSnapshots (private, недоступен из JVM-теста):
     * тот же обход mapIndexed с previousTimestamp и streaming-флагом хвоста.
     * Контракт: при изменении currentSnapshots в ChatActivity это зеркало обновить.
     */
    fun snapshots(messages: List<ChatMessage>, chromeEpoch: Long, typing: Boolean): List<CardSnapshot> {
        var previousTimestamp = MessageTimePolicy.UNKNOWN_TIMESTAMP
        val lastIndex = messages.size - 1
        return messages.mapIndexed { index, message ->
            val snapshot = CardSnapshotPolicy.of(
                message = message,
                previousTimestamp = previousTimestamp,
                chromeEpoch = chromeEpoch,
                streamingFlag = typing && index == lastIndex,
            )
            if (message.timestamp > MessageTimePolicy.UNKNOWN_TIMESTAMP) previousTimestamp = message.timestamp
            snapshot
        }
    }

    private fun planName(plan: TranscriptPlan): String = when (plan) {
        TranscriptPlan.FullRebuild -> "FullRebuild"
        TranscriptPlan.NoChange -> "NoChange"
        is TranscriptPlan.Patch -> "Patch"
    }

    /**
     * Медиана nanoTime по [runs] прогонам. ТАЙМИНГИ СПРАВОЧНЫЕ: утверждать
     * wall-clock пороги в тестах запрещено (общий CI-раннер даёт разброс);
     * защищаемые величины — детерминированные счётчики работы в отчёте.
     */
    private fun measureMedianNs(runs: Int, block: () -> Unit): Long {
        val samples = (1..runs.coerceAtLeast(1)).map {
            val t0 = System.nanoTime()
            block()
            System.nanoTime() - t0
        }.sorted()
        return samples[samples.size / 2]
    }
}
