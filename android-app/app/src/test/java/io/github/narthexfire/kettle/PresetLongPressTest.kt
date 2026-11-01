package io.github.narthexfire.kettle

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import io.github.narthexfire.kettle.data.KettleConfig
import io.github.narthexfire.kettle.data.SettingsRepository
import io.github.narthexfire.kettle.net.LinkInfo
import io.github.narthexfire.kettle.net.LinkPhase
import io.github.narthexfire.kettle.protocol.KettleState
import io.github.narthexfire.kettle.protocol.KettleStatus
import io.github.narthexfire.kettle.ui.Choice
import io.github.narthexfire.kettle.ui.KettleView
import io.github.narthexfire.kettle.ui.UiState
import io.github.narthexfire.kettle.ui.screens.MainActions
import io.github.narthexfire.kettle.ui.screens.MainScreen
import io.github.narthexfire.kettle.ui.theme.KettleTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression test for a crash: long-pressing a preset opened the edit dialog, whose
 * OutlinedTextField label lerps between our text style (letter spacing in em) and Material's (sp),
 * throwing "Cannot perform operation for Em and Sp". One long-press per case, for each of the 6
 * tiles, with fresh defaults and with migrated 4-slot data.
 *
 * Frames are driven with the test clock instead of waiting for idle: under Robolectric a text
 * field inside a dialog window never reports idle (a test-environment limitation), but composition
 * exceptions still surface from advanceTimeBy, which is what reproduced the crash.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h1900dp-xxhdpi")
class PresetLongPressTest(private val data: String, private val slot: Int) {
    @get:Rule val compose = createComposeRule()

    companion object {
        private const val OLDER_DATA = """[{"id":"kitchen","name":"Kitchen-Kettle","ip":"192.168.1.100","port":6002,"imei":"GD0-00000-00ab",
            "defaultBoilPoint":95,"presets":[{"name":"Sencha","temp":72},{"name":"White tea","temp":80},{"name":"Oolong","temp":85},{"name":"Coffee","temp":88}]}]"""

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} slot {1}")
        fun cases(): List<Array<Any>> = listOf("fresh", "migrated").flatMap { d -> (0 until 6).map { arrayOf<Any>(d, it) } }
    }

    private fun cfg(): KettleConfig = if (data == "fresh") KettleConfig.DEFAULTS[0] else SettingsRepository.decode(OLDER_DATA)[0]

    @Test fun longPressOpensEditDialogWithoutCrashing() {
        val cfg = cfg()
        val state = UiState(
            kettles = listOf(KettleView(cfg, LinkInfo(phase = LinkPhase.Connected,
                status = KettleStatus(KettleState.Standby, 22, 100, 500, 0, "C800020000166401F40000"), lastStatusAt = 1))),
            selectedId = cfg.id, choice = Choice(100, 0, null), now = 2,
        )
        compose.setContent { KettleTheme(dark = false) { MainScreen(state, true, MainActions()) } }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(300)
        compose.onAllNodesWithText(cfg.presets[slot].name)[0].performScrollTo().performTouchInput { longClick() }
        // Compose the dialog and run its label animation: this is where the crash was thrown.
        repeat(5) { compose.mainClock.advanceTimeBy(200) }
    }
}
