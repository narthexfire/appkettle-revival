package io.github.narthexfire.kettle.data

import io.github.narthexfire.kettle.protocol.KettleState

/**
 * When keep warm may be sent: the kettle accepts 0x4A only when the water is >= 60 C, the kettle is
 * not empty and it is NOT in Standby (in Standby it answers ack 00). The original app hid its
 * keep-warm button in those cases, and this matches the behavior observed on a real kettle.
 *
 * What tapping a keep-warm chip (Off / 5 / 10 / 15 / 20 / 30) does, by the kettle's live state:
 * 0x4A with the minutes while the water is at least 60 C, also while boiling. Keep warm Off during
 * Keep warm uses OFF 0x3A (proven to cancel it).
 */
sealed interface KeepWarmAction {
    /** OFF 0x3A: cancels keep warm (only while the kettle is in Keep warm). */
    data object SendOff : KeepWarmAction
    /** 0x4A setKeepWarm(minutes). */
    data class SendKeepWarm(val minutes: Int) : KeepWarmAction
    /**
     * Keep warm Off while heating below 60 C. The original never sends 0x4A below 60, so the
     * Off is queued: 0x4A(0) once the water reaches 60 during this boil (see [resolveQueuedOff]).
     */
    data object QueueOff : KeepWarmAction
    /** Nothing sent now; the choice is used by the next Boil. [note] explains why, or null. */
    data class NextBoil(val note: String?) : KeepWarmAction

    companion object {
        const val STANDBY_HINT = "Keep warm starts after boiling \u2014 choose a time, then press Boil"
        const val REFUSED = "The kettle didn't accept keep warm. It only starts keep warm right after boiling (or while heating); choose a time, then press Boil."

        /** What to do with a queued Off as new status arrives: null = keep waiting. */
        fun resolveQueuedOff(state: KettleState?, waterTempC: Int?): KeepWarmAction? = when {
            state == KettleState.Heating && waterTempC != null && waterTempC >= TempLogic.KEEP_WARM_MIN -> SendKeepWarm(0)
            state == KettleState.Heating -> null
            state == KettleState.KeepWarm -> SendOff // the boil ended and keep warm started anyway: stop it
            else -> NextBoil(null) // the boil ended without keep warm: nothing left to do
        }

        fun decide(state: KettleState?, waterTempC: Int?, minutes: Int): KeepWarmAction {
            val hot = waterTempC != null && waterTempC >= TempLogic.KEEP_WARM_MIN
            return when (state) {
                KettleState.KeepWarm ->
                    if (minutes == 0) SendOff else SendKeepWarm(minutes) // restart with the new duration
                // Standby: only choose the keep-warm for the next Boil (the original hides the button here).
                KettleState.Standby -> NextBoil(if (minutes == 0) null else STANDBY_HINT)
                KettleState.Ready -> when {
                    minutes == 0 -> NextBoil(null)
                    hot -> SendKeepWarm(minutes)
                    else -> NextBoil("Keep warm needs 60° or more")
                }
                KettleState.Heating -> when {
                    // The original app sends 0x4A(0) to cancel (not OFF, which would stop the boil).
                    minutes == 0 -> if (hot) SendKeepWarm(0) else QueueOff
                    hot -> SendKeepWarm(minutes)
                    else -> NextBoil("Applies when the water reaches 60°: press Boil again then, or it is used next time")
                }
                else -> NextBoil(null) // off base, unknown, no status
            }
        }
    }
}
