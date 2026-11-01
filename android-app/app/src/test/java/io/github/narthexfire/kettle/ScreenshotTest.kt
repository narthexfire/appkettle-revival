package io.github.narthexfire.kettle

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import io.github.narthexfire.kettle.data.KettleConfig
import io.github.narthexfire.kettle.net.LinkInfo
import io.github.narthexfire.kettle.net.LinkPhase
import io.github.narthexfire.kettle.net.PhoneNet
import io.github.narthexfire.kettle.protocol.KettleState
import io.github.narthexfire.kettle.protocol.KettleStatus
import io.github.narthexfire.kettle.ui.Choice
import io.github.narthexfire.kettle.ui.KettleView
import io.github.narthexfire.kettle.ui.UiState
import io.github.narthexfire.kettle.ui.screens.DiagnosticsScreen
import io.github.narthexfire.kettle.ui.screens.HelpScreen
import io.github.narthexfire.kettle.ui.screens.MainActions
import io.github.narthexfire.kettle.ui.screens.MainScreen
import io.github.narthexfire.kettle.ui.theme.KettleTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the main states from synthetic data to docs/screenshots (record with
 * `gradlew recordRoborazziDebug`). Tall canvas so the whole scrolling screen is visible.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h1900dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val now = 1_735_689_600_000L
    private val kitchen = TestKettles.kitchen.copy(
        boilHistory = emptyList(),
        presets = io.github.narthexfire.kettle.data.DEFAULT_PRESETS.toMutableList().also { it[3] = it[3].copy(keepWarm = 10) }, // Oolong
    )

    private fun link(state: KettleState, t: Int, target: Int, ml: Int, kw: Int = 0) = LinkInfo(
        phase = LinkPhase.Connected,
        status = KettleStatus(state, t, target, ml, kw, "C8000200001664 01F40000".replace(" ", "")),
        lastStatusAt = now - 2000, lastSeenAt = now - 2000, connectedSince = now - 600_000,
        keepAliveRttMs = 96, reportedImei = "GD0-00000-00ab",
        lastRawFrame = "##0083{\"wifi_cmd\":\"62\",\"imei\":\"GD0-00000-00ab\",\"data3\":\"aa001803000000000000002a360000c800020000166401f400004b\"}&&",
    )

    private fun ui(link: LinkInfo?, choice: Choice, heatStart: Int? = null, cfg: KettleConfig = kitchen) = UiState(
        kettles = listOf(KettleView(cfg, link)) + TestKettles.list.drop(1).map { KettleView(it, null) },
        selectedId = cfg.id, choice = choice, now = now, heatStart = heatStart,
    )

    private fun shot(name: String, dark: Boolean = false, screen: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { KettleTheme(dark = dark) { content() } }
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        val path = "../docs/screenshots/$name.png"
        @OptIn(ExperimentalRoborazziApi::class)
        if (screen) captureScreenRoboImage(path) else compose.onRoot().captureRoboImage(path)
    }

    /* ---- README images: a real phone viewport (412 x 915 dp at 420 dpi = 1080 x 2402 px) ---- */

    @Test @Config(qualifiers = "w412dp-h915dp-420dpi")
    fun readmeStandby() = shot("readme-standby") {
        MainScreen(ui(link(KettleState.Standby, 22, 100, 500), Choice(100, 0, null)), true, MainActions())
    }

    @Test @Config(qualifiers = "w412dp-h915dp-420dpi")
    fun readmeHeating() = shot("readme-heating") {
        MainScreen(ui(link(KettleState.Heating, 64, 100, 1200, 600), Choice(100, 10, null), heatStart = 22), true, MainActions())
    }

    @Test @Config(qualifiers = "w412dp-h915dp-420dpi")
    fun readmeFirstRun() = shot("readme-first-run") {
        MainScreen(UiState(kettles = KettleConfig.DEFAULTS.map { KettleView(it, null) }, selectedId = KettleConfig.DEFAULTS[0].id,
            choice = Choice(100, 0, null), now = now), true, MainActions())
    }

    /** Scrolled down to the presets and keep-warm cards. */
    @Test @Config(qualifiers = "w412dp-h915dp-420dpi")
    fun readmePresets() {
        compose.setContent { KettleTheme(dark = false) {
            MainScreen(ui(link(KettleState.Standby, 22, 100, 1000), Choice(85, 10, null)), true, MainActions())
        } }
        compose.mainClock.advanceTimeBy(1500)
        compose.onAllNodesWithText("Keep warm (minutes)", ignoreCase = true)[0].performScrollTo()
        // Then scroll a bit more so the keep-warm card sits above the Boil/Stop bar.
        compose.onRoot().performTouchInput { swipeUp(startY = height * 0.7f, endY = height * 0.45f, durationMillis = 600) }
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        @OptIn(ExperimentalRoborazziApi::class)
        compose.onRoot().captureRoboImage("../docs/screenshots/readme-presets.png")
    }

    @Test fun firstRunLight() = shot("0-first-run-light") {
        MainScreen(UiState(kettles = KettleConfig.DEFAULTS.map { KettleView(it, null) }, selectedId = KettleConfig.DEFAULTS[0].id,
            choice = Choice(100, 0, null), now = now), true, MainActions())
    }

    @Test fun settingsFirstRunLight() = shot("0-settings-first-run-light") {
        io.github.narthexfire.kettle.ui.screens.KettleEditor(KettleView(KettleConfig.DEFAULTS[0], null), {}, {}, {})
    }

    @Test fun standbyLight() = shot("1-standby-light") {
        MainScreen(ui(link(KettleState.Standby, 22, 100, 500), Choice(100, 0, null)), true, MainActions())
    }

    @Test fun heatingLight() = shot("2-heating-light") {
        MainScreen(ui(link(KettleState.Heating, 64, 100, 1200, 600), Choice(100, 10, null), heatStart = 22), true, MainActions())
    }

    @Test fun keepWarmDark() = shot("3-keepwarm-dark", dark = true) {
        MainScreen(ui(link(KettleState.KeepWarm, 100, 100, 500, 192), Choice(100, 5, null)), true, MainActions())
    }

    @Test fun sheetLight() = shot("4-sheet-light", screen = true) {
        MainScreen(ui(link(KettleState.Standby, 22, 100, 500), Choice(75, 10, 1)), true, MainActions(), sheetOpenInitially = true)
    }

    @Test fun offBaseDark() = shot("5-offbase-dark", dark = true) {
        MainScreen(ui(link(KettleState.OffBase, 40, 100, 0), Choice(78, 0, null)), true, MainActions())
    }

    @Test fun offlineLight() = shot("6-offline-light") {
        MainScreen(
            ui(LinkInfo(phase = LinkPhase.Offline, lastError = "Can't reach 192.168.1.100:6002 (Connection refused)", reconnectCount = 3), Choice(100, 0, null)),
            true, MainActions(),
        )
    }

    @Test fun presetSelectedLight() = shot("9-preset-oolong-light") {
        MainScreen(ui(link(KettleState.Standby, 22, 100, 500), Choice(85, 0, 3)), true, MainActions())
    }

    @Test fun diagnosticsLight() = shot("7-diagnostics-light") {
        DiagnosticsScreen(
            KettleView(kitchen, link(KettleState.Standby, 22, 100, 500)),
            PhoneNet(onWifi = true, ssid = null, ssidNeedsPermission = true, ipv4 = "10.0.0.23", prefixLength = 24),
            now, "1.0 (1)", "", {}, {}, {}, {},
        )
    }

    @Test fun aboutLight() = shot("10-about-light") {
        io.github.narthexfire.kettle.ui.screens.AboutScreen(TestKettles.list, "1.0.0 (1)", "", {}, {})
    }

    @Test fun aboutDark() = shot("11-about-dark", dark = true) {
        io.github.narthexfire.kettle.ui.screens.AboutScreen(TestKettles.list, "1.0.0 (1)", "", {}, {})
    }

    @Test fun below60Light() = shot("12-below60-light") {
        MainScreen(ui(link(KettleState.Standby, 22, 100, 500), Choice(50, 10, null)), true, MainActions())
    }

    /** Legibility check: 0 / 500 / 912 / 1700 ml, cold (Standby 18°) and hot (Ready 95°). */
    @androidx.compose.runtime.Composable
    private fun GaugeMatrix() {
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.background(io.github.narthexfire.kettle.ui.theme.K.c.status.bg),
        ) {
            listOf(0, 500, 912, 1700).forEach { ml ->
                androidx.compose.foundation.layout.Row {
                    listOf(io.github.narthexfire.kettle.ui.components.KettleMode.Standby to 18, io.github.narthexfire.kettle.ui.components.KettleMode.Ready to 95).forEach { (mode, t) ->
                        io.github.narthexfire.kettle.ui.components.KettleIllustration(
                            mode = mode, temperature = t, waterMl = ml, boilPoint = 95, reduceMotion = true,
                            modifier = androidx.compose.ui.Modifier.size(206.dp, 160.dp),
                        )
                    }
                }
            }
        }
    }

    /** Water colors at 20 / 55 / 75 / 95 C (912 ml), gauge legibility over blue, purple and red. */
    @androidx.compose.runtime.Composable
    private fun TempStrip() {
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.background(io.github.narthexfire.kettle.ui.theme.K.c.status.bg)) {
            listOf(listOf(20, 55), listOf(75, 95)).forEach { row ->
                androidx.compose.foundation.layout.Row {
                    row.forEach { t ->
                        io.github.narthexfire.kettle.ui.components.KettleIllustration(
                            mode = if (t >= 95) io.github.narthexfire.kettle.ui.components.KettleMode.Ready else io.github.narthexfire.kettle.ui.components.KettleMode.Standby,
                            temperature = t, waterMl = 912, boilPoint = 95, reduceMotion = true,
                            modifier = androidx.compose.ui.Modifier.size(206.dp, 160.dp),
                        )
                    }
                }
            }
            androidx.compose.foundation.layout.Row {
                listOf(40, 80).forEach { t ->
                    io.github.narthexfire.kettle.ui.components.KettleIllustration(
                        mode = io.github.narthexfire.kettle.ui.components.KettleMode.Heating, temperature = t, waterMl = 912, boilPoint = 95,
                        reduceMotion = true, modifier = androidx.compose.ui.Modifier.size(206.dp, 160.dp),
                    )
                }
            }
        }
    }

    @Test fun tempStripLight() = shot("15-water-colors-light") { TempStrip() }

    @Test fun tempStripDark() = shot("16-water-colors-dark", dark = true) { TempStrip() }

    @Test fun gaugeMatrixLight() = shot("13-gauge-matrix-light") { GaugeMatrix() }

    @Test fun gaugeMatrixDark() = shot("14-gauge-matrix-dark", dark = true) { GaugeMatrix() }

    @Test fun lowWaterLight() = shot("17-low-water-light") {
        MainScreen(ui(link(KettleState.Standby, 22, 100, 180), Choice(100, 0, null)), true, MainActions())
    }

    /** All six preset names at 412 dp with larger system font sizes: no mid-word breaks. */
    @androidx.compose.runtime.Composable
    private fun PresetsAt(fontScale: Float) {
        val d = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, fontScale),
        ) {
            MainScreen(ui(link(KettleState.Standby, 22, 100, 500), Choice(70, 0, 4)), true, MainActions())
        }
    }

    @Test fun presetsFont115() = shot("18-presets-font115-light") { PresetsAt(1.15f) }

    @Test fun presetsFont130() = shot("19-presets-font130-light") { PresetsAt(1.3f) }

    @Test fun helpDark() = shot("8-help-dark", dark = true) { HelpScreen({}, {}) }
}
