package io.github.narthexfire.kettle.data

import io.github.narthexfire.kettle.protocol.KettleState
import io.github.narthexfire.kettle.protocol.KettleStatus

/** Warn (don't block) when there is too little water for the kettle to start. */
object LowWater {
    /** A status older than this is not used for the warning. */
    const val FRESH_MS = 60_000L

    fun shouldWarn(status: KettleStatus?, lastStatusAt: Long, now: Long, minMl: Int = DeviceFacts.MIN_ML): Boolean {
        if (status == null || lastStatusAt <= 0 || now - lastStatusAt > FRESH_MS) return false
        if (status.state == KettleState.OffBase || status.state == KettleState.Unknown || status.state == KettleState.Unknown1) return false
        return status.waterMl < minMl
    }

    /** Inline two-tap confirm: the first Boil tap arms it for [WINDOW_MS], the second sends. */
    const val WINDOW_MS = 5_000L
    const val ARMED = "Too little water \u2014 tap Boil again to send anyway"

    enum class Tap { Send, Arm, SendAnyway }

    fun onBoilTap(lowWater: Boolean, armedAt: Long, now: Long): Tap = when {
        !lowWater -> Tap.Send
        armedAt > 0 && now - armedAt <= WINDOW_MS -> Tap.SendAnyway
        else -> Tap.Arm
    }

    const val WARNING = "Too little water. Fill above MIN (1 cup / 250 ml) \u2014 the kettle won't start below it."
}
