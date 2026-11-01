package io.github.narthexfire.kettle.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.narthexfire.kettle.data.KEEP_WARM_OPTIONS
import io.github.narthexfire.kettle.data.Preset
import io.github.narthexfire.kettle.data.PresetLogic
import androidx.compose.foundation.combinedClickable
import io.github.narthexfire.kettle.data.TempLogic
import io.github.narthexfire.kettle.ui.theme.K
import io.github.narthexfire.kettle.ui.theme.Zone
import io.github.narthexfire.kettle.ui.theme.tempColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* ---------- zone card and heading ---------- */

@Composable
fun ZoneCard(
    zone: Zone, modifier: Modifier = Modifier,
    padH: Dp = 20.dp, padV: Dp = 20.dp, gap: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    val c = K.c
    Column(
        modifier
            .fillMaxWidth()
            .then(if (!c.dark) Modifier.shadow(2.dp, shape, ambientColor = Color(0x14281E14), spotColor = Color(0x1F281E14)) else Modifier)
            .clip(shape)
            .background(zone.bg)
            .border(1.dp, zone.line, shape)
            .padding(horizontal = padH, vertical = padV),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

@Composable
fun ZoneHeading(icon: ImageVector, label: String, zone: Zone, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = zone.fg, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(label.uppercase(), style = K.t.overline, color = zone.fg, modifier = Modifier.weight(1f))
        trailing()
    }
}

/* ---------- status dot ---------- */

@Composable
fun StatusDot(color: Color, pulse: Boolean, reduceMotion: Boolean, size: Dp = 14.dp) {
    val inf = rememberInfiniteTransition(label = "dot")
    val p by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p")
    val k = if (pulse && !reduceMotion) p else 0f
    Box(
        Modifier.size(size).graphicsLayer {
            alpha = 1f - .55f * k; scaleX = 1f - .2f * k; scaleY = 1f - .2f * k
        }.background(color, CircleShape),
    )
}

/* ---------- fine temperature control (spec 3a) ---------- */

@Composable
private fun RepeatButton(icon: ImageVector, desc: String, enabled: Boolean, onStep: () -> Unit) {
    val c = K.c
    val z = c.temp
    var pressed by remember { mutableStateOf(false) }
    val step by rememberUpdatedState(onStep)
    val en by rememberUpdatedState(enabled)
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (pressed && enabled) z.fg else c.tile)
            .border(2.dp, if (enabled) z.fg else z.line, CircleShape)
            .semantics {
                role = Role.Button; contentDescription = desc
                if (!enabled) disabled()
                onClick { if (en) step(); en }
            }
            .pointerInput(Unit) {
                // A tap steps on RELEASE; holding repeats only after the long-press timeout;
                // a drag (scroll, pull-to-refresh) that starts here cancels without changing anything.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!en) return@awaitEachGesture
                    pressed = true
                    var repeated = false
                    var canceled = false
                    val job = scope.launch {
                        delay(viewConfiguration.longPressTimeoutMillis)
                        repeated = true
                        while (en) { step(); delay(110) }
                    }
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: ev.changes.first()
                        if (!ch.pressed) break
                        if (ch.isConsumed || (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            canceled = true; break
                        }
                    }
                    job.cancel()
                    pressed = false
                    if (!repeated && !canceled) step()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (pressed && enabled) c.card else if (enabled) z.fg else z.line, modifier = Modifier.size(28.dp))
    }
}

@Composable
fun TempControl(
    value: Int, boilPoint: Int, onChange: (Int) -> Unit, showEnds: Boolean, modifier: Modifier = Modifier,
    measuredBoil: Int? = null,
) {
    val c = K.c
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RepeatButton(KIcons.minus, "1 degree cooler", TempLogic.canMinus(value)) { onChange(TempLogic.minus(value, boilPoint)) }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                TempLogic.controlLabel(value), style = K.t.value, color = c.ink, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.height(2.dp))
            TempSlider(value, boilPoint, onChange)
            TempLogic.stopsAtBoilHint(value, measuredBoil)?.let { Text(it, style = K.t.secondary, color = c.ink2, textAlign = TextAlign.Center) }
            if (showEnds) {
                Row(Modifier.fillMaxWidth()) {
                    Text("40°", style = K.t.secondary, color = c.ink2, modifier = Modifier.weight(1f))
                    Text("100°", style = K.t.secondary, color = c.ink2)
                }
            }
        }
        RepeatButton(KIcons.plus, "1 degree hotter", TempLogic.canPlus(value)) { onChange(TempLogic.plus(value, boilPoint)) }
    }
}

@Composable
private fun TempSlider(value: Int, boilPoint: Int, onChange: (Int) -> Unit) {
    val c = K.c
    val change by rememberUpdatedState(onChange)
    val boilPt by rememberUpdatedState(boilPoint)
    val frac = TempLogic.toSlider(value, boilPoint)
    val ring = tempColor(value, boilPoint)
    val thumb = 24.dp
    val thumbPx = with(LocalDensity.current) { thumb.toPx() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp) // >= 48 dp touch target; the drawn track stays 8 dp
            .semantics {
                contentDescription = "Target temperature"
                stateDescription = TempLogic.controlLabel(value)
                // The range is always 40..100 (whatever boil point has been measured).
                progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(
                    value.toFloat(), TempLogic.MIN.toFloat()..TempLogic.BOIL.toFloat(), steps = TempLogic.BOIL - TempLogic.MIN - 1,
                )
                setProgress { p ->
                    // Accept either a fraction (0..1) or a temperature (40..100).
                    change(if (p <= 1f) TempLogic.fromSlider(p, boilPt) else p.roundToIntSafe().coerceIn(TempLogic.MIN, TempLogic.BOIL)); true
                }
            }
            .pointerInput(Unit) {
                fun at(x: Float) = (x - thumbPx / 2) / (size.width - thumbPx)
                detectTapGestures { change(TempLogic.fromSlider(at(it.x), boilPt)) }
            }
            .pointerInput(Unit) {
                fun at(x: Float) = (x - thumbPx / 2) / (size.width - thumbPx)
                detectDragGestures(onDragStart = { change(TempLogic.fromSlider(at(it.x), boilPt)) }) { ch, _ ->
                    change(TempLogic.fromSlider(at(ch.position.x), boilPt))
                }
            },
    ) {
        Canvas(Modifier.fillMaxWidth().height(48.dp)) {
            val h = 8.dp.toPx()
            val cy = size.height / 2
            drawRoundRect(
                Brush.horizontalGradient(listOf(c.tempA, c.tempB)),
                topLeft = Offset(thumbPx / 2 - h / 2, cy - h / 2),
                size = androidx.compose.ui.geometry.Size(size.width - thumbPx + h, h),
                cornerRadius = CornerRadius(h / 2),
            )
            val x = thumbPx / 2 + frac * (size.width - thumbPx)
            drawCircle(Color.White, thumbPx / 2, Offset(x, cy))
            drawCircle(ring, thumbPx / 2 - 2.5.dp.toPx(), Offset(x, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(5.dp.toPx()))
        }
    }
}

/* ---------- chips ---------- */

@Composable
fun Chip(
    text: String, selected: Boolean, fill: Color, onFill: Color, border: Color, modifier: Modifier = Modifier,
    unselectedBg: Color = K.c.tile, enabled: Boolean = true, onClick: () -> Unit,
) {
    val c = K.c
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (selected) fill else unselectedBg)
            .border(1.dp, if (selected) fill else border, shape)
            .semantics { this.selected = selected; role = Role.Button }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        FitText(text, K.t.chip.copy(textAlign = TextAlign.Center), if (selected) onFill else c.ink)
    }
}

@Composable
fun KeepWarmChips(selected: Int, onSelect: (Int) -> Unit, enabled: Boolean = true) {
    val z = K.c.warm
    // Three per row at large font sizes so "Off" never breaks mid-word.
    val perRow = if (LocalDensity.current.fontScale > 1.3f) 3 else 6
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        KEEP_WARM_OPTIONS.chunked(perRow).forEach { row ->
            Row(
                Modifier.fillMaxWidth().graphicsLayer { alpha = if (enabled) 1f else 0.45f },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { m ->
                    Chip(
                        if (m == 0) "Off" else "$m", (if (enabled) selected else 0) == m, z.fill, z.onFill, z.line,
                        Modifier.weight(1f).semantics {
                            contentDescription = if (m == 0) "Keep warm off" else "Keep warm $m minutes"
                            if (!enabled) disabled()
                        },
                        enabled = enabled,
                    ) { onSelect(m) }
                }
            }
        }
        if (!enabled) Text("Keep warm needs 60\u00B0 or more", style = K.t.secondary, color = K.c.ink2)
    }
}

@Composable
fun TempChips(value: Int, boilPoint: Int, onSelect: (Int) -> Unit) {
    val c = K.c
    val cols = if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.5f) 4 else 5
    val temps = (40..95 step 5).toList()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        temps.chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { t ->
                    Chip("$t°", value == t, tempColor(t, boilPoint), Color.White, c.temp.line, Modifier.weight(1f)) { onSelect(t) }
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Chip(
            "Boil (100°)", TempLogic.isBoil(value),
            c.accent, c.onAccent, c.temp.line, Modifier.fillMaxWidth(),
        ) { onSelect(TempLogic.BOIL) }
    }
}

/* ---------- presets ---------- */

@Composable
fun PresetGrid(
    presets: List<Preset>, selected: Int?, boilPoint: Int, onSelect: (Int) -> Unit,
    compact: Boolean = false, onLongPress: ((Int) -> Unit)? = null,
) {
    val oneCol = LocalDensity.current.fontScale > 1.5f
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.indices.chunked(if (oneCol) 1 else 2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { i ->
                    PresetTile(i, presets[i], selected == i, boilPoint, compact, Modifier.weight(1f), { onSelect(i) },
                        onLongPress?.let { f -> { f(i) } })
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PresetTile(
    i: Int, p: Preset, selected: Boolean, boilPoint: Int, compact: Boolean, modifier: Modifier,
    onClick: () -> Unit, onLongClick: (() -> Unit)?,
) {
    val c = K.c
    val z = c.preset
    val shape = RoundedCornerShape(20.dp)
    // Tiles are 60 % of their original height: main grid 108 -> 65 dp, sheet 72 -> 48 dp
    // (60 % would be 43 dp, below the 48 dp touch minimum). Icon sits left of name and temperature.
    Box(
        modifier
            .heightIn(min = if (compact) 48.dp else 65.dp)
            .clip(shape)
            .background(if (selected) z.soft else c.tile)
            .border(if (selected) 2.dp else 1.dp, if (selected) z.fg else z.line, shape)
            .semantics(mergeDescendants = true) { this.selected = selected; role = Role.Button }
            .combinedClickable(
                onClick = onClick, onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) "Edit preset" else null,
            )
            .padding(horizontal = 16.dp, vertical = if (compact) 6.dp else 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(KIcons.preset(p.icon), null, tint = z.fg, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                FitText(p.name, K.t.presetName, c.ink)
                FitText(PresetLogic.label(p, boilPoint), K.t.secondary, if (selected) z.fg else c.ink2)
            }
        }
    }
}

/** Name plus a temperature with -/+ (1 degree steps, 40 to the boil point). Used by Settings and the long-press dialog. */
@Composable
fun PresetEditorRow(p: Preset, boilPoint: Int, onChange: (Preset) -> Unit) {
    val c = K.c
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.OutlinedTextField(
            value = p.name, onValueChange = { onChange(p.copy(name = it.take(PresetLogic.MAX_NAME))) }, singleLine = true,
            label = { Text("Name", style = K.t.secondary) }, textStyle = K.t.title.copy(color = c.ink),
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.preset.fg, unfocusedBorderColor = c.preset.line, focusedLabelColor = c.preset.fg,
                unfocusedLabelColor = c.ink2, cursorColor = c.preset.fg, focusedContainerColor = c.tile, unfocusedContainerColor = c.tile,
            ),
        )
        val t = PresetLogic.clamp(p.temp, boilPoint)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(KIcons.minus, "1 degree cooler", t > TempLogic.MIN) {
                val nt = PresetLogic.minus(t, boilPoint)
                onChange(p.copy(temp = nt, keepWarm = TempLogic.effectiveKeepWarm(nt, p.keepWarm)))
            }
            Text(
                if (TempLogic.isBoil(t, boilPoint)) "$t° (Boil)" else "$t°", style = K.t.value, color = c.ink,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            StepButton(KIcons.plus, "1 degree hotter", TempLogic.canPlus(t)) { onChange(p.copy(temp = PresetLogic.plus(t, boilPoint))) }
        }
        Text("Keep warm (minutes)", style = K.t.secondary, color = c.ink2)
        KeepWarmChips(p.keepWarm, { onChange(p.copy(keepWarm = it)) }, enabled = TempLogic.keepWarmAllowed(t))
    }
}

@Composable
private fun StepButton(icon: ImageVector, desc: String, enabled: Boolean, onStep: () -> Unit) =
    RepeatButton(icon, desc, enabled, onStep)

/* ---------- buttons ---------- */

@Composable
fun PrimaryButton(
    text: String, modifier: Modifier = Modifier, icon: ImageVector? = KIcons.power, enabled: Boolean = true,
    progress: Float? = null, dimWhenDisabled: Boolean = true, onClick: () -> Unit,
) {
    val c = K.c
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(if (enabled || !dimWhenDisabled) c.accent else c.accent.copy(alpha = .45f))
            .drawWithContent {
                if (progress != null) {
                    drawRect(Color.Black.copy(alpha = .22f), size = size.copy(width = size.width * progress.coerceIn(0f, 1f)))
                }
                drawContent()
            }
            .semantics { role = Role.Button }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = c.onAccent, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(10.dp))
            }
            Text(text, style = K.t.button, color = c.onAccent, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun SecondaryButton(
    text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit,
) {
    val c = K.c
    val shape = RoundedCornerShape(50)
    val fg = if (enabled) c.ink else c.ink3
    Box(
        modifier
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(c.card)
            .border(1.5.dp, if (enabled) c.ink3 else c.line, shape)
            .semantics { role = Role.Button }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(8.dp))
            }
            Text(text, style = K.t.button, color = fg, textAlign = TextAlign.Center)
        }
    }
}

/** Two-line label/value row used on Settings, About and Diagnostics. */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, note: String? = null, valueColor: Color = K.c.ink, mono: Boolean = false) {
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(label, style = K.t.secondary, color = K.c.ink2)
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(value, style = if (mono) K.t.mono.copy(color = valueColor) else K.t.title.copy(color = valueColor))
        }
        if (note != null) Text(note, style = K.t.secondary, color = K.c.ink2)
    }
}

private fun Float.roundToIntSafe(): Int = kotlin.math.round(this).toInt()

/** True when a line of [text] ends inside a word (the next line starts mid-word). */
fun breaksMidWord(text: String, lineEnds: List<Int>): Boolean =
    lineEnds.dropLast(1).any { end ->
        end in 1 until text.length && !text[end - 1].isWhitespace() && !text[end].isWhitespace()
    }

/**
 * Text that may wrap only at spaces. If a word would be split (e.g. "Chocolat/e"), the font
 * shrinks in 5 % steps down to [minScale] (85 %); it never breaks a word while a smaller size fits.
 */
@Composable
fun FitText(text: String, style: androidx.compose.ui.text.TextStyle, color: Color, minScale: Float = 0.85f) {
    var scale by remember(text, style) { mutableStateOf(1f) }
    Text(
        text, color = color,
        style = style.copy(fontSize = style.fontSize * scale, lineHeight = style.lineHeight * scale),
        onTextLayout = { r ->
            val ends = (0 until r.lineCount).map { r.getLineEnd(it, visibleEnd = false) }
            if (breaksMidWord(text, ends) && scale > minScale + 0.001f) scale = (scale - 0.05f).coerceAtLeast(minScale)
        },
    )
}
