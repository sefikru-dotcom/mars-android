package ru.mars.mobile

/**
 * Кэш снимков карточек между кадрами стрима — чистая JVM-политика (без Android).
 *
 * Проблема, которую закрывает: [CardSnapshotPolicy.of] на каждый кадр считает
 * день-разделитель через `Instant.ofEpochMilli(...).atZone(zone).toLocalDate()`
 * ([MessageTimePolicy.showDaySeparator]) и создаёт новый [CardSnapshot] для КАЖДОГО
 * сообщения истории. В диалоге на 200 сообщений при троттлинге ~20 кадров/с это
 * ~8000 конверсий зон и ~4000 аллокаций снимков в секунду на главном потоке —
 * при том что за кадр меняется ровно одно сообщение (хвост стрима).
 *
 * Правило переиспользования: снимок зависит только от (экземпляр сообщения,
 * предыдущая метка времени, эпоха хрома, стрим-флаг). [ChatUiState] обновляет
 * сообщения на месте (`messages[i] = messages[i].copy(...)`), поэтому неизменённые
 * сообщения сохраняют ТОЖДЕСТВО экземпляра — по нему и переиспользуем снимок,
 * не заходя в календарную арифметику. Изменённое сообщение пересчитывается, как и
 * раньше, вместе с соседом справа (у него мог сдвинуться день-разделитель).
 *
 * Ссылки на сообщения хранятся МАССИВОМ, а не ссылкой на список: вызывающий
 * ([ChatUiState.messages]) правит список на месте, и удержание его же ссылки
 * заставило бы сравнивать элемент сам с собой — снимок «не изменился» навсегда.
 */
class TranscriptSnapshotCache(
    private val build: (ChatMessage, Long, Long, Boolean) -> CardSnapshot = CardSnapshotPolicy::of,
) {

    /** Экземпляры сообщений предыдущего кадра — по индексу. */
    private var previousMessages: Array<Any?> = emptyArray()

    private var snapshots: List<CardSnapshot> = emptyList()

    /** Предыдущая метка времени, с которой считался снимок каждого индекса. */
    private var previousTimestamps: LongArray = LongArray(0)

    /** Снимки всех сообщений: переиспользует неизменившиеся, считает только грязные. */
    fun update(messages: List<ChatMessage>, chromeEpoch: Long, typing: Boolean): List<CardSnapshot> {
        val size = messages.size
        val result = ArrayList<CardSnapshot>(size)
        val timestamps = LongArray(size)
        var previousTimestamp = MessageTimePolicy.UNKNOWN_TIMESTAMP
        val lastIndex = size - 1

        for (index in 0 until size) {
            val message = messages[index]
            val streamingFlag = typing && index == lastIndex
            val cached = snapshots.getOrNull(index)
            val reusable = cached != null &&
                index < previousMessages.size && previousMessages[index] === message &&
                index < previousTimestamps.size && previousTimestamps[index] == previousTimestamp &&
                cached.chromeEpoch == chromeEpoch &&
                cached.streamingFlag == streamingFlag

            result.add(
                if (reusable) {
                    cached!!
                } else {
                    build(message, previousTimestamp, chromeEpoch, streamingFlag)
                }
            )
            timestamps[index] = previousTimestamp
            if (message.timestamp > MessageTimePolicy.UNKNOWN_TIMESTAMP) previousTimestamp = message.timestamp
        }

        // Массив переиспользуется, если размер тот же: на кадре стрима это запись
        // N ссылок без единой аллокации (список сообщений может быть большим).
        val refs = if (previousMessages.size == size) previousMessages else arrayOfNulls<Any?>(size)
        for (index in 0 until size) refs[index] = messages[index]
        previousMessages = refs

        snapshots = result
        previousTimestamps = timestamps
        return result
    }

    /** Сброс (смена сессии): следующий кадр считается с нуля. */
    fun invalidate() {
        previousMessages = emptyArray()
        snapshots = emptyList()
        previousTimestamps = LongArray(0)
    }
}
