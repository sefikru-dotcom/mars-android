package ru.mars.mobile.offline

import ru.mars.mobile.QueuedMessage

/**
 * Потолок очереди на стороне хранилища — чистая функция, отдельно от SQLite.
 *
 * Политика очереди ([ru.mars.mobile.OfflineMessageQueuePolicy]) уже вытесняет старое при
 * переполнении, но БД читается и другим путём: после обновления клиента в таблице может
 * лежать очередь, записанная версией с другим лимитом (или после ручной правки). Загрузка
 * «как есть» подняла бы в память сотни сообщений и снова отправила бы их — поэтому
 * хранилище подрезает снимок до лимита, сохраняя ПОСЛЕДНИЕ (самые новые) записи и их порядок.
 */
object OfflineQueueLimits {

    /**
     * Оставляет не более [maxRows] последних сообщений (порядок FIFO сохраняется).
     * [maxRows] <= 0 трактуется как «без ограничения» — вызывающий сам отвечает за лимит.
     */
    fun bound(messages: List<QueuedMessage>, maxRows: Int): List<QueuedMessage> {
        if (maxRows <= 0 || messages.size <= maxRows) return messages
        return messages.takeLast(maxRows)
    }
}
