package io.github.narthexfire.kettle.data

/**
 * Pull-to-refresh on the main screen. Connected: only a keep-alive (never a kettle command).
 * Not connected: a fresh reconnect, then the kettle search if the saved address still fails.
 */
enum class RefreshAction {
    KeepAlive, Reconnect, NotAvailable;

    companion object {
        fun decide(isSetUp: Boolean, enabled: Boolean, connected: Boolean): RefreshAction = when {
            !isSetUp || !enabled -> NotAvailable
            connected -> KeepAlive
            else -> Reconnect
        }
    }
}
