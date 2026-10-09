package ru.mars.mobile

/**
 * Guard асинхронной навигации по диалогам — чистая политика без Android-фреймворка.
 *
 * Каждая навигация (клик по диалогу → асинхронная загрузка деталей) получает токен
 * через [beginNavigation]; её callback применяется к UI, только если токен всё ещё
 * текущий ([isCurrent]) — «побеждает последняя загрузка», устаревшие ответы не
 * перетирают уже открытую сессию. Синхронные смены контекста, после которых чужой
 * callback вреден («новый чат», «финиш», удаление текущего диалога, выход из аккаунта),
 * вызывают [invalidatePending] — все незавершённые навигации становятся устаревшими.
 */
class SessionNavigationPolicy {
    private var generation = 0L

    /** Начинает навигацию: возвращает токен для проверки callback'а в [isCurrent]. */
    fun beginNavigation(): Long = ++generation

    /** Инвалидирует все pending-навигации, не начиная новую. */
    fun invalidatePending() {
        generation++
    }

    /** True, если с момента выдачи токена не было других навигаций/инвалидаций. */
    fun isCurrent(token: Long): Boolean = token == generation
}
