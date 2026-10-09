package ru.mars.mobile

/**
 * План пересоздания экрана (чистая политика): какой диалог открыть после пересоздания Activity.
 */
sealed interface RecreationPlan {
    /** Заново открыть сохранённый диалог по его id (загрузка идёт через общий guard навигации). */
    data class Reopen(val sessionId: String) : RecreationPlan

    /** Восстанавливать нечего — старт с нового чата. */
    data object FreshStart : RecreationPlan
}

/**
 * Политика сохранения открытого диалога при пересоздании Activity (поворот экрана, тёмная тема,
 * смерть процесса с восстановлением) — чистая логика без Android-фреймворка. Хост
 * (ChatActivity) дёргает два тонких хука:
 *
 * - [stateToPersist] перед onSaveInstanceState — нормализует текущий session_id для записи
 *   (пустое значение не сохраняется: новый чат без диалога восстанавливать нечем);
 * - [planFor] в onCreate — одноразовое решение о восстановлении из сохранённого состояния:
 *   первый валидный id становится [RecreationPlan.Reopen], всё остальное (состояния нет,
 *   пустой id, повторный вызов) — [RecreationPlan.FreshStart].
 *
 * Одноразовость гарантирует, что один экземпляр Activity не восстановит диалог дважды:
 * дальше текущий диалог меняется только навигацией пользователя. После пересоздания
 * создаётся новый экземпляр политики, и сохранённый id снова отрабатывает один раз.
 *
 * Исчезнувшая на сервере сессия политикой не проверяется: загрузка деталей идёт через
 * общий guard [SessionNavigationPolicy], а null-детали просто оставляют новый чат.
 */
class SessionRecreationPolicy {
    private var restoreConsumed = false

    /** Значение для onSaveInstanceState: session_id без обрамляющих пробелов либо null. */
    fun stateToPersist(currentSessionId: String?): String? =
        currentSessionId?.trim()?.takeIf { it.isNotEmpty() }

    /** Одноразовый план восстановления из savedInstanceState (см. [RecreationPlan]). */
    fun planFor(savedSessionId: String?): RecreationPlan {
        if (restoreConsumed) return RecreationPlan.FreshStart
        restoreConsumed = true
        return stateToPersist(savedSessionId)
            ?.let { RecreationPlan.Reopen(it) }
            ?: RecreationPlan.FreshStart
    }
}
