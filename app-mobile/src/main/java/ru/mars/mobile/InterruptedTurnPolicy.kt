package ru.mars.mobile

/**
 * Локальный буфер прерванного хода: что успело прийти по SSE и что было видно
 * пользователю в момент, когда процесс убили / экран пересоздали.
 *
 * Зачем: серверный журнал (`GET /api/chat/stream?stream_id=...`) восстанавливает
 * ход, пока он ЖИВ на сервере. Если ход уже завершился (или журнал не пережил
 * рестарт бэкенда), приложение показало бы только то, что сервер успел сохранить в
 * историю — последние дельты, которые пользователь видел, исчезали. Буфер закрывает
 * именно этот зазор: он хранит ПОСЛЕДНИЙ видимый текст ответа и позволяет догнать
 * историю после холодного старта.
 */
data class InterruptedTurnSnapshot(
    val sessionId: String,
    val streamId: String?,
    val text: String,
    val savedAtMs: Long,
)

/** Решение «что делать с локальным буфером» при открытии диалога. */
sealed interface InterruptedTurnRecovery {
    /** Буфера нет / он не про этот диалог / устарел / сервер уже знает больше. */
    data object None : InterruptedTurnRecovery

    /**
     * Хвост ответа, которого нет в серверной истории: его надо дописать в транскрипт,
     * иначе видимый пользователю текст потерян.
     */
    data class Recovered(val appendText: String) : InterruptedTurnRecovery
}

/**
 * Чистая политика согласования локального буфера с серверной историей.
 *
 * Правила (по порядку):
 * 1. нет буфера, пустой текст, чужой диалог, старше [ttlMs] → [InterruptedTurnRecovery.None]
 *    (чужая/древняя запись не должна всплывать в другом чате);
 * 2. ход ЖИВ на сервере ([serverTurnActive]) → None: сервер переиграет журнал целиком,
 *    а локальная вставка дала бы дубль текста;
 * 3. серверная история пуста/короче и является префиксом локального текста →
 *    [InterruptedTurnRecovery.Recovered] с хвостом (ровно то, что потерялось);
 * 4. сервер знает столько же или больше (или тексты разошлись) → None: источник истины
 *    сервер, локальный буфер не должен «перебивать» финальный ответ.
 */
object InterruptedTurnPolicy {

    /** 6 часов: дольше ход не живёт, а старый буфер уже не «продолжение», а мусор. */
    const val DEFAULT_TTL_MS: Long = 6L * 60 * 60 * 1000

    fun reconcile(
        snapshot: InterruptedTurnSnapshot?,
        sessionId: String?,
        serverText: String?,
        serverTurnActive: Boolean,
        nowMs: Long,
        ttlMs: Long = DEFAULT_TTL_MS,
    ): InterruptedTurnRecovery {
        if (snapshot == null) return InterruptedTurnRecovery.None
        if (sessionId == null || snapshot.sessionId != sessionId) return InterruptedTurnRecovery.None
        val local = snapshot.text
        if (local.isBlank()) return InterruptedTurnRecovery.None
        if (nowMs - snapshot.savedAtMs > ttlMs) return InterruptedTurnRecovery.None
        if (serverTurnActive) return InterruptedTurnRecovery.None

        val server = serverText.orEmpty()
        if (server.isBlank()) return InterruptedTurnRecovery.Recovered(local)
        // Сервер сохранил ровно то, что видел пользователь — дописывать нечего.
        if (server == local) return InterruptedTurnRecovery.None
        // Серверная история — префикс видимого текста: хвост потерян при падении.
        if (local.startsWith(server)) {
            val tail = local.substring(server.length)
            return if (tail.isBlank()) InterruptedTurnRecovery.None else InterruptedTurnRecovery.Recovered(tail)
        }
        // Сервер впереди или тексты разошлись (перегенерация/правка) — верим серверу.
        return InterruptedTurnRecovery.None
    }
}
