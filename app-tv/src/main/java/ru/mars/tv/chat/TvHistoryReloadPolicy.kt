package ru.mars.tv.chat

/**
 * План инкрементальной перезагрузки истории в ТВ-ленте.
 *
 * Собирается по снимкам строк ([ru.mars.tv.TvSessionBrowserPolicy.TranscriptRow])
 * «что уже нарисовано» -> «что должно быть нарисовано». Хост применяет план
 * точечно: неизменённые пузыри остаются теми же View.
 */
sealed interface TvHistoryReloadPlan {
    /** Ничего не изменилось — лента не трогается вообще. */
    data object NoChange : TvHistoryReloadPlan

    /** Прежние строки остаются префиксом: дорисовать хвост с индекса [fromIndex]. */
    data class Append(val fromIndex: Int) : TvHistoryReloadPlan

    /**
     * Середина изменилась: [keepPrefix] первых и [keepSuffix] последних View
     * переиспользуются как есть, диапазон `[rebuildFrom, rebuildTo)` пересобирается.
     */
    data class Patch(
        val keepPrefix: Int,
        val keepSuffix: Int,
        val rebuildFrom: Int,
        val rebuildTo: Int,
    ) : TvHistoryReloadPlan

    /** Переиспользовать нечего (смена сессии, лента была пуста/стала пустой) — полная сборка. */
    data object FullRebuild : TvHistoryReloadPlan
}

/**
 * Чистая политика инкрементальной перезагрузки истории ТВ-чата.
 *
 * Проблема: прежний путь сносил ленту целиком (`removeAllViews`) и инфлейтил ВСЮ
 * историю заново на главном потоке. На длинном транскрипте (до
 * [TvTranscriptRetentionPolicy.MAX_RENDERED_MESSAGES] пузырей, каждый — TextView
 * либо rich-markdown с регексами) это давало ANR на перезагрузке истории/транскрипта.
 * Реальный кадр перезагрузки почти всегда — «прежняя история + хвост» (ответ
 * доехал, пока лента была в фоне) либо «изменился последний пузырь»: и то, и
 * другое решается без сноса неизменившихся View.
 *
 * Правила (по индексам, без эвристик по содержимому):
 * - оба снимка пусты — [TvHistoryReloadPlan.NoChange];
 * - прежний пуст — [TvHistoryReloadPlan.Append] с нуля (сносить нечего);
 * - новый пуст — [TvHistoryReloadPlan.FullRebuild] (лента чистится);
 * - новый длиннее и начинается прежним — [TvHistoryReloadPlan.Append] (частый случай);
 * - иначе общий префикс + общий суффикс, пересобирается только середина;
 * - если не переиспользуется ни одна строка при равной длине — [TvHistoryReloadPlan.FullRebuild].
 *
 * Сравнение — структурное равенство data-класса строки (автор/текст/роль), поэтому
 * «тот же текст» не считается изменением.
 */
object TvHistoryReloadPolicy {

    fun <T> plan(previous: List<T>, next: List<T>): TvHistoryReloadPlan {
        if (previous.isEmpty() && next.isEmpty()) return TvHistoryReloadPlan.NoChange
        if (previous.isEmpty()) return TvHistoryReloadPlan.Append(0)
        if (next.isEmpty()) return TvHistoryReloadPlan.FullRebuild
        if (previous == next) return TvHistoryReloadPlan.NoChange
        if (next.size > previous.size && startsWith(next, previous)) {
            return TvHistoryReloadPlan.Append(previous.size)
        }

        val shared = minOf(previous.size, next.size)
        var prefix = 0
        while (prefix < shared && previous[prefix] == next[prefix]) prefix++
        var suffix = 0
        while (suffix < shared - prefix &&
            previous[previous.size - 1 - suffix] == next[next.size - 1 - suffix]
        ) {
            suffix++
        }
        // Ничего не переиспользуется — честный полный пересбор (тот же путь, что и раньше,
        // но только когда история действительно другая целиком).
        if (prefix == 0 && suffix == 0) return TvHistoryReloadPlan.FullRebuild
        return TvHistoryReloadPlan.Patch(
            keepPrefix = prefix,
            keepSuffix = suffix,
            rebuildFrom = prefix,
            rebuildTo = next.size - suffix,
        )
    }

    /** [candidate] начинается ровно списком [prefix] (поэлементно, структурно). */
    private fun <T> startsWith(candidate: List<T>, prefix: List<T>): Boolean {
        if (candidate.size < prefix.size) return false
        for (index in prefix.indices) {
            if (candidate[index] != prefix[index]) return false
        }
        return true
    }
}
