package io.github.narthexfire.kettle.net

/**
 * When to ask Android for a local-only connection to the kettle's own Wi-Fi (and when to let it go).
 * Pure logic, unit-tested; [LocalWifi] carries out the decision.
 */
object LocalWifiPolicy {
    enum class Action { Request, Release, None, /** Release and request again with the new SSID/passphrase. */ Rerequest }

    data class Input(
        val foreground: Boolean,
        val sdkSupportsSpecifier: Boolean,
        /** The selected kettle: set up, enabled, auto-join on, with an SSID and passphrase. */
        val selectedId: String?,
        val autoJoin: Boolean,
        val kettleSsid: String,
        val hasPassphrase: Boolean,
        val kettleActive: Boolean,
        /** The kettle link (over whatever path) is connected. */
        val linkConnected: Boolean,
        /** The link has failed at least once (Offline) on the current path. */
        val linkFailed: Boolean,
        /** Phone's current Wi-Fi SSID if known (needs the location permission). */
        val phoneSsid: String?,
        /** Phone's main Wi-Fi is on the kettle's subnet: true / false / unknown. */
        val sameSubnet: Boolean?,
        /** Kettle id a local-only request is currently held for (requesting or connected), else null. */
        val requestedFor: String?,
        /** The last request for this kettle ended unavailable/timed out; wait for "Try again". */
        val failedFor: String?,
        /** The SSID / passphrase fingerprint the held (or failed) request was made with. */
        val heldSsid: String? = null,
        val heldPassKey: Int? = null,
        val passKey: Int? = null,
        val failedSsid: String? = null,
    )

    fun decide(i: Input): Action {
        val wanted = i.foreground && i.sdkSupportsSpecifier && i.selectedId != null && i.autoJoin &&
            i.kettleSsid.isNotBlank() && i.hasPassphrase && i.kettleActive
        // Hold a request only while it is wanted, and only for the kettle on screen.
        if (i.requestedFor != null) {
            if (!wanted || i.requestedFor != i.selectedId) return Action.Release
            // Settings changed the Wi-Fi details: drop the old request and ask again with the new ones.
            val changed = (i.heldSsid != null && i.heldSsid != i.kettleSsid) || (i.heldPassKey != null && i.heldPassKey != i.passKey)
            return if (changed) Action.Rerequest else Action.None
        }
        if (!wanted) return Action.None
        // A failure only blocks retries for the SSID that failed (a new SSID is tried at once).
        if (i.failedFor == i.selectedId && (i.failedSsid == null || i.failedSsid == i.kettleSsid)) return Action.None
        if (i.linkConnected) return Action.None // reachable over the current network
        if (i.phoneSsid != null && i.phoneSsid == i.kettleSsid) return Action.None // already on it
        if (i.sameSubnet == true) return Action.None // on the kettle's LAN: the kettle is just off/asleep
        // Phone is on another network (subnet differs), or the main path has already failed.
        return if (i.sameSubnet == false || i.linkFailed) Action.Request else Action.None
    }
}
