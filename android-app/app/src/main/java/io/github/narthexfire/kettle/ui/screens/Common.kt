package io.github.narthexfire.kettle.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.narthexfire.kettle.ui.components.KIcons
import io.github.narthexfire.kettle.ui.theme.K
import io.github.narthexfire.kettle.ui.theme.LocalKettleType

/** App bar with a back arrow and a 28 sp title, then a scrolling column of zone cards. */
@Composable
fun SubScreen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // Every overflow screen uses explicit sizes around a 16 sp body (Kettle details has its own set).
    val details = LocalKettleType.current === io.github.narthexfire.kettle.ui.theme.KTypeCompact
    androidx.compose.runtime.CompositionLocalProvider(
        LocalKettleType provides if (details) io.github.narthexfire.kettle.ui.theme.KTypeDetails else io.github.narthexfire.kettle.ui.theme.KTypeOverflow,
    ) { SubScreenBody(title, onBack, content) }
}

@Composable
private fun SubScreenBody(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = K.c
    Column(
        Modifier
            .fillMaxSize()
            .background(c.surface)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) {
                Icon(KIcons.back, "Back", tint = c.ink, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text(
                title, style = K.t.appTitle, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        }
        content()
    }
}

@Composable
fun Para(text: String) = Text(text, style = K.t.secondary, color = K.c.ink)

@Composable
fun Bullet(text: String) {
    Row {
        Text("•", style = K.t.secondary, color = K.c.ink2, modifier = Modifier.width(22.dp))
        Text(text, style = K.t.secondary, color = K.c.ink, modifier = Modifier.weight(1f))
    }
}

/**
 * Neutral text title (no maker logo or trademark artwork is shipped). [height] sets the line height.
 */
@Composable
fun AppTitle(height: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(
        "Kettle",
        style = K.t.title.copy(
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            fontSize = androidx.compose.ui.unit.TextUnit((height.value * 0.75f), androidx.compose.ui.unit.TextUnitType.Sp),
        ),
        color = K.c.ink,
        modifier = modifier.semantics { heading() },
    )
}
