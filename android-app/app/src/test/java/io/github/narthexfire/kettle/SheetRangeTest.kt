package io.github.narthexfire.kettle

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Temperature & keep warm SHEET with stored data from an older version: a measured boil point of
 * 95, the old default/override fields 95, lastTarget 100, presets, Wi-Fi fields and migration flags.
 * Drives the real MainScreen with the sheet open and a stateful target, as the app does.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h1900dp-xxhdpi")
class SheetRangeTest {
    @get:Rule val compose = createComposeRule()

    private val stored = """[{"id":"kitchen","name":"Kitchen-Kettle","ip":"192.168.1.100","port":6002,"imei":"GD0-00000-00ab",
        "mac":"00:26:29:00:00:01","wifiSsid":"OldKettleNet","device":"AppKettle Wi-Fi smart kettle",
        "defaultBoilPoint":95,"boilPointOverride":95,"boilHistory":[95,95,95,95,95],"enabled":true,
        "lastTarget":100,"lastKeepWarm":0,"presetOrderMigrated":true,"wifiPass":"oldpass123","autoJoinWifi":true,"cupMl":250,
        "presets":[{"name":"Coffee","temp":88,"keepWarm":0,"icon":"cup"},{"name":"Green Tea","temp":75,"keepWarm":0,"icon":"leaf"},
        {"name":"White Tea","temp":80,"keepWarm":0,"icon":"flower"},{"name":"Oolong","temp":85,"keepWarm":0,"icon":"pot"},
        {"name":"Hot Chocolate","temp":70,"keepWarm":0,"icon":"mug"},{"name":"Warm","temp":60,"keepWarm":0,"icon":"flame"}]}]"""
    private val cfg: KettleConfig = SettingsRepository.decode(stored)[0]

    private fun openSheet(start: Int): () -> Int {
        var target by mutableStateOf(start)
        compose.setContent {
            KettleTheme(false) {
                MainScreen(
                    UiState(
                        kettles = listOf(KettleView(cfg, LinkInfo(phase = LinkPhase.Connected,
                            status = KettleStatus(KettleState.Standby, 52, 95, 912, 0, "C800020000345D03900000"), lastStatusAt = 1))),
                        selectedId = cfg.id, choice = Choice(target, 0, null), now = 2,
                    ),
                    reduceMotion = true, actions = MainActions(setTarget = { target = it }), sheetOpenInitially = true,
                )
            }
        }
        compose.waitForIdle()
        return { target }
    }

    /** The sheet's controls are composed after the main screen's, so take the last match. */
    private fun sheetNode(desc: String): SemanticsNodeInteraction =
        compose.onAllNodesWithContentDescription(desc).let { it[it.fetchSemanticsNodes().size - 1] }

    @Test fun plusEnabledAt95AndReaches100() {
        val target = openSheet(95)
        sheetNode("1 degree hotter").assertIsEnabled()
        repeat(9) { sheetNode("1 degree hotter").performClick(); compose.waitForIdle() }
        assertEquals(100, target())
        sheetNode("1 degree hotter").assertIsNotEnabled() // only at the hard maximum
    }

    @Test fun sliderRangeEndsAt100AndDragReaches100() {
        val target = openSheet(95)
        val range = sheetNode("Target temperature").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(40f, range.range.start); assertEquals(100f, range.range.endInclusive)
        sheetNode("Target temperature").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.waitForIdle()
        assertEquals(100, target())
    }

    /**
     * At the top the value must read 100° (not just "Boil"), even though the hint mentions the measured
     * boil point (≈95°), which could otherwise read as a 95° maximum. The slider must end at 100°.
     */
    @Test fun atTheTopTheValueReads100() {
        openSheet(100)
        val valueNodes = compose.onAllNodesWithText("100° · Boil").fetchSemanticsNodes().size
        assertTrue("the temperature value at the top should read \"100° · Boil\"", valueNodes >= 2) // main card + sheet
        assertTrue("the slider end label should read 100°", compose.onAllNodesWithText("100°").fetchSemanticsNodes().isNotEmpty())
    }
}
