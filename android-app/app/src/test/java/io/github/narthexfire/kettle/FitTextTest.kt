package io.github.narthexfire.kettle

import io.github.narthexfire.kettle.ui.components.breaksMidWord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FitTextTest {
    @Test fun detectsMidWordBreaks() {
        val t = "Hot Chocolate"
        assertFalse(breaksMidWord(t, listOf(4, 13)))      // "Hot " | "Chocolate": at a space
        assertTrue(breaksMidWord(t, listOf(4, 12, 13)))   // "Hot " | "Chocolat" | "e": mid-word
        assertFalse(breaksMidWord(t, listOf(13)))         // one line
        assertTrue(breaksMidWord("Chocolate", listOf(8, 9)))
    }
}
