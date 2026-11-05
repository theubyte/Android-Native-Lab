package de.millione.schulranzen.backup

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.millione.schulranzen.core.backup.BackupException
import de.millione.schulranzen.core.backup.BackupHooks
import de.millione.schulranzen.core.backup.MergeParticipant
import de.millione.schulranzen.core.backup.SyncError
import de.millione.schulranzen.core.backup.SyncException
import de.millione.schulranzen.core.backup.SyncPasswordException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Keeps a merge away from data that is open on screen. A notebook keeps its
 * pages in memory; if a merge changed a page underneath, the next save would
 * overwrite the merged version and it would look like a newer local edit.
 *
 * The screens that hold pages in memory register here, the automatic sync only
 * merges when none is open and a screen opening meanwhile waits for the merge.
 */
object EditGate {
    private val lock = Any()
    private var editors = 0
    private val applying = MutableStateFlow(false)

    /** True while a merge is changing the data. */
    val merging: StateFlow<Boolean> = applying

    val editing: Boolean get() = synchronized(lock) { editors > 0 }

    /** Marks an editor as open until the result is closed. */
    fun enter(): AutoCloseable {
        synchronized(lock) { editors++ }
        var closed = false
        return AutoCloseable {
            synchronized(lock) { if (!closed) { closed = true; editors-- } }
        }
    }

    /** Like [enter], but waits for a running merge first. Call before loading the pages. */
    suspend fun enterWhenFree(): AutoCloseable {
        while (true) {
            applying.first { !it }
            synchronized(lock) {
                if (!applying.value) {
                    editors++
                    var closed = false
                    return AutoCloseable { synchronized(lock) { if (!closed) { closed = true; editors-- } } }
                }
            }
        }
    }

    /** Runs a merge; throws [EditorOpenException] when an editor is open. */
    internal suspend fun <T> exclusive(block: suspend () -> T): T {
        synchronized(lock) {
            if (editors > 0) throw EditorOpenException()
            applying.value = true
        }
        try {
            return block()
        } finally {
            applying.value = false
        }
    }
}

class EditorOpenException : BackupException("Zum Abgleichen bitte zuerst das offene Heft oder Buch schließen.")

/**
 * "Jetzt abgleichen" without pressing it: when the app is opened and every few
 * hours in the background, each switched on by the user. Needs [install] at app
 * start, because a background run has no screen to get the app's data from.
 */
object AutoSync {
    private const val PERIODIC = "schulranzen-sync"
    private const val ONCE = "schulranzen-sync-now"
    private const val HOURS = 6L
    /** Switching apps back and forth should not sync each time. */
    private const val OPEN_PAUSE_MS = 15 * 60 * 1000L

    @Volatile private var participantsOf: ((Context) -> List<MergeParticipant>)? = null
    @Volatile private var hooksOf: ((Context) -> BackupHooks)? = null
    private var started = 0
    private var lastOpen = 0L

    val installed: Boolean get() = participantsOf != null

    internal fun participants(context: Context) = participantsOf?.invoke(context)
    internal fun hooks(context: Context) = hooksOf?.invoke(context) ?: BackupHooks.None

    /**
     * Call once in Application.onCreate. From then on the automatic sync runs as
     * set on the backup screen. It relies on [EditGate], so the notebook and book
     * screens must register there.
     */
    fun install(app: Application, participants: (Context) -> List<MergeParticipant>, hooks: (Context) -> BackupHooks) {
        participantsOf = participants
        hooksOf = hooks
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) opened(app)
            }
            override fun onActivityStopped(activity: Activity) { started-- }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        apply(app)
    }

    private fun opened(context: Context) {
        val s = BackupSettings.get(context).state.value
        val now = System.currentTimeMillis()
        if (!s.syncOnOpen || now - lastOpen < OPEN_PAUSE_MS) return
        lastOpen = now
        val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints(s)).build()
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, req) }
    }

    /** Applies changed settings. */
    fun apply(context: Context) {
        val s = BackupSettings.get(context).state.value
        val wm = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        if (!s.syncBackground || !installed) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val req = PeriodicWorkRequestBuilder<SyncWorker>(HOURS, TimeUnit.HOURS)
            .setConstraints(constraints(s))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    private fun constraints(s: BackupSettings.State) = Constraints.Builder()
        .setRequiredNetworkType(if (s.syncWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresStorageNotLow(true)
        .build()
}

/** One automatic sync. Never asks anything: what needs the user waits for the button and is shown there. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val participants = AutoSync.participants(ctx) ?: return Result.success()
        val settings = BackupSettings.get(ctx)
        val account = SyncAccount(ctx)
        if (!account.state.value.loggedIn) return Result.success()
        // An open notebook: come back later, no error for that.
        if (EditGate.editing) return Result.retry()
        return try {
            BackupManager(ctx, AutoSync.hooks(ctx)).syncNow(account, participants, settings.password())
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: EditorOpenException) {
            Result.retry()
        } catch (e: Exception) {
            when {
                e is BackupException && e.message?.startsWith("Es läuft schon") == true -> Result.retry()
                // Away from home is normal: try again later without bothering anyone.
                e is SyncException && e.error == SyncError.UNREACHABLE -> if (runAttemptCount < 3) Result.retry() else Result.failure()
                e is SyncPasswordException -> Result.failure() // recorded by syncNow, the button asks
                else -> Result.failure()
            }
        }
    }
}
