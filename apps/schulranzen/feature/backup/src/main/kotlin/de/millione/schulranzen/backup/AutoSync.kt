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
import android.net.ConnectivityManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
                if (started++ == 0) { opened(app); LiveSync.start(app) }
            }
            override fun onActivityStopped(activity: Activity) {
                if (--started == 0) { LiveSync.stop(); left(app) }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        apply(app)
    }

    /** What was just scanned or learnt should not wait until the app is opened again. */
    private fun left(context: Context) {
        val s = BackupSettings.get(context).state.value
        if (!s.syncLive) return
        val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints(s)).build()
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, req) }
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

/**
 * The devices in step while the app is open. A pass that finds nothing costs
 * one small request and a look at the file times, so it can run often. Pages
 * held by an open notebook stay out of it ([EditGate]); closing one syncs at once.
 */
internal object LiveSync {
    private const val TICK_MS = 2_000L
    private const val PAUSE_MS = 30_000L
    private const val MAX_PAUSE_MS = 5 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun start(context: Context) {
        job?.cancel()
        val app = context.applicationContext
        job = scope.launch { loop(app) }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun loop(context: Context) {
        val settings = BackupSettings.get(context)
        val network = context.getSystemService(ConnectivityManager::class.java)
        var next = 0L
        var failures = 0
        var wasEditing = false
        while (true) {
            delay(TICK_MS)
            val s = settings.state.value
            val participants = AutoSync.participants(context)
            // Read anew each time: signing in happens on another instance, on the backup screen.
            val account = SyncAccount(context)
            if (!s.syncLive || participants == null || !account.state.value.loggedIn) continue
            val editing = EditGate.editing
            if (wasEditing && !editing) next = 0
            wasEditing = editing
            if (editing || System.currentTimeMillis() < next) continue
            if (s.syncWifiOnly && network?.isActiveNetworkMetered != false) continue
            try {
                BackupManager(context, AutoSync.hooks(context)).syncNow(account, participants, settings.password())
                failures = 0
            } catch (e: CancellationException) {
                throw e
            } catch (_: EditorOpenException) {
                // A notebook opened meanwhile: its closing starts the next pass.
            } catch (_: Exception) {
                // Away from home or the server is down; syncNow noted it for the backup screen.
                failures++
            }
            next = System.currentTimeMillis() + (PAUSE_MS shl failures.coerceAtMost(4)).coerceAtMost(MAX_PAUSE_MS)
        }
    }
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
