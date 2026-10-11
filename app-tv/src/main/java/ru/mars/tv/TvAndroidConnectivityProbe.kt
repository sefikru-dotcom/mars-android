package ru.mars.tv

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Production [TvConnectivityProbe] over [ConnectivityManager] (needs the manifest's
 * `ACCESS_NETWORK_STATE`): [isNetworkAvailable] is the instantaneous
 * active-network snapshot (INTERNET capability required — a connected-but-captive
 * network without it does not count), and [awaitNetworkAvailable] parks the recovery
 * loop in a `registerDefaultNetworkCallback` observer until the radio comes back,
 * resuming immediately when the network is already up.
 *
 * Defensive degradation: without a ConnectivityManager (or when registration throws on
 * an exotic TV ROM) the probe reports "available" and never parks — recovery then falls
 * back to the coordinator's bounded backoff instead of hanging forever on a probe that
 * cannot observe anything.
 */
class TvAndroidConnectivityProbe(context: Context) : TvConnectivityProbe {

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun isNetworkAvailable(): Boolean {
        val manager = connectivityManager ?: return true
        return try {
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true // a throwing ROM must not park recovery forever
        }
    }

    override suspend fun awaitNetworkAvailable() {
        if (isNetworkAvailable()) return
        val manager = connectivityManager ?: return
        suspendCancellableCoroutine { continuation ->
            var registered = false
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (registered) runCatching { manager.unregisterNetworkCallback(this) }
                    registered = false
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    // A network that regains INTERNET capability counts as "back".
                    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        onAvailable(network)
                    }
                }
            }
            try {
                manager.registerDefaultNetworkCallback(callback)
                registered = true
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume(Unit)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation {
                if (registered) runCatching { manager.unregisterNetworkCallback(callback) }
                registered = false
            }
            // The network may have come back between the snapshot and the registration.
            if (isNetworkAvailable() && continuation.isActive) {
                if (registered) runCatching { manager.unregisterNetworkCallback(callback) }
                registered = false
                continuation.resume(Unit)
            }
        }
    }
}
