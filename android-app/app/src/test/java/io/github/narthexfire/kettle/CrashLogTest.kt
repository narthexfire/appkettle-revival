package io.github.narthexfire.kettle

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import io.github.narthexfire.kettle.data.KettleConfig
import io.github.narthexfire.kettle.net.PhoneNet
import io.github.narthexfire.kettle.ui.Diagnostics
import io.github.narthexfire.kettle.ui.KettleView
import io.github.narthexfire.kettle.ui.screens.KettleEditor
import io.github.narthexfire.kettle.ui.theme.KettleTheme
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h1900dp-xxhdpi")
class CrashLogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun crashIsWrittenReadAndShownInDetails() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        CrashLog.clear(ctx)
        assertNull(CrashLog.read(ctx))
        CrashLog.write(ctx, Thread.currentThread(), IllegalArgumentException("Cannot perform operation for Em and Sp"))
        val text = CrashLog.read(ctx)!!
        assertTrue(text.contains("IllegalArgumentException: Cannot perform operation for Em and Sp"))
        assertTrue(text.contains("App: Kettle "))
        val copy = Diagnostics.asText(Diagnostics.sections(KettleConfig.DEFAULTS[0], null, PhoneNet(), 0L, "1.0.0", "x", text), 0L)
        assertTrue(copy.contains("LAST CRASH"))
        assertTrue(copy.contains("Cannot perform operation for Em and Sp"))
        CrashLog.clear(ctx)
        assertNull(CrashLog.read(ctx))
    }

    /** The kettle Settings editor uses the same OutlinedTextFields; focusing them must not crash. */
    @Test fun settingsEditorTextFieldsDoNotCrash() {
        compose.setContent {
            KettleTheme(dark = false) {
                KettleEditor(KettleView(KettleConfig.DEFAULTS[0], null), {}, {}, {})
            }
        }
        compose.onAllNodesWithText("Name")[0].performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Kettle ID")[0].performClick()
        compose.waitForIdle()
    }
}
