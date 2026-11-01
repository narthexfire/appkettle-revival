package io.github.narthexfire.kettle

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Design rule: no Toast, Snackbar or Popup anywhere; no dialogs on the main screen
 *  except the user-initiated editors (long-press preset editor, "Save as preset" slot picker). */
class NoOverlayTest {
    private val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    private fun code(line: String): String? {
        val c = line.substringBefore("//").trim()
        return if (c.startsWith("*") || c.startsWith("/*")) null else c
    }

    @Test fun noToastsSnackbarsOrPopupsInTheSources() {
        val bad = Regex("""\b(Toast|Snackbar\w*|showSnackbar|makeText|Popup|PopupWindow)\b""")
        val hits = root.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l -> code(l)?.takeIf { bad.containsMatchIn(it) }?.let { "${f.name}:${i + 1}: $l" } }
        }.toList()
        assertTrue("floating messages found:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test fun noDialogsOnTheMainScreenExceptUserEditors() {
        val allowed = setOf("PresetDialog", "SaveAsPresetDialog")
        val f = root.walkTopDown().first { it.name == "MainScreen.kt" }
        var fn = ""
        val hits = mutableListOf<String>()
        f.readLines().forEachIndexed { i, l ->
            Regex("""\bfun\s+(\w+)""").find(l)?.let { fn = it.groupValues[1] }
            val c = code(l) ?: return@forEachIndexed
            if (Regex("""\b(AlertDialog|BasicAlertDialog|Dialog)\s*\(""").containsMatchIn(c) && fn !in allowed) hits += "MainScreen.kt:${i + 1} in $fn: $l"
        }
        assertTrue("dialogs on the main screen:\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
