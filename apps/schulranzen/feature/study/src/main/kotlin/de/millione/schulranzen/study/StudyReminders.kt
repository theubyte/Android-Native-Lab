package de.millione.schulranzen.study

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.millione.schulranzen.core.study.FocusState
import de.millione.schulranzen.core.study.Reminders
import de.millione.schulranzen.core.study.Timetable
import de.millione.schulranzen.design.QuietButton
import de.millione.schulranzen.design.Ranzen
import de.millione.schulranzen.study.ui.FieldLabel
import de.millione.schulranzen.study.ui.LineField
import de.millione.schulranzen.study.ui.RetroSwitch
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Local reminders, off until the learner switches them on. The daily one is a chain
 * of one-shot jobs rather than a periodic one, so it stays at the chosen wall-clock
 * time over daylight saving. WorkManager keeps the chain over restarts of the device.
 */
object StudyReminders {
    /** Set on the launch intent when a reminder is tapped; the host can open "study" on it. */
    const val EXTRA_OPEN_STUDY = "de.millione.schulranzen.study.OPEN"

    private const val PREFS = "study_reminders"
    private const val WORK_DAILY = "study_reminder_daily"
    private const val CHANNEL = "study_reminders"
    private const val ID_DAILY = 4101
    private const val ID_EXAM_BASE = 4200
    internal const val ID_FOCUS = 4102

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(c: Context): Boolean = prefs(c).getBoolean("enabled", false)
    fun minuteOfDay(c: Context): Int = prefs(c).getInt("minute", Reminders.DEFAULT_MINUTE)

    fun setEnabled(c: Context, enabled: Boolean) {
        prefs(c).edit().putBoolean("enabled", enabled).apply()
        if (enabled) schedule(c, replace = true) else WorkManager.getInstance(c).cancelUniqueWork(WORK_DAILY)
    }

    fun setMinuteOfDay(c: Context, minute: Int) {
        prefs(c).edit().putInt("minute", minute).apply()
        if (isEnabled(c)) schedule(c, replace = true)
    }

    /** Safe to call on every start: only sets up the chain again if it got lost. */
    fun ensureScheduled(c: Context) {
        if (isEnabled(c)) schedule(c, replace = false)
    }

    internal fun schedule(c: Context, replace: Boolean) {
        val delay = Reminders.delayMillis(ZonedDateTime.now(), minuteOfDay(c))
        val work = OneTimeWorkRequestBuilder<StudyReminderWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(c).enqueueUniqueWork(WORK_DAILY, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, work)
    }

    fun hasPermission(c: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(c: Context) {
        val manager = c.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Lernen", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Erinnerungen ans Lernen und an nahe Prüfungen"
                },
            )
        }
    }

    internal fun post(c: Context, id: Int, title: String, body: String) {
        if (!hasPermission(c)) return
        ensureChannel(c)
        val open = c.packageManager.getLaunchIntentForPackage(c.packageName)?.apply {
            putExtra(EXTRA_OPEN_STUDY, true)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = open?.let { PendingIntent.getActivity(c, id, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_study_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching { NotificationManagerCompat.from(c).notify(id, n) }
    }

    /** Runs once a day: posts what is due and sets up the next day. */
    internal suspend fun fire(c: Context) {
        val data = StudyRepository(c.applicationContext).reminderData()
        val today = java.time.LocalDate.now()
        val next = data.exams.firstOrNull()?.first
        val days = next?.let { java.time.temporal.ChronoUnit.DAYS.between(today, java.time.LocalDate.ofEpochDay(it.dateEpochDay)) }
        Reminders.daily(data.dueReviews, data.fresh, data.reviewedToday, next?.subject, days)?.let { post(c, ID_DAILY, it.title, it.body) }
        data.exams.take(6).forEachIndexed { i, (exam, percent) ->
            val left = java.time.temporal.ChronoUnit.DAYS.between(today, java.time.LocalDate.ofEpochDay(exam.dateEpochDay))
            Reminders.exam(exam.subject, ExamKind.of(exam.kind).label, left, percent)?.let { post(c, ID_EXAM_BASE + i, it.title, it.body) }
        }
    }
}

/** The daily job: whatever fails, the next day stays scheduled. */
class StudyReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            StudyReminders.fire(applicationContext)
        } catch (_: Exception) {
            // A reminder that could not be built is no reason to lose tomorrow's.
        }
        if (StudyReminders.isEnabled(applicationContext)) {
            // REPLACE on a running unique job would cancel this one; schedule through a fresh name.
            val delay = Reminders.delayMillis(ZonedDateTime.now(), StudyReminders.minuteOfDay(applicationContext))
            val work = OneTimeWorkRequestBuilder<StudyReminderWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork("study_reminder_daily", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }
        return Result.success()
    }
}

/** Announces the end of a focus phase, so the screen can stay off. */
internal object FocusNotice {
    private const val WORK = "study_focus_phase"

    fun schedule(c: Context, state: FocusState) {
        val text = if (state.inBreak) "Pause vorbei. Weiter mit Block ${state.block + 1}." else "Block ${state.block} geschafft. Zeit für eine Pause."
        val work = OneTimeWorkRequestBuilder<FocusNoticeWorker>()
            .setInitialDelay(state.secondsLeft.toLong(), TimeUnit.SECONDS)
            .setInputData(workDataOf("text" to text))
            .build()
        WorkManager.getInstance(c).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, work)
    }

    fun cancel(c: Context) {
        WorkManager.getInstance(c).cancelUniqueWork(WORK)
    }
}

class FocusNoticeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        StudyReminders.post(c, StudyReminders.ID_FOCUS, "Fokus-Timer", inputData.getString("text") ?: "Phase beendet.")
        // The next phase has its own notice as long as the timer runs.
        val store = FocusStore(c)
        if (store.running && store.pausedAt == 0L) {
            val elapsed = (System.currentTimeMillis() - store.startedAt) / 1000
            if (!de.millione.schulranzen.core.study.FocusTimer.finished(store.plan, store.blocks, elapsed)) {
                val state = de.millione.schulranzen.core.study.FocusTimer.at(store.plan, elapsed + 1)
                val text = if (state.inBreak) "Pause vorbei. Weiter mit Block ${state.block + 1}." else "Block ${state.block} geschafft. Zeit für eine Pause."
                val next = OneTimeWorkRequestBuilder<FocusNoticeWorker>()
                    .setInitialDelay(state.secondsLeft.toLong(), TimeUnit.SECONDS)
                    .setInputData(workDataOf("text" to text))
                    .build()
                WorkManager.getInstance(c).enqueueUniqueWork("study_focus_phase", ExistingWorkPolicy.APPEND_OR_REPLACE, next)
            }
        }
        return Result.success()
    }
}

/** Reminder part of the study settings: switch, time, and the Android permission. */
@Composable
internal fun ReminderSettings() {
    val c = Ranzen.colors
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(StudyReminders.isEnabled(context)) }
    var time by remember { mutableStateOf(Timetable.formatTime(StudyReminders.minuteOfDay(context))) }
    var denied by remember { mutableStateOf(enabled && !StudyReminders.hasPermission(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        enabled = granted
        StudyReminders.setEnabled(context, granted)
    }
    Text("Erinnerungen", style = Ranzen.type.heading, color = c.ink)
    RetroSwitch("Täglich erinnern", enabled, { on ->
        if (on && !StudyReminders.hasPermission(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            enabled = on
            denied = false
            StudyReminders.setEnabled(context, on)
        }
    }, hint = "Sagt, was fällig ist, und warnt vor nahen Prüfungen. Bleibt still, wenn nichts ansteht.")
    if (denied) {
        Text("Android erlaubt der App gerade keine Benachrichtigungen.", style = Ranzen.type.body, color = c.signal)
        QuietButton("Einstellungen öffnen", {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }, color = c.ink)
    }
    if (enabled) {
        Spacer(Modifier.height(8.dp))
        FieldLabel("Uhrzeit")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(110.dp)) {
                LineField(time, { t ->
                    time = t
                    Timetable.parseTime(t)?.let { StudyReminders.setMinuteOfDay(context, it) }
                }, "17:00", style = Ranzen.type.mono)
            }
            if (Timetable.parseTime(time) == null) {
                Text("Als 17:00 eingeben", style = Ranzen.type.caption, color = c.signal, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}
