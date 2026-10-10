package ru.mars.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

/**
 * Платформенная детекция состояния сети через `ConnectivityManager.registerDefaultNetworkCallback`
 * (API 24+; minSdk проекта 28). Тонкая обвязка без решений: колбэки системы переводятся в
 * события [NetworkEvent] и подаются в чистую машину [NetworkStatePolicy], все переходы
 * отдаются подписчику на главной ветке (колбэки приходят с ConnectivityThread).
 *
 * [start] дополнительно делает синхронный снимок текущего подключения, поэтому стартовое
 * состояние известно сразу, а не после первого системного колбэка: баннер корректен
 * с первого кадра. Снимок без активного network или без INTERNET-способностей трактуется
 * как [NetworkEvent.Lost] — сервер Марса удалённый, локальная сеть без интернета бесполезна.
 *
 * Требует permission ACCESS_NETWORK_STATE (добавлен в манифест app-mobile).
 */
class NetworkConnectivityObserver(
    private val context: Context,
    private val policy: NetworkStatePolicy = NetworkStatePolicy(),
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val onTransition: (NetworkTransition) -> Unit,
) {

    private var callback: ConnectivityManager.NetworkCallback? = null

    private val connectivityManager: ConnectivityManager?
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** Текущее состояние сети (OFFLINE до [start]). */
    val state: NetworkState get() = policy.state

    /** Регистрирует колбэк и сразу публикует снимок текущего состояния. Повторный вызов — no-op. */
    fun start() {
        if (callback != null) return
        feed(snapshotEvent())
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = capabilitiesOf(network)
                feed(
                    NetworkEvent.Available(
                        validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                    ),
                )
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                feed(capabilities.toEvent())
            }

            override fun onLosing(network: Network, maxMsToLive: Int) {
                feed(NetworkEvent.Losing)
            }

            override fun onLost(network: Network) {
                feed(NetworkEvent.Lost)
            }
        }
        runCatching { connectivityManager?.registerDefaultNetworkCallback(cb) }
            .onSuccess { callback = cb }
    }

    /** Снимает колбэк; безопасно вызывать повторно и после неудачной регистрации. */
    fun stop() {
        val cb = callback ?: return
        callback = null
        runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
    }

    private fun capabilitiesOf(network: Network): NetworkCapabilities? =
        runCatching { connectivityManager?.getNetworkCapabilities(network) }.getOrNull()

    private fun snapshotEvent(): NetworkEvent {
        val cm = connectivityManager ?: return NetworkEvent.Lost
        val network = runCatching { cm.activeNetwork }.getOrNull() ?: return NetworkEvent.Lost
        val capabilities = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
            ?: return NetworkEvent.Lost
        return capabilities.toEvent()
    }

    /**
     * NetworkCapabilities → событие политики. Онлайн только при NET_CAPABILITY_INTERNET:
     * сеть без интернет-способности (локальный Wi-Fi без шлюза) для удалённого сервера
     * эквивалентна офлайну. Флаг validated различает ONLINE и UNVALIDATED.
     */
    private fun NetworkCapabilities.toEvent(): NetworkEvent =
        if (hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            NetworkEvent.CapabilitiesChanged(validated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        } else {
            NetworkEvent.Lost
        }

    private fun feed(event: NetworkEvent) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            deliver(event)
        } else {
            mainHandler.post { deliver(event) }
        }
    }

    private fun deliver(event: NetworkEvent) {
        onTransition(policy.onEvent(event))
    }
}
