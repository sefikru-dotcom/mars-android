package ru.mars.mobile

/**
 * Решения офлайн-очереди — чистая JVM-политика (без Android).
 *
 * Сама очередь ([OfflineMessageQueuePolicy]) хранит и персистит строки; здесь только
 * решения, которые раньше были размазаны по `sendMessage`/`flushOfflineQueue`/
 * `dispatchQueuedMessage`:
 * 1. Что делать с отправкой без сети: буферизовать текст, отказать вложениям (multipart
 *    офлайн не повторить — чипы остаются в композиторе) или отправлять как обычно.
 * 2. Когда флашить очередь: только после первичного восстановления диалога, при сети и
 *    вне активного хода — иначе следующее сообщение уехало бы поверх текущего.
 * 3. Под каким ключом возвращать недоставленное сообщение: сессия могла создаться до
 *    сбоя потока, и строка под `null` не дождалась бы флаша диалога.
 */
class ChatOfflineQueuePolicy {

    /** Вердикт по нажатию «отправить» при известном состоянии сети. */
    enum class SendVerdict {
        /** Сеть есть — обычный путь отправки. */
        SEND_NOW,

        /** Сети нет и есть вложения: буферизовать нельзя, показываем статус. */
        BLOCK_ATTACHMENT,

        /** Сети нет, только текст: уходит в персистентную очередь с локальным эхом. */
        BUFFER,
    }

    /**
     * Что делать с отправкой.
     *
     * @param networkSendAllowed сеть позволяет отправку ([NetworkStatePolicy]).
     * @param attachmentCount число вложений в композиторе.
     */
    fun sendVerdict(networkSendAllowed: Boolean, attachmentCount: Int): SendVerdict = when {
        networkSendAllowed -> SendVerdict.SEND_NOW
        attachmentCount > 0 -> SendVerdict.BLOCK_ATTACHMENT
        else -> SendVerdict.BUFFER
    }

    /**
     * Можно ли автоотправлять очередь.
     *
     * @param initialRestoreDone первичное восстановление диалога завершено — до него
     *   `currentSessionId` ещё не привязан, и флаш ушёл бы не в тот диалог.
     * @param networkSendAllowed сеть позволяет отправку.
     * @param typing идёт активный ход: следующее сообщение диспетчеризуется из его `finally`.
     */
    fun canFlush(
        initialRestoreDone: Boolean,
        networkSendAllowed: Boolean,
        typing: Boolean,
    ): Boolean = initialRestoreDone && networkSendAllowed && !typing

    /**
     * Ключ сессии для возврата недоставленного сообщения: актуальный диалог важнее
     * исходного (сессия могла создаться уже после буферизации).
     */
    fun rebindSession(currentSessionId: String?, queuedSessionId: String?): String? =
        currentSessionId ?: queuedSessionId

    /** Тексты PENDING-строк текущего диалога — для локального эха в транскрипте. */
    fun echoTexts(pending: List<QueuedMessage>): List<String> = pending.map { it.text }
}
