package io.github.narthexfire.kettle.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The app's 24x24 line icons, rebuilt as ImageVectors (tinted by Icon). */
object KIcons {
    private fun icon(name: String, stroke: Float, vararg paths: String, filled: Set<Int> = emptySet()): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEachIndexed { i, d ->
            val nodes = PathParser().parsePathString(d).toNodes()
            if (i in filled) b.addPath(nodes, fill = SolidColor(Color.Black))
            else b.addPath(
                nodes, stroke = SolidColor(Color.Black), strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r},$cy a$r,$r 0 1,0 ${2 * r},0 a$r,$r 0 1,0 ${-2 * r},0"

    val gear = icon(
        "gear", 1.8f, circle(12f, 12f, 3f),
        "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z",
    )
    val chevRight = icon("chev", 2.2f, "m9 6 6 6-6 6")
    val chevDown = icon("chevD", 2.4f, "m6 9 6 6 6-6")
    val back = icon("back", 2.4f, "m15 6-6 6 6 6")
    val tick = icon("tick", 2.6f, "m5 12 5 5 9-10")
    val leaf = icon("leaf", 1.9f, "M5 19c0-8 5-13 15-14-1 10-6 15-14 15", "M5 19 14 10")
    val flower = icon(
        "flower", 1.9f, circle(12f, 12f, 2.5f),
        "M12 9.5C10 6 10.5 3.5 12 3c1.5.5 2 3 0 6.5M14.5 12c3.5-2 6-1.5 6.5 0-.5 1.5-3 2-6.5 0M12 14.5c2 3.5 1.5 6 0 6.5-1.5-.5-2-3 0-6.5M9.5 12c-3.5 2-6 1.5-6.5 0 .5-1.5 3-2 6.5 0",
    )
    val cup = icon(
        "cup", 1.9f, "M4 9h13v5a5 5 0 0 1-5 5H9a5 5 0 0 1-5-5z", "M17 11h1.5a2.5 2.5 0 0 1 0 5H17",
        "M8 3.5c-.6.8-.6 1.7 0 2.5M12 3.5c-.6.8-.6 1.7 0 2.5",
    )
    val drop = icon("drop", 1.9f, "M12 3s6 6.5 6 11a6 6 0 0 1-12 0c0-4.5 6-11 6-11z", "M9.5 15a2.5 2.5 0 0 0 2.5 2.5")
    val minus = icon("minus", 2.4f, "M6 12h12")
    val plus = icon("plus", 2.4f, "M6 12h12M12 6v12")
    val wifi = icon(
        "wifi", 2f, "M2.5 9a14 14 0 0 1 19 0M5.5 12.5a9.5 9.5 0 0 1 13 0M8.8 16a5 5 0 0 1 6.4 0",
        circle(12f, 19.2f, 1f), filled = setOf(1),
    )
    val power = icon("power", 2.4f, "M12 3v8", "M6.3 7.3a8 8 0 1 0 11.4 0")
    val stop = icon("stop", 0f, "M8.5 6h7a2.5 2.5 0 0 1 2.5 2.5v7a2.5 2.5 0 0 1-2.5 2.5h-7A2.5 2.5 0 0 1 6 15.5v-7A2.5 2.5 0 0 1 8.5 6z", filled = setOf(0))
    val gauge = icon("gauge", 2f, "M4 18a9 9 0 1 1 16 0", "m12 14 4-5")
    val therm = icon("therm", 2f, "M10 13.5V5a2 2 0 1 1 4 0v8.5a4 4 0 1 1-4 0z", "M12 10v6")
    val star = icon("star", 2f, "m12 3 2.6 5.6 6 .7-4.5 4.1 1.2 6L12 16.4 6.7 19.4l1.2-6L3.4 9.3l6-.7z")
    val flame = icon("flame", 2f, "M12 21a6 6 0 0 0 6-6c0-4-3-6-4-10-2 2-3 4-3 6-1-1-1.5-2-1.5-3C7 9.5 6 12 6 15a6 6 0 0 0 6 6z")
    val more = icon("more", 0f, circle(12f, 5f, 2f), circle(12f, 12f, 2f), circle(12f, 19f, 2f), filled = setOf(0, 1, 2))
    val info = icon("info", 2f, circle(12f, 12f, 9f), "M12 11v6", circle(12f, 7.6f, 1.1f), filled = setOf(2))
    val help = icon("help", 2f, circle(12f, 12f, 9f), "M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.7.3-1 .9-1 1.6v.6", circle(12f, 17f, 1.1f), filled = setOf(2))
    val pot = icon(
        "pot", 1.9f, "M5 11h11v3.5A5.5 5.5 0 0 1 10.5 20h0A5.5 5.5 0 0 1 5 14.5z", "M16 13l3.5-3",
        "M8 11a2.5 2.5 0 0 1 5 0", "M10.5 6.5v1.5",
    )
    val mug = icon(
        "mug", 1.9f, "M5 8h11v7a5 5 0 0 1-5 5h-1a5 5 0 0 1-5-5z", "M16 10h1.5a2.5 2.5 0 0 1 0 5H16",
        "M8.5 4.5h2v2h-2zM12 3.5h2v2h-2z",
    )
    val pulse = icon("pulse", 2f, "M3 12h4l2-6 4 12 2-6h6")

    fun preset(key: String) = when (key) {
        "leaf" -> leaf; "flower" -> flower; "cup" -> cup; "pot" -> pot; "mug" -> mug; "flame" -> flame; else -> drop
    }
}
