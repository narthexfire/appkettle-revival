package io.github.narthexfire.kettle.data

import kotlin.math.roundToInt

/**
 * Water in cups. Default cup = 250 ml: AppKettle's Volume Troubleshooting page says the kettle
 * "requires a minimum water level of 250ml (1 cup)". The official app let users set their own cup
 * capacity, so it is a per-kettle setting ("Cup size (ml)") here too.
 * Shown to the nearest quarter cup with fraction glyphs: "¼ cup", "1 cup", "2½ cups"; "<¼ cup" below 1/8.
 */
object Cups {
    const val DEFAULT_ML = 250

    /**
     * Status-line text: "Empty" at 0 ml; "MAX+ (over 1.7 L)" above the 1.7 L capacity (the scale
     * reads ±30 ml, so it can report more); otherwise "2 cups · ≈ 500 ml".
     */
    fun waterText(ml: Int, cupMl: Int = DEFAULT_ML): String = when {
        ml <= 0 -> "Empty"
        ml > DeviceFacts.CAPACITY_ML -> "MAX+ (over 1.7 L)"
        else -> format(ml, cupMl) + " \u00B7 " + (if (ml >= 1000) "\u2248 %.1f L".format(ml / 1000f) else "\u2248 $ml ml")
    }
    val RANGE = 50..1000

    fun format(ml: Int, cupMl: Int = DEFAULT_ML): String {
        if (ml <= 0) return "Empty"
        val size = cupMl.coerceIn(RANGE)
        val cups = ml.toDouble() / size
        if (cups < 0.125) return "<¼ cup"
        val quarters = (cups * 4).roundToInt()
        val whole = quarters / 4
        val frac = when (quarters % 4) { 1 -> "¼"; 2 -> "½"; 3 -> "¾"; else -> "" }
        val number = (if (whole > 0) whole.toString() else "") + frac
        return if (quarters <= 4) "$number cup" else "$number cups"
    }
}
