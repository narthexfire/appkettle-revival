package io.github.narthexfire.kettle

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import io.github.narthexfire.kettle.data.KettleConfig
import io.github.narthexfire.kettle.data.Preset
import io.github.narthexfire.kettle.data.PresetLogic
import io.github.narthexfire.kettle.data.SettingsRepository
import io.github.narthexfire.kettle.data.TempLogic
import io.github.narthexfire.kettle.ui.components.PresetEditorRow
import io.github.narthexfire.kettle.ui.components.TempChips
import io.github.narthexfire.kettle.ui.components.TempControl
import io.github.narthexfire.kettle.ui.theme.KettleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Stored data from an older version has a learned boil point below 100 (here 95, plus the old
 * default/override fields), so KettleConfig.boilPoint is 95. The target must still reach 100.
 * Fresh installs have 100, so this needs its own test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h1900dp-xxhdpi")
class UpgradedRangeTest {
    @get:Rule val compose = createComposeRule()

    /** Kitchen-Kettle as stored by an older version (fields as that version wrote them). */
    private val olderData = """[{"id":"kitchen","name":"Kitchen-Kettle","ip":"192.168.1.100","port":6002,"imei":"GD0-00000-00ab",
        "mac":"00:26:29:00:00:01","wifiSsid":"OldKettleNet","device":"AppKettle Wi-Fi smart kettle",
        "defaultBoilPoint":95,"boilPointOverride":95,"boilHistory":[95,95,95],"enabled":true,"lastTarget":95,"lastKeepWarm":0,
        "presets":[{"name":"Green Tea","temp":75,"keepWarm":0},{"name":"White Tea","temp":80,"keepWarm":0},
        {"name":"Oolong","temp":85,"keepWarm":0},{"name":"Coffee","temp":88,"keepWarm":0},
        {"name":"Hot Chocolate","temp":70,"keepWarm":0},{"name":"Warm","temp":60,"keepWarm":0}]}]"""

    private val cfg: KettleConfig = SettingsRepository.decode(olderData)[0]

    @Test fun upgradedDataHasMeasuredBoil95ButNoLimit() {
        assertEquals(95, cfg.boilPoint) // used for colors only
        assertEquals(100, TempLogic.sendTarget(100, cfg.boilPoint))
        assertEquals(95, PresetLogic.clamp(95, cfg.boilPoint))
        assertEquals(100, PresetLogic.saveTo(cfg.presets, 0, "Coffee", 100, 0, cfg.boilPoint)[0].temp)
        // A preset stored at 95 is chosen as 95.
        val p = cfg.copy(presets = cfg.presets.toMutableList().also { it[0] = Preset("Coffee", 95, icon = "cup") })
        assertEquals(95, PresetLogic.clamp(p.presets[0].temp, p.boilPoint))
    }

    @Test fun mainControlPlusReaches100() {
        var target by mutableStateOf(89)
        compose.setContent { KettleTheme(false) { TempControl(target, cfg.boilPoint, { target = it }, showEnds = true, measuredBoil = cfg.measuredBoilPoint) } }
        repeat(11) { compose.onNodeWithContentDescription("1 degree hotter").performClick(); compose.waitForIdle() }
        assertEquals(100, target)
    }

    @Test fun mainControlSliderReaches100() {
        var target by mutableStateOf(60)
        compose.setContent { KettleTheme(false) { TempControl(target, cfg.boilPoint, { target = it }, showEnds = true) } }
        compose.onNodeWithContentDescription("Target temperature").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.waitForIdle()
        assertEquals(100, target)
        compose.onNodeWithContentDescription("Target temperature").performSemanticsAction(SemanticsActions.SetProgress) { it(55f / 60f) }
        compose.waitForIdle()
        assertEquals(95, target)
    }

    /** The long-press edit dialog and the Settings presets editor share this row. */
    @Test fun presetEditorPlusReaches100() {
        var p by mutableStateOf(Preset("Coffee", 88, icon = "cup"))
        compose.setContent { KettleTheme(false) { PresetEditorRow(p, cfg.boilPoint) { p = it } } }
        repeat(12) { compose.onAllNodesWithContentDescription("1 degree hotter")[0].performClick(); compose.waitForIdle() }
        assertEquals(100, p.temp)
    }

    @Test fun sheetChipsReach95AndBoil() {
        var target by mutableStateOf(75)
        compose.setContent { KettleTheme(false) { TempChips(target, cfg.boilPoint) { target = it } } }
        compose.onNodeWithText("95°").performClick(); compose.waitForIdle()
        assertEquals(95, target)
        compose.onNodeWithText("Boil (100°)").performClick(); compose.waitForIdle()
        assertEquals(100, target)
    }
}
