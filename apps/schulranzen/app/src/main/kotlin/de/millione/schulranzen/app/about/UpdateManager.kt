package de.millione.schulranzen.app.about

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import de.millione.schulranzen.app.BuildConfig
import de.millione.schulranzen.core.update.UpdateApk
import de.millione.schulranzen.core.update.UpdateClient
import de.millione.schulranzen.core.update.UpdateError
import de.millione.schulranzen.core.update.UpdateException
import de.millione.schulranzen.core.update.UpdateManifest
import de.millione.schulranzen.core.update.UpdateManifestParser
import de.millione.schulranzen.core.update.UpdatePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/** What the update layer shows. */
internal sealed interface UpdateState {
    data object Idle : UpdateState
    /** Only from the button; the automatic check stays invisible. */
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val manifest: UpdateManifest, val apk: UpdateApk) : UpdateState
    data class Downloading(val manifest: UpdateManifest, val apk: UpdateApk, val done: Long, val total: Long?) : UpdateState
    data class Ready(val manifest: UpdateManifest, val file: File) : UpdateState
    /** Android has to allow this app to install, once. */
    data class NeedsPermission(val manifest: UpdateManifest, val file: File) : UpdateState
    /** [retry] is set when the same download can simply continue. */
    data class Failed(val message: String, val retry: Available? = null) : UpdateState
}

/**
 * The update process, independent of any screen, so a download goes on while
 * the user moves through the app. It runs as long as the process lives (no
 * foreground service); if Android ends it, the partial file is continued later.
 * Nothing installs itself: the last step is always the user's and Android's.
 */
internal class UpdateManager private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("update", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = File(app.cacheDir, "updates")
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null
    @Volatile private var cancelRequested = false
    private var startupDone = false

    private val installedCode get() = BuildConfig.VERSION_CODE.toLong()

    /**
     * Debug builds only: a test server for two real versions in the emulator
     * (docs/UPDATE.md), which also allows plain http for it. R8 removes this
     * from release builds, where the URL is fixed and https is required.
     */
    private val debugUrl: String? get() =
        if (BuildConfig.DEBUG) runCatching { File(app.filesDir, "update-debug-url.txt").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } else null

    private val client get() = UpdateClient(httpsOnly = debugUrl == null)

    var autoCheck: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(v) { prefs.edit().putBoolean(KEY_AUTO, v).apply() }

    /** Once per process: tidy up, bring back a finished download, check if a day has passed. */
    fun onAppStart() {
        synchronized(this) { if (startupDone) return; startupDone = true }
        launchExclusive {
            restoreOrClean()
            val due = UpdatePolicy.autoCheckDue(prefs.getLong(KEY_LAST, 0), System.currentTimeMillis())
            if (autoCheck && due && _state.value == UpdateState.Idle) check(manual = false)
        }
    }

    /** The "Nach Updates suchen" button. */
    fun checkNow() {
        if (job?.isActive == true) return
        when (_state.value) {
            is UpdateState.Ready, is UpdateState.NeedsPermission -> return
            else -> {}
        }
        _state.value = UpdateState.Checking
        launchExclusive { check(manual = true) }
    }

    private fun launchExclusive(block: suspend () -> Unit) {
        job = scope.launch { block() }
    }

    private fun check(manual: Boolean) {
        try {
            val url = debugUrl ?: MANIFEST_URL
            val manifest = client.fetchManifest(url)
            prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
            if (!UpdatePolicy.isNewer(installedCode, manifest)) {
                _state.value = if (manual) UpdateState.UpToDate else UpdateState.Idle
                return
            }
            val apk = UpdatePolicy.pickApk(manifest, Build.SUPPORTED_ABIS.toList())
            _state.value = UpdateState.Available(manifest, apk)
        } catch (e: UpdateException) {
            // Offline or server trouble must never disturb a normal start.
            _state.value = if (manual) UpdateState.Failed(e.message ?: "Die Prüfung ist fehlgeschlagen.") else UpdateState.Idle
        } catch (e: RuntimeException) {
            _state.value = if (manual) UpdateState.Failed("Die Prüfung ist fehlgeschlagen.") else UpdateState.Idle
        }
    }

    fun startDownload(offer: UpdateState.Available) {
        if (job?.isActive == true) return
        cancelRequested = false
        val (manifest, apk) = offer
        launchExclusive {
            val name = UpdatePolicy.fileName(manifest, apk)
            val part = File(dir, "$name.part")
            try {
                dir.mkdirs()
                // Partial files of other releases are of no use any more.
                dir.listFiles()?.filter { it.name != part.name }?.forEach { it.delete() }
                _state.value = UpdateState.Downloading(manifest, apk, part.length(), apk.size)
                client.download(apk, part, cancelled = { cancelRequested }) { done, total ->
                    _state.value = UpdateState.Downloading(manifest, apk, done, total)
                }
                val target = File(dir, name)
                try {
                    UpdateClient.verifyChecksum(part, apk)
                    if (!part.renameTo(target)) throw UpdateException(UpdateError.SERVER, "Die Datei ließ sich nicht ablegen.")
                    verifyPackage(target, manifest)
                } catch (e: UpdateException) {
                    target.delete()
                    throw e
                }
                prefs.edit().putString(KEY_PENDING, encodePending(manifest, apk, target)).apply()
                _state.value = readyState(manifest, target)
            } catch (e: UpdateException) {
                _state.value = when (e.error) {
                    UpdateError.CANCELLED -> offer
                    UpdateError.OFFLINE, UpdateError.SERVER -> UpdateState.Failed(e.message ?: "Der Download ist fehlgeschlagen.", retry = offer)
                    else -> UpdateState.Failed(e.message ?: "Der Download ist fehlgeschlagen.")
                }
            } catch (e: java.io.IOException) {
                // Disk full and the like.
                _state.value = UpdateState.Failed("Die Datei ließ sich nicht speichern. Ist genug Platz frei?", retry = offer)
            }
        }
    }

    fun cancelDownload() { cancelRequested = true }

    /** "Später": asked again at the next daily check. */
    fun dismiss() { _state.value = UpdateState.Idle }

    /** Drops a finished download the user does not want. */
    fun discard() {
        prefs.edit().remove(KEY_PENDING).apply()
        dir.listFiles()?.forEach { it.delete() }
        _state.value = UpdateState.Idle
    }

    private fun canInstall() = app.packageManager.canRequestPackageInstalls()

    private fun readyState(m: UpdateManifest, f: File): UpdateState =
        if (canInstall()) UpdateState.Ready(m, f) else UpdateState.NeedsPermission(m, f)

    /** The user came back, maybe from the system setting. */
    fun onResume() {
        val s = _state.value
        if (s is UpdateState.NeedsPermission && canInstall()) _state.value = UpdateState.Ready(s.manifest, s.file)
    }

    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))

    /** Hands the file to Android's own installer, which asks once more and checks the signature itself. */
    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(app, app.packageName + ".files", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Not just Android's check at install time: a foreign or mixed-up file is stopped here already. */
    private fun verifyPackage(file: File, manifest: UpdateManifest) {
        val info = archiveInfo(file)
        if (info == null) {
            file.delete()
            throw UpdateException(UpdateError.BAD_PACKAGE, "Die geladene Datei ist keine gültige App. Sie wurde gelöscht.")
        }
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        if (info.packageName != app.packageName || code != manifest.versionCode) {
            file.delete()
            throw UpdateException(UpdateError.BAD_PACKAGE, "Die geladene Datei gehört nicht zu diesem Update. Sie wurde gelöscht.")
        }
        val installed = app.packageManager.getPackageInfo(app.packageName, signingFlags())
        if (!UpdatePolicy.sameSigner(certHashes(installed), certHashes(info))) {
            file.delete()
            throw UpdateException(UpdateError.BAD_SIGNATURE, "Das Update ist nicht vom selben Herausgeber signiert wie diese App. Die Datei wurde gelöscht.")
        }
    }

    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    private fun archiveInfo(file: File): PackageInfo? =
        @Suppress("DEPRECATION") app.packageManager.getPackageArchiveInfo(file.path, signingFlags())

    private fun certHashes(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION") info.signatures
        }
        return signatures.orEmpty().map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    /**
     * After an update (or a process death while the user was in the system
     * setting) the cache decides: the old APK goes, a still valid one is offered
     * again, checked as if it were new.
     */
    private fun restoreOrClean() {
        val pending = prefs.getString(KEY_PENDING, null)
        val restored = pending?.let { runCatching { decodePending(it) }.getOrNull() }
        if (restored != null && UpdatePolicy.isNewer(installedCode, restored.manifest) && restored.file.exists()) {
            val ok = runCatching {
                UpdateClient.verifyChecksum(restored.file, restored.apk)
                verifyPackage(restored.file, restored.manifest)
            }.isSuccess
            if (ok) {
                _state.value = readyState(restored.manifest, restored.file)
                return
            }
        }
        prefs.edit().remove(KEY_PENDING).apply()
        // Keeps a partial download of a release that is still newer; the next offer continues it.
        val keepCode = Regex("""schulranzen-(\d+)-""")
        dir.listFiles()?.forEach { f ->
            val code = keepCode.find(f.name)?.groupValues?.get(1)?.toLongOrNull()
            if (!f.name.endsWith(".part") || code == null || code <= installedCode) f.delete()
        }
    }

    private class Pending(val manifest: UpdateManifest, val apk: UpdateApk, val file: File)

    private fun encodePending(m: UpdateManifest, apk: UpdateApk, file: File): String {
        val q = { s: String -> kotlinx.serialization.json.JsonPrimitive(s).toString() }
        val size = apk.size?.let { ""","size":$it""" }.orEmpty()
        val manifest = """{"versionCode":${m.versionCode},"versionName":${q(m.versionName)},"changes":${q(m.changes)},""" +
            """"apks":{${q(apk.abi)}:{"url":${q(apk.url)},"sha256":${q(apk.sha256)}$size}}}"""
        return file.name + "\n" + manifest
    }

    private fun decodePending(text: String): Pending {
        val name = text.substringBefore('\n')
        val m = UpdateManifestParser.parse(text.substringAfter('\n'), httpsOnly = debugUrl == null)
        return Pending(m, m.apks.values.single(), File(dir, name))
    }

    companion object {
        /** The repository holds several apps, so this names one file instead of "the latest release". */
        const val MANIFEST_URL = "https://raw.githubusercontent.com/theubyte/Android-Native-Lab/main/apps/schulranzen/update.json"
        private const val KEY_AUTO = "auto"
        private const val KEY_LAST = "lastCheck"
        private const val KEY_PENDING = "pending"

        @Volatile private var instance: UpdateManager? = null
        fun get(context: Context): UpdateManager = instance ?: synchronized(this) {
            instance ?: UpdateManager(context).also { instance = it }
        }
    }
}
