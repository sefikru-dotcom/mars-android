package ru.mars.mobile

/**
 * Guard живого хода send-пути (SSE-collect после `POST /api/chat/start`) — чистая
 * политика без Android-фреймворка.
 *
 * Инвариант: поздние события старого хода не должны менять новый экран. Каждый
 * отправочный стрим получает токен через [beginTurn]; любая смена контекста
 * (переключение диалога, «новый чат», удаление/финиш текущей сессии, «стоп»,
 * logout, реавторизация, destroy) вызывает [invalidate] — после него события
 * убитого хода не применяются к транскрипту ([isCurrent] false), даже если
 * отмена корутины collect по какой-то причине запаздывает или путь ухода
 * забыл отменить job.
 *
 * Recovery-цикл ([MidTurnRecoveryController]) защищён собственным generation
 * guard'ом; этот guard закрывает именно первичный collect send-пути, у которого
 * раньше защиты не было: поздние чанки/SessionBound старого хода могли перекрасить
 * только что открытый диалог и переписать currentSessionId обратно на старую сессию.
 *
 * Все вызовы происходят в main-потоке (диспетчер Activity/collect), поэтому
 * volatile/атомики не нужны — как и в [SessionNavigationPolicy].
 */
class LiveTurnGuard {
    private var generation = 0L

    /** Регистрирует новый живой ход: возвращает токен для проверки событий в collect. */
    fun beginTurn(): Long = ++generation

    /** Гасит текущий токен: поздние события убитого хода больше не проходят. */
    fun invalidate() {
        generation++
    }

    /** True, если ход всё ещё владеет экраном; токен 0 — фантомный, никогда не текущий. */
    fun isCurrent(token: Long): Boolean = token != 0L && token == generation
}
