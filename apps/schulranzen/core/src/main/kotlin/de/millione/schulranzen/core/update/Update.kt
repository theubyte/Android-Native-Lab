package de.millione.schulranzen.core.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** Why an update step failed; the UI shows [UpdateException.message] as is. */
enum class UpdateError { OFFLINE, SERVER, BAD_MANIFEST, NO_APK_FOR_DEVICE, INSECURE_URL, TOO_LARGE, BAD_CHECKSUM, BAD_SIGNATURE, BAD_PACKAGE, CANCELLED }

class UpdateException(val error: UpdateError, message: String, cause: Throwable? = null) : IOException(message, cause)

/** One APK of a release. [size] is optional in the file; it allows a limit and a progress bar from the start. */
data class UpdateApk(val abi: String, val url: String, val sha256: String, val size: Long? = null)

/** The content of `apps/schulranzen/update.json`. */
data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val changes: String,
    val apks: Map<String, UpdateApk>,
)

/** The decisions of the updater, without any device or network. */
object UpdatePolicy {
    const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    fun isNewer(installedCode: Long, manifest: UpdateManifest): Boolean = manifest.versionCode > installedCode

    /** At most once a day; a clock set back must not stop the checks for good. */
    fun autoCheckDue(lastCheckMs: Long, nowMs: Long): Boolean =
        lastCheckMs <= 0 || nowMs < lastCheckMs || nowMs - lastCheckMs >= AUTO_CHECK_INTERVAL_MS

    /**
     * The first ABI the device names that the release has. [supportedAbis] is in
     * the device's order of preference (Build.SUPPORTED_ABIS), so an arm64 device
     * takes arm64 and a release without it would fall back to 32 bit.
     */
    fun pickApk(manifest: UpdateManifest, supportedAbis: List<String>): UpdateApk =
        supportedAbis.firstNotNullOfOrNull { manifest.apks[it] }
            ?: throw UpdateException(UpdateError.NO_APK_FOR_DEVICE, "Für dieses Gerät gibt es in Version ${manifest.versionName} keine passende Datei.")

    /** Both sets are certificate hashes; an APK from anyone else never counts as the same app. */
    fun sameSigner(installed: Set<String>, apk: Set<String>): Boolean = installed.isNotEmpty() && installed == apk

    fun requireHttps(url: String) {
        val scheme = runCatching { URI(url).scheme }.getOrNull()
        val host = runCatching { URI(url).host }.getOrNull()
        if (!scheme.equals("https", ignoreCase = true) || host.isNullOrEmpty()) {
            throw UpdateException(UpdateError.INSECURE_URL, "Die Update-Quelle ist nicht sicher (nur HTTPS ist erlaubt).")
        }
    }

    /** Partial and finished files carry version and ABI, so nothing from another release is ever continued. */
    fun fileName(manifest: UpdateManifest, apk: UpdateApk): String =
        "schulranzen-${manifest.versionCode}-${apk.abi.filter { it.isLetterOrDigit() || it == '-' }}.apk"
}

object UpdateManifestParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val SHA256 = Regex("[0-9a-fA-F]{64}")

    private fun bad(what: String): Nothing = throw UpdateException(UpdateError.BAD_MANIFEST, "Die Update-Beschreibung ist fehlerhaft ($what).")

    /** [httpsOnly] is switched off only for the debug test server, see docs/UPDATE.md. */
    fun parse(text: String, httpsOnly: Boolean = true): UpdateManifest {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: IllegalArgumentException) { // includes SerializationException
            throw UpdateException(UpdateError.BAD_MANIFEST, "Die Update-Beschreibung ist nicht lesbar.", e)
        }
        fun JsonObject.str(k: String) = runCatching { this[k]?.jsonPrimitive?.contentOrNull }.getOrNull()
        val code = runCatching { root["versionCode"]?.jsonPrimitive?.longOrNull }.getOrNull()
        if (code == null || code <= 0) bad("versionCode")
        val name = root.str("versionName")?.takeIf { it.isNotBlank() } ?: bad("versionName")
        val apkObjects = runCatching { root["apks"]?.jsonObject }.getOrNull() ?: bad("apks")
        val apks = apkObjects.mapValues { (abi, v) ->
            val o = runCatching { v.jsonObject }.getOrNull() ?: bad(abi)
            val url = o.str("url")?.takeIf { it.isNotBlank() } ?: bad("$abi: url")
            if (httpsOnly) UpdatePolicy.requireHttps(url)
            val sha = o.str("sha256")?.takeIf { SHA256.matches(it) } ?: bad("$abi: sha256")
            val size = runCatching { o["size"]?.jsonPrimitive?.longOrNull }.getOrNull()?.takeIf { it > 0 }
            UpdateApk(abi, url, sha.lowercase(), size)
        }
        if (apks.isEmpty()) bad("apks")
        return UpdateManifest(code, name.trim(), root.str("changes").orEmpty().trim(), apks)
    }
}

/**
 * The network side of the updater, as plain HTTP so it runs in tests. Blocking:
 * call it on an IO thread. HttpURLConnection follows redirects only within the
 * same protocol, so GitHub's https redirects work and a step down to http fails.
 */
class UpdateClient(
    private val httpsOnly: Boolean = true,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
) {
    private fun open(url: String): HttpURLConnection {
        if (httpsOnly) UpdatePolicy.requireHttps(url)
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = readTimeoutMs
        c.useCaches = false
        c.setRequestProperty("User-Agent", "Schulranzen")
        return c
    }

    /** Only the source file, no identifiers: GitHub sees the address and that the app asked. */
    fun fetchManifest(url: String): UpdateManifest {
        val c = open(url)
        val text = try {
            val status = c.responseCode
            if (status !in 200..299) throw UpdateException(UpdateError.SERVER, "Der Update-Server antwortet nicht richtig (HTTP $status).")
            c.inputStream.use { readLimited(it) }
        } catch (e: UpdateException) {
            throw e
        } catch (e: IOException) {
            throw UpdateException(UpdateError.OFFLINE, "Keine Verbindung zum Update-Server.", e)
        } finally {
            c.disconnect()
        }
        return UpdateManifestParser.parse(text, httpsOnly)
    }

    private fun readLimited(input: InputStream): String {
        val bytes = input.readNBytes(MAX_MANIFEST + 1)
        if (bytes.size > MAX_MANIFEST) throw UpdateException(UpdateError.BAD_MANIFEST, "Die Update-Beschreibung ist zu groß.")
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Loads [apk] into [part], continuing what is already there when the server
     * allows it. [cancelled] is checked between blocks; a cancelled or broken
     * download keeps the partial file for the next try. [onProgress] gets bytes
     * done and the total (null if unknown).
     */
    fun download(apk: UpdateApk, part: File, cancelled: () -> Boolean = { false }, onProgress: (Long, Long?) -> Unit = { _, _ -> }) {
        val have = if (part.exists()) part.length() else 0L
        // Complete already (e.g. the process died before the check): nothing to request.
        if (apk.size != null && have == apk.size) { onProgress(have, apk.size); return }
        if (apk.size != null && have > apk.size) part.delete()
        val start = if (part.exists()) part.length() else 0L
        val c = open(apk.url)
        try {
            if (start > 0) c.setRequestProperty("Range", "bytes=$start-")
            val status = try { c.responseCode } catch (e: IOException) { throw offline(e) }
            val resume = when (status) {
                200 -> false // Range ignored: from the start.
                206 -> true
                416 -> { part.delete(); throw UpdateException(UpdateError.SERVER, "Der Download passte nicht mehr zusammen. Bitte noch einmal versuchen.") }
                else -> throw UpdateException(UpdateError.SERVER, "Die Datei ließ sich nicht laden (HTTP $status).")
            }
            val offset = if (resume) start else 0L
            val length = c.contentLengthLong.takeIf { it >= 0 }?.plus(offset)
            val total = apk.size ?: length
            val limit = apk.size ?: MAX_APK
            if (total != null && total > limit) throw UpdateException(UpdateError.TOO_LARGE, "Die Datei ist größer als angekündigt.")
            part.parentFile?.mkdirs()
            var done = offset
            onProgress(done, total)
            try {
                java.io.FileOutputStream(part, resume).use { out ->
                    c.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled()) throw UpdateException(UpdateError.CANCELLED, "Abgebrochen.")
                            val n = input.read(buf)
                            if (n < 0) break
                            done += n
                            if (done > limit) throw UpdateException(UpdateError.TOO_LARGE, "Die Datei ist größer als angekündigt.")
                            out.write(buf, 0, n)
                            onProgress(done, total)
                        }
                    }
                }
            } catch (e: UpdateException) {
                throw e
            } catch (e: IOException) {
                throw offline(e)
            }
            if (total != null && done < total) throw UpdateException(UpdateError.OFFLINE, "Die Verbindung brach ab. Der Download lässt sich fortsetzen.")
        } finally {
            c.disconnect()
        }
    }

    private fun offline(e: IOException) = UpdateException(UpdateError.OFFLINE, "Die Verbindung brach ab. Der Download lässt sich fortsetzen.", e)

    companion object {
        /** A manifest is a few hundred bytes. */
        private const val MAX_MANIFEST = 256 * 1024
        /** The safety net when a manifest names no size. */
        private const val MAX_APK = 400L * 1024 * 1024

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** Throws and deletes the file on a mismatch: a broken file must never be continued. */
        fun verifyChecksum(file: File, apk: UpdateApk) {
            if (sha256(file) != apk.sha256.lowercase()) {
                file.delete()
                throw UpdateException(UpdateError.BAD_CHECKSUM, "Die geladene Datei ist beschädigt oder wurde verändert. Sie wurde gelöscht.")
            }
        }
    }
}
