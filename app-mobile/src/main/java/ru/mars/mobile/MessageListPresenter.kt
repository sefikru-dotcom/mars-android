package ru.mars.mobile

/**
 * Владелец решения «что перерисовать на этом кадре» для списка сообщений.
 *
 * Разбиение ChatActivity (W4) оставило рендер транскрипта в [ChatTranscriptSection],
 * а выбор «грязных» карточек — в чистой [IncrementalTranscriptPolicy]. Этот класс —
 * единственная точка, где Activity спрашивает план, и единственное место, где
 * считается, сколько карточек трогает кадр. Смысл: гарантия «на кадре стрима
 * обновляется ровно хвостовая карточка» должна быть проверяемой, а не следствием
 * того, что кто-то не добавил ещё один вызов renderMessages().
 *
 * Класс намеренно без Android-зависимостей: план и счётчики проверяются JVM-тестами
 * (см. MessageListPresenterTest) на длинных транскриптах.
 */
class MessageListPresenter(
    private val policy: IncrementalTranscriptPolicy = IncrementalTranscriptPolicy,
) {

    /** Сколько кадров прошло через презентер (диагностика/тесты). */
    var frames: Long = 0L
        private set

    /** Сколько кадров стрима тронули ровно одну карточку (целевое поведение). */
    var singleCardStreamFrames: Long = 0L
        private set

    /** Последний план — чтобы тесты и отладка видели, что именно решили. */
    var lastPlan: TranscriptPlan = TranscriptPlan.NoChange
        private set

    /**
     * План кадра. [streaming] — идёт ли сейчас ход (TYPING): на кадре стрима хвост
     * растёт, поэтому «одна грязная карточка» — ожидаемая норма, а не совпадение.
     */
    fun plan(
        previous: List<CardSnapshot>,
        current: List<CardSnapshot>,
        streaming: Boolean,
    ): TranscriptPlan {
        frames++
        val plan = policy.plan(previous, current)
        lastPlan = plan
        if (streaming && touchesSingleCard(plan, current)) singleCardStreamFrames++
        return plan
    }

    /**
     * Тронута ли кадром ровно одна карточка (и это хвост)?
     *
     * Append-only кадр (новое сообщение в конце) тоже считается одним касанием: план
     * с пустым [TranscriptPlan.Patch.updates] и `appendFrom == size - 1` доинфлейтит
     * только новую карточку.
     */
    fun touchesSingleCard(plan: TranscriptPlan, current: List<CardSnapshot>): Boolean = when (plan) {
        TranscriptPlan.NoChange -> true
        TranscriptPlan.FullRebuild -> false
        is TranscriptPlan.Patch ->
            plan.updates.size + (current.size - plan.appendFrom) == 1
    }
}
