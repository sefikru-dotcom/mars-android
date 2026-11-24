package ru.mars.network.repository

/**
 * Чистая JVM-политика «кого греть» для серверной предзагрузки следующего трека.
 *
 * Решает и только решает: нужен ли POST /api/preload/{id} при старте текущего трека.
 * Никакой сети, Android и корутин — решение принимается по снимку очереди и
 * идентификатору последней поданной заявки, которую хранит вызывающий (плеер).
 *
 * Правила:
 * - пустой/blank id, пустая очередь, отсутствие следующего трека → [Skip];
 * - repeat ONE и очередь из одного элемента → [Skip]: следующий тот же самый трек;
 * - повтор того же id, который уже был подан на предзагрузку → [Skip] (не греть дважды);
 * - иначе → [Warm] с id следующего трека.
 *
 * Shuffle и repeat здесь НЕ пересчитываются заново: вызывающий передаёт id, который
 * уже выбрал [MusicQueueManager.nextIndex] — единственный владелец порядка,
 * shuffle и repeat. Политика лишь отсекает бессмысленные и повторные заявки.
 */
object MusicPreloadPolicy {

    /** Решение политики: [id] — трек, который стоит подогреть. */
    sealed interface Decision {
        data object Skip : Decision
        data class Warm(val id: String) : Decision
    }

    /**
     * Решение по снимку очереди: [nextId] — id следующего по очереди трека (уже с учётом
     * shuffle/repeat), [currentId] — id стартующего сейчас трека, [lastPreloadedId] —
     * id последней поданной заявки (null, если её ещё не было).
     */
    fun decide(
        nextId: String?,
        currentId: String?,
        lastPreloadedId: String?,
        queueSize: Int = 1,
        repeatOne: Boolean = false,
    ): Decision {
        if (nextId.isNullOrBlank()) return Decision.Skip
        val next = nextId.trim()
        if (next.isBlank()) return Decision.Skip
        // Очередь из одного элемента (и repeat ONE в частности): «следующий» — тот же трек.
        if (queueSize <= 1) return Decision.Skip
        if (repeatOne && next == currentId?.trim()) return Decision.Skip
        // Не греть повторно тот же id, даже если очередь сдвинулась и он снова стал следующим.
        if (next == lastPreloadedId?.trim()) return Decision.Skip
        return Decision.Warm(next)
    }
}
