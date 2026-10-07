package ru.mars.mobile

/** UI state and request guards; it contains no credentials or secrets. */
class PairingFlowState {
    enum class Phase { IDLE, CREATING, WAITING, EXCHANGING, CONNECTED, REVOKING, ERROR }

    var phase: Phase = Phase.IDLE
        private set

    fun beginCreate(): Boolean = if (phase == Phase.IDLE || phase == Phase.ERROR) {
        phase = Phase.CREATING
        true
    } else false

    fun created(): Boolean = if (phase == Phase.CREATING) {
        phase = Phase.WAITING
        true
    } else false

    fun beginExchange(): Boolean = if (phase == Phase.WAITING) {
        phase = Phase.EXCHANGING
        true
    } else false

    fun connected(): Boolean = if (phase == Phase.EXCHANGING) {
        phase = Phase.CONNECTED
        true
    } else false

    fun restoreConnected() {
        phase = Phase.CONNECTED
    }

    fun beginRevoke(): Boolean = if (phase == Phase.CONNECTED) {
        phase = Phase.REVOKING
        true
    } else false

    fun revoked(): Boolean = if (phase == Phase.REVOKING) {
        phase = Phase.IDLE
        true
    } else false

    fun failed(): Boolean = if (phase != Phase.CONNECTED && phase != Phase.REVOKING) {
        phase = Phase.ERROR
        true
    } else false
}
