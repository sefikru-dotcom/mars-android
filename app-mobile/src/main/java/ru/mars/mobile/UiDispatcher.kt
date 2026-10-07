package ru.mars.mobile

import android.os.Handler
import android.os.Looper

/**
 * Thread-confinement seam for [InteractiveRequestController]: every mutation of controller and
 * [ChatUiState] state (dialog identity/generation guards, respond-result handling, the accepted
 * clarify echo) is funnelled through [dispatch] so it all happens on ONE thread — the UI thread
 * in production. Gateway HTTP stays on the controller's background [java.util.concurrent.Executor];
 * only its result application is dispatched here.
 *
 * The interface is deliberately Android-free so unit tests can drive it inline or queued (clean
 * seam instead of framework stubs); the production implementation is [MainThreadDispatcher].
 */
fun interface UiDispatcher {
    fun dispatch(block: () -> Unit)
}

/**
 * Runs [block] inline when already on the main thread, otherwise posts it to the main looper —
 * the same semantics as `Activity#runOnUiThread`, without capturing an Activity. Per the project
 * convention this class touches Android framework objects and is therefore exercised on real
 * devices only; unit tests substitute fake [UiDispatcher]s.
 */
class MainThreadDispatcher : UiDispatcher {
    private val handler = Handler(Looper.getMainLooper())

    override fun dispatch(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }
}
