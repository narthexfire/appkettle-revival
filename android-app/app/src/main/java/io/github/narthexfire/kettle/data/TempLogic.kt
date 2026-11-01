package io.github.narthexfire.kettle.data

import kotlin.math.roundToInt

/**
 * Rules for the temperature control: any target
 * from 40 to 100 C can be chosen and is sent as-is; the kettle cuts out by itself at the real boil.
 * Only exactly 100 is "Boil". The learned boil point is information only (see KettleConfig) and the
 * `boilPoint` parameters below are kept for call-site compatibility but no longer limit anything.
 */
object TempLogic {
    const val MIN = 40
    const val BOIL = 100

    @Suppress("UNUSED_PARAMETER")
    fun isBoil(t: Int, boilPoint: Int = BOIL) = t >= BOIL

    /** The target sent to the kettle: the chosen value, 40-100. */
    @Suppress("UNUSED_PARAMETER")
    fun sendTarget(t: Int, boilPoint: Int = BOIL): Int = t.coerceIn(MIN, BOIL)

    @Suppress("UNUSED_PARAMETER")
    fun plus(t: Int, boilPoint: Int = BOIL): Int = (t + 1).coerceIn(MIN, BOIL)

    @Suppress("UNUSED_PARAMETER")
    fun minus(t: Int, boilPoint: Int = BOIL): Int = (t - 1).coerceIn(MIN, BOIL)

    fun canPlus(t: Int) = t < BOIL
    fun canMinus(t: Int) = t > MIN

    /** Slider: 0..1 maps linearly to 40..100. */
    @Suppress("UNUSED_PARAMETER")
    fun fromSlider(p: Float, boilPoint: Int = BOIL): Int = (MIN + p.coerceIn(0f, 1f) * (BOIL - MIN)).roundToInt()

    @Suppress("UNUSED_PARAMETER")
    fun toSlider(t: Int, boilPoint: Int = BOIL): Float = ((t - MIN).toFloat() / (BOIL - MIN)).coerceIn(0f, 1f)

    /** AppKettle: keep warm only works at 60 C or above. */
    const val KEEP_WARM_MIN = 60

    fun keepWarmAllowed(target: Int) = target >= KEEP_WARM_MIN

    /** The keep-warm minutes actually usable with [target] (0 below 60 C). */
    fun effectiveKeepWarm(target: Int, keepWarm: Int) = if (keepWarmAllowed(target)) keepWarm else 0

    /** "Boil" only at exactly 100; otherwise the number. */
    @Suppress("UNUSED_PARAMETER")
    fun label(t: Int, boilPoint: Int = BOIL): String = if (t >= BOIL) "Boil" else "$t°"

    /**
     * The temperature control's own value: "78°", and at the top "100° · Boil", so the maximum always
     * Reads as 100 (a bare "Boil" next to a "≈95°" hint read as a 95° limit).
     */
    fun controlLabel(t: Int): String = if (t >= BOIL) "100° · Boil" else "$t°"

    /** Hint when the target is above the measured boil point. Worded so the boil point doesn't read as a limit. */
    fun stopsAtBoilHint(t: Int, measuredBoil: Int?): String? =
        if (measuredBoil != null && t > measuredBoil) "Water boils at \u2248$measuredBoil° here; the kettle stops when it boils" else null
}

/**
 * Learns the kettle's real boiling point. When the kettle finishes a boil by itself it rewrites
 * its reported target from 100 to the temperature at which it detected the boil (for example about 95 at 1,500 m).
 */
object BoilPointLearner {
    val PLAUSIBLE = 85..100
    const val WINDOW = 5

    /**
     * Returns the boil temperature to record, or null. A sample is taken on the transition out of
     * Heating (commanded to 100) into Ready / Keep warm, when the reported target is plausible and
     * the water actually reached it (so a manual Stop at 70 is ignored).
     */
    fun sample(
        wasHeatingToBoil: Boolean,
        nowStateIsDone: Boolean,
        reportedTarget: Int,
        temperature: Int,
    ): Int? {
        if (!wasHeatingToBoil || !nowStateIsDone) return null
        if (reportedTarget !in PLAUSIBLE) return null
        if (temperature < reportedTarget - 2) return null
        return reportedTarget
    }

    fun addSample(history: List<Int>, sample: Int): List<Int> =
        (history + sample).filter { it in PLAUSIBLE }.takeLast(WINDOW)

    fun median(history: List<Int>): Int? {
        val h = history.filter { it in PLAUSIBLE }.sorted()
        if (h.isEmpty()) return null
        return if (h.size % 2 == 1) h[h.size / 2] else ((h[h.size / 2 - 1] + h[h.size / 2]) / 2.0).roundToInt()
    }

    /** Manual override wins, then the learned median, then the configured default. */
    fun effective(override: Int?, history: List<Int>, default: Int): Int =
        override?.takeIf { it in PLAUSIBLE } ?: median(history) ?: default
}
