package ru.mars.mobile

/**
 * Состояние сети глазами приложения — чистая модель без Android-фреймворка.
 *
 * [sendAllowed] — можно ли пытаться отправлять сообщения сразу: ONLINE и UNVALIDATED
 * разрешают отправку (проверка валидации интернета занимает до секунды после подключения,
 * а серверная ошибка отправки сама по себе хороший сигнал), LOSING и OFFLINE — нет,
 * текст уходит в офлайн-очередь ([OfflineMessageQueuePolicy]).
 */
enum class NetworkState(val sendAllowed: Boolean) {
    /** Сеть доступна и валидирована (NET_CAPABILITY_VALIDATED). */
    ONLINE(true),

    /** Сеть доступна, но валидация интернета ещё не прошла («подключаемся»). */
    UNVALIDATED(true),

    /** onLosing: сеть жива, но сейчас умрёт (handover/лимит) — отправку лучше буферизовать. */
    LOSING(false),

    /** Сети нет (onLost / нет активного сетевого подключения / локальная сеть без интернета). */
    OFFLINE(false),
}

/** События платформенного детектора ([NetworkConnectivityObserver]) для чистой машины состояний. */
sealed interface NetworkEvent {
    /** Сеть стала активной ([android.net.ConnectivityManager.NetworkCallback.onAvailable]). */
    data class Available(val validated: Boolean) : NetworkEvent

    /** Обновились NetworkCapabilities активной сети (флаг validated). */
    data class CapabilitiesChanged(val validated: Boolean) : NetworkEvent

    /** Сеть сейчас будет потеряна (onLosing). */
    data object Losing : NetworkEvent

    /** Сеть потеряна / активной сети нет (onLost, стартовый снимок без сети). */
    data object Lost : NetworkEvent
}

/**
 * Переход [previous] → [current]. Флаги считаются по [NetworkState.sendAllowed]:
 * [restored] — связь вернулась (триггер флаша офлайн-очереди и баннера восстановления),
 * [dropped] — связь пропала (триггер перехода на буферизацию отправлений).
 */
data class NetworkTransition(val previous: NetworkState, val current: NetworkState) {
    val changed: Boolean get() = previous != current
    val restored: Boolean get() = !previous.sendAllowed && current.sendAllowed
    val dropped: Boolean get() = previous.sendAllowed && !current.sendAllowed
}

/**
 * Чистая машина состояний сети: редуцирует события [NetworkEvent] в [NetworkState].
 * Платформенная обвязка (ConnectivityManager-колбэки, потоки) живёт в
 * [NetworkConnectivityObserver]; все решения — здесь, поэтому покрыты JVM-тестами.
 *
 * Правила редукции:
 * - Available/CapabilitiesChanged(validated=true)  → ONLINE;
 * - Available/CapabilitiesChanged(validated=false) → UNVALIDATED (в т.ч. выход из LOSING:
 *   новые capabilities активной сети означают, что handover состоялся);
 * - Losing → LOSING;
 * - Lost → OFFLINE.
 *
 * Начальное состояние — OFFLINE («не доказано обратное»): до первого снимка/события
 * отправка буферизуется, а не падает в сеть, которой может не быть.
 */
class NetworkStatePolicy(initial: NetworkState = NetworkState.OFFLINE) {

    var state: NetworkState = initial
        private set

    /** Применяет событие и возвращает переход (для подписчиков: баннер, флаш очереди). */
    fun onEvent(event: NetworkEvent): NetworkTransition {
        val previous = state
        state = reduce(previous, event)
        return NetworkTransition(previous, state)
    }

    companion object {
        /**
         * Чистая функция редукции — те же правила, что в [onEvent], без мутации состояния.
         * Сигнатура классического редьюсера (state, event): текущее состояние не влияет
         * на исход (события несут полную информацию), но сохраняется для единого контракта.
         */
        @Suppress("UNUSED_PARAMETER")
        fun reduce(current: NetworkState, event: NetworkEvent): NetworkState = when (event) {
            is NetworkEvent.Available -> if (event.validated) NetworkState.ONLINE else NetworkState.UNVALIDATED
            is NetworkEvent.CapabilitiesChanged -> if (event.validated) NetworkState.ONLINE else NetworkState.UNVALIDATED
            NetworkEvent.Losing -> NetworkState.LOSING
            NetworkEvent.Lost -> NetworkState.OFFLINE
        }
    }
}
