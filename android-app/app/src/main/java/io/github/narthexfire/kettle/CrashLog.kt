package io.github.narthexfire.kettle

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the last uncaught exception to app-private storage (files/last_crash.txt) so it can be
 * read and copied from the Kettle details screen without USB debugging. Nothing leaves the phone.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { write(app, thread, e) }
            previous?.uncaughtException(thread, e)
        }
    }

    fun format(thread: Thread, e: Throwable, now: Long = System.currentTimeMillis()): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        return buildString {
            appendLine("Time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now)))
            appendLine("App: Kettle ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Thread: ${thread.name}")
            append(sw.toString())
        }
    }

    fun write(context: Context, thread: Thread, e: Throwable) {
        File(context.filesDir, FILE).writeText(format(thread, e).take(64_000))
    }

    fun read(context: Context): String? =
        runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }
}

class KettleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
