package de.millione.schulranzen.app.about

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import de.millione.schulranzen.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local crash capture without any outside service: a crash becomes a text
 * file and the next start offers to share it. Nothing is ever sent by itself;
 * the app declares no reporting endpoint at all.
 */
internal object CrashCatcher {
    private const val PREFS = "crash"
    private const val KEY_EXIT_SEEN = "exitSeen"

    fun store(context: Context) = CrashStore(File(context.noBackupFilesDir, "crash"))

    private fun header() = ReportHeader(
        BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), BuildConfig.BUILD_TYPE,
        "${Build.MANUFACTURER} ${Build.MODEL}", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    )

    private fun timeText(millis: Long) = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).format(Date(millis))

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Must never throw: the system's own handling still has to run after this.
            runCatching {
                val now = System.currentTimeMillis()
                val detail = "Thread: ${thread.name}\n\n${error.stackTraceToString()}"
                store(app).write(CrashFormat.report(header(), "Absturz", timeText(now), detail), now)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Crashes in native code (ink engine, ML Kit) and freezes never reach the
     * handler above. Android 11 and later remember them, so they are turned
     * into reports on the next start. Blocking: call off the main thread.
     */
    fun collectSystemExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = runCatching { am.getHistoricalProcessExitReasons(null, 0, 8) }.getOrNull() ?: return
        // First run with this feature: older exits are not worth asking about.
        val seen = prefs.getLong(KEY_EXIT_SEEN, -1L)
        val newest = exits.maxOfOrNull { it.timestamp } ?: System.currentTimeMillis()
        if (seen >= 0) for (e in exits) {
            if (e.timestamp <= seen) continue
            val kind = when (e.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "Absturz in nativem Code"
                ApplicationExitInfo.REASON_ANR -> "App reagierte nicht mehr"
                else -> continue
            }
            val detail = "Grund: ${e.description.orEmpty()}\nProzess: ${e.processName}\nWichtigkeit: ${e.importance}"
            store(context).write(CrashFormat.report(header(), kind, timeText(e.timestamp), detail), e.timestamp)
        }
        prefs.edit().putLong(KEY_EXIT_SEEN, maxOf(newest, seen)).apply()
    }

    /** Opens the system share sheet; the user picks where it goes, there is no preset recipient. */
    fun share(context: Context, report: File) {
        // Only this copy is reachable through the FileProvider.
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val copy = report.copyTo(File(dir, "schulranzen-bericht.txt"), overwrite = true)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", copy)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Schulranzen ${BuildConfig.VERSION_NAME}: Absturzbericht")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        val chooser = Intent.createChooser(send, "Bericht teilen")
        if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
