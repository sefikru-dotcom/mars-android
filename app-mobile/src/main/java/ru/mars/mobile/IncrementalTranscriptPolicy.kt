package ru.mars.mobile

/** Как именно изменилась одна карточка между двумя снимками. */
enum class CardUpdateKind {
    /** Снимки равны — view не трогается. */
    NONE,

    /**
     * Изменился ТОЛЬКО текст пузыря, окружение то же: можно переиспользовать view
     * карточки и пересобрать лишь её markdown-секцию (самый частый кадр стрима).
     * Пустой→непустой текст сюда НЕ попадает (секции ещё нет — нужна вся карточка).
     */
    TEXT_ONLY,

    /** Изменилось окружение (мысли/инструменты/статус/вложения/эпоха) — карточка пересобирается целиком. */
    FULL_CARD,
}

/** План инкрементального рендера транскрипта. */
sealed interface TranscriptPlan {
    /** Контейнер сносятся и строится заново (смена сессии, подрезка, первая отрисовка, эпоха). */
    data object FullRebuild : TranscriptPlan

    /** Ничего не изменилось — view-дерево не трогается вообще. */
    data object NoChange : TranscriptPlan

    /**
     * Точечная заплатка: [updates] — пересборка отдельных существующих карточек,
     * [appendFrom] — индекс первого нового сообщения (доставляется в хвост контейнера).
     */
    data class Patch(
        val updates: List<CardPatch>,
        val appendFrom: Int,
    ) : TranscriptPlan
}

/** Одна позиция заплатки: индекс карточки и глубина её обновления. */
data class CardPatch(val index: Int, val kind: CardUpdateKind)

/**
 * Выбор «грязных» карточек для инкрементального рендера — чистая JVM-политика.
 *
 * Проблема: прежний renderMessages() сносил контейнер (removeAllViews) и инфлейтил
 * ВСЮ историю на каждый SSE-чанк — O(сообщения) инфлейтов + O(суммарный текст)
 * регексов на главном потоке за кадр → ANR. Политика сводит кадр стрима к ОДНОЙ
 * карточке: снапшоты сравниваются структурно; история перерисовывается только
 * когда реально изменилась (смена сессии, cap-подрезка, смена эпохи окружения).
 *
 * Правила:
 * - список сжался или опустел/заполнился впервые — [TranscriptPlan.FullRebuild]
 *   (дешевле и безопаснее, чем высчитывать сдвиги индексов);
 * - отличается ВЕСЬ общий префикс при длине > 1 — тоже FullRebuild (смена сессии:
 *   N точечных замен эквивалентны полному пересбору, но с лишним промежуточными
 *   состояниями контейнера);
 * - иначе [TranscriptPlan.Patch]: точечные замены + доинфлейт хвоста.
 */
object IncrementalTranscriptPolicy {

    fun plan(previous: List<CardSnapshot>, current: List<CardSnapshot>): TranscriptPlan {
        if (previous.isEmpty() && current.isEmpty()) return TranscriptPlan.NoChange
        if (previous.isEmpty() || current.isEmpty()) return TranscriptPlan.FullRebuild
        if (current.size < previous.size) return TranscriptPlan.FullRebuild

        val shared = previous.size
        val updates = (0 until shared).mapNotNull { index ->
            when (val kind = classify(previous[index], current[index])) {
                CardUpdateKind.NONE -> null
                else -> CardPatch(index, kind)
            }
        }
        if (updates.size == shared && shared > 1) return TranscriptPlan.FullRebuild
        if (updates.isEmpty() && current.size == shared) return TranscriptPlan.NoChange
        return TranscriptPlan.Patch(updates, shared)
    }

    /** Классификация изменения одной карточки: [CardUpdateKind.NONE]/[CardUpdateKind.TEXT_ONLY]/[CardUpdateKind.FULL_CARD]. */
    fun classify(old: CardSnapshot?, new: CardSnapshot): CardUpdateKind = when {
        old == null -> CardUpdateKind.FULL_CARD // карточки ещё нет — строится целиком
        old == new -> CardUpdateKind.NONE
        // Text-only fast path: окружение то же, оба текста непустые (markdown-секция уже
        // существует — иначе карточка собирается целиком), якорная карточка не участвует.
        old.sameChrome(new) &&
            old.textLength > 0 && new.textLength > 0 &&
            !old.isTrimAnchor && !new.isTrimAnchor -> CardUpdateKind.TEXT_ONLY
        else -> CardUpdateKind.FULL_CARD
    }
}
