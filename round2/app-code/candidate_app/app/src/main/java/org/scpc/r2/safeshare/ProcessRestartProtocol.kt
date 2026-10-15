package org.scpc.r2.safeshare

internal object ProcessRestartProtocol {
    const val PREFERENCES = "intent_relay_demo"
    const val KEY_RESTART_PENDING = "restart_pending"
    const val KEY_PREVIOUS_PID = "previous_pid"
    const val KEY_RESTART_SCHEDULED_AT = "restart_scheduled_at"
    const val EXTRA_PREVIOUS_PID = "org.scpc.r2.safeshare.PREVIOUS_PID"

    fun isPidChangeVerified(previousPid: Int, currentPid: Int): Boolean =
        previousPid > 0 && currentPid > 0 && previousPid != currentPid
}
