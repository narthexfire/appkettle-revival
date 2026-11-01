package io.github.narthexfire.kettle

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import io.github.narthexfire.kettle.ui.components.TempControl
import io.github.narthexfire.kettle.ui.theme.KettleTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The -/+ buttons step on release, repeat only after a long press, and ignore drags. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h900dp-xxhdpi")
class RepeatButtonTest {
    @get:Rule val compose = createComposeRule()
    private var target = 70
    private var changes = 0

    private fun setUp() {
        compose.setContent { KettleTheme(false) { TempControl(target, 95, { target = it; changes++ }, showEnds = true) } }
    }

    @Test fun tapStepsOnceOnRelease() {
        setUp()
        val plus = compose.onNodeWithContentDescription("1 degree hotter")
        plus.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(100)
        assertEquals("nothing on finger-down", 0, changes)
        plus.performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(1, changes); assertEquals(71, target)
    }

    @Test fun dragStartingOnTheButtonChangesNothing() {
        setUp()
        compose.onNodeWithContentDescription("1 degree hotter").performTouchInput {
            down(center); moveBy(Offset(0f, 120f)); moveBy(Offset(0f, 120f)); up()
        }
        compose.waitForIdle()
        assertEquals(0, changes); assertEquals(70, target)
    }

    @Test fun holdRepeatsAfterTheLongPressTimeout() {
        setUp()
        val plus = compose.onNodeWithContentDescription("1 degree hotter")
        plus.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_500)
        plus.performTouchInput { up() }
        compose.waitForIdle()
        assertTrue("repeated: $changes", changes > 3)
    }
}
