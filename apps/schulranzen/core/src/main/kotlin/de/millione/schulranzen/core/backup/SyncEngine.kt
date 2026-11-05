package de.millione.schulranzen.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.security.DigestInputStream
import java.security.MessageDigest

/** One file of a synced state. [blob] is its name on the server, [stored] its size there. */
@Serializable
data class SyncEntry(
    val path: String,
    val size: Long,
    val sha256: String,
    val modified: Long = 0,
    val blob: String,
    val stored: Long = 0,
)

/** The list of files of a state; encrypted on the server when the user encrypts. */
@Serializable
data class SyncManifest(
    val format: Int = FORMAT,
    val created: Long = 0,
    val device: String = "",
    val entries: List<SyncEntry> = emptyList(),
) {
    companion object { const val FORMAT = 1 }
}

/** The current state on the server: its version and the (possibly encrypted) file list. */
class RemoteHead(val rev: String, val manifest: ByteArray)

/** Another device was faster, or a file changed while it was sent: merge again from the newest state. */
class SyncConflictException(message: String = "Inzwischen gibt es einen neueren Stand.") : java.io.IOException(message)

/** The server's state is encrypted and [wrong] = a password was tried, or none is known yet. */
class SyncPasswordException(val wrong: Boolean) : BackupException(
    if (wrong) "Das Passwort passt nicht zum Stand auf dem Server." else "Der Stand auf dem Server ist verschlüsselt. Bitte das Passwort eingeben.",
)

/** A participant could not merge. The local data stays good, but nothing is uploaded. */
class SyncMergeFailedException(val reports: List<MergeReport>) : BackupException(
    "Zusammenführen fehlgeschlagen (${reports.filter { !it.ok }.joinToString { it.name }}). Der Stand auf dem Server bleibt unverändert.",
)

/** What a device needs from the server; blocking, called on an IO thread. */
interface SyncRemote {
    /** Null when there is no state yet. */
    fun head(): RemoteHead?
    fun missing(ids: List<String>): List<String>
    /** [sha256] of the bytes in [file]; the server refuses other bytes. */
    fun putBlob(id: String, file: File, sha256: String, progress: (Long, Long) -> Unit)
    /** Complete or not at all. */
    fun getBlob(id: String, target: File, progress: (Long, Long) -> Unit)
    /** Replaces [expected] (null = none yet) atomically; else [SyncConflictException]. Returns the new version. */
    fun commit(expected: String?, manifest: ByteArray, blobs: List<String>, device: String): String
}

/** What "Jetzt abgleichen" did. */
data class SyncResult(
    val reports: List<MergeReport>,
    val warnings: List<String>,
    val downloadedBytes: Long,
    val uploadedBytes: Long,
    /** Nothing new on either side. */
    val upToDate: Boolean,
) {
    val added get() = reports.sumOf { it.added }
    val updated get() = reports.sumOf { it.updated }
    val removed get() = reports.sumOf { it.removed }
    val conflicts get() = reports.sumOf { it.conflicts }

    /** "3 Seiten neu, 1 Konflikt". */
    fun headline(): String = SyncText.headline(reports, upToDate)
}

object SyncText {
    /** "3 Seiten neu, 1 Konflikt" for the status line. */
    fun headline(reports: List<MergeReport>, upToDate: Boolean = false): String {
        val pages = reports.firstOrNull { it.name == NOTEBOOK_REPORT }
        val others = reports.filter { it.name != NOTEBOOK_REPORT }
        val parts = ArrayList<String>()
        pages?.let {
            if (it.added > 0) parts += if (it.added == 1) "1 Seite neu" else "${it.added} Seiten neu"
            if (it.updated > 0) parts += if (it.updated == 1) "1 Seite aktualisiert" else "${it.updated} Seiten aktualisiert"
            if (it.removed > 0) parts += if (it.removed == 1) "1 Seite gelöscht" else "${it.removed} Seiten gelöscht"
        }
        val rest = others.sumOf { it.added + it.updated }
        if (rest > 0) parts += if (rest == 1) "1 weitere Änderung" else "$rest weitere Änderungen"
        val conflicts = reports.sumOf { it.conflicts }
        if (conflicts > 0) parts += if (conflicts == 1) "1 Konflikt" else "$conflicts Konflikte"
        return when {
            parts.isNotEmpty() -> parts.joinToString(", ") + "."
            upToDate -> "Alles aktuell."
            else -> "Abgeglichen, nichts Neues von anderen Geräten."
        }
    }

    /** Name of the notebook report; the headline counts its items as pages. */
    const val NOTEBOOK_REPORT = "Hefte"
}

/** Remembers file hashes by size and time, so unchanged large PDFs are not read again every time. */
class HashCache(private val file: File) {
    @Serializable private data class Item(val size: Long, val modified: Long, val sha256: String)
    @Serializable private data class Stored(val items: Map<String, Item> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true }
    private val items = HashMap(runCatching { json.decodeFromString(Stored.serializer(), file.readText()).items }.getOrDefault(emptyMap()))
    private val seen = HashSet<String>()
    private var dirty = false

    fun sha256(f: File, key: String = f.path): String {
        seen += key
        val size = f.length()
        val modified = f.lastModified()
        items[key]?.let { if (it.size == size && it.modified == modified) return it.sha256 }
        val sha = Hashing.sha256(f)
        items[key] = Item(size, modified, sha)
        dirty = true
        return sha
    }

    /** Stores the hashes used since the last save and forgets the rest. */
    fun save() {
        if (items.keys.retainAll(seen)) dirty = true
        seen.clear()
        if (!dirty) return
        atomicWrite(file, json.encodeToString(Stored.serializer(), Stored(items)))
        dirty = false
    }
}

/**
 * One sync: fetch the server's state, merge it, send the result. All the safety
 * lives in the order of the steps:
 *
 * 1. Only files that differ from the local ones are fetched into [work]; they
 *    are checked and kept there, so a broken connection resumes.
 * 2. The participants merge. Each writes atomically and is repeatable; if one
 *    fails nothing is sent, so a state that was not merged is never replaced.
 * 3. Only files the server lacks are sent. Nothing changes on the server until
 *    the commit, which names the state the merge was based on. If another
 *    device was faster the commit is refused and the whole thing runs again.
 *
 * With a connection lost anywhere the data on both sides stays valid, and the
 * next run continues. Blocking, except for the participants. Call on an IO thread.
 */
class SyncEngine(
    private val remote: SyncRemote,
    private val roots: List<BackupRoot>,
    /** Private working folder on this device. */
    private val work: File,
    /** Survives between runs, is never backed up. */
    private val stateFile: File,
    private val hashes: HashCache,
    private val participants: List<MergeParticipant>,
    /** To open an encrypted state and to send encrypted, when the user encrypts. */
    private val password: CharArray?,
    /** Send encrypted even when the server's state is not. */
    private val encrypt: Boolean,
    private val device: String,
    /** Flushes pending writes so the files on disk are complete (database journals). */
    private val prepare: suspend () -> Unit = {},
    /** Runs the merge; the place to keep editors away from the data meanwhile. */
    private val exclusive: suspend (suspend () -> Unit) -> Unit = { it() },
    /** Step and bytes (total -1 = unknown). Throwing here cancels. */
    private val progress: (String, Long, Long) -> Unit = { _, _, _ -> },
) {
    @Serializable private data class State(val rev: String? = null, val local: String? = null)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stage = File(work, "stage")
    private val outgoing = File(work, "out")

    private var downloaded = 0L
    private var uploaded = 0L
    private val allReports = LinkedHashMap<String, MergeReport>()
    private val warnings = LinkedHashSet<String>()

    suspend fun run(): SyncResult {
        var attempt = 0
        while (true) {
            attempt++
            try {
                val upToDate = once()
                stage.deleteTree()
                outgoing.deleteTree()
                return SyncResult(allReports.values.toList(), warnings.toList(), downloaded, uploaded, upToDate)
            } catch (_: SyncConflictException) {
                if (attempt >= MAX_ATTEMPTS) throw SyncException(SyncError.SERVER, "Ein anderes Gerät gleicht gerade ab. Bitte gleich noch einmal versuchen.")
            }
        }
    }

    /** Returns true when there was nothing to do at all. */
    private suspend fun once(): Boolean {
        progress("Stand auf dem Server abfragen", -1, -1)
        val head = remote.head()
        val state = readState()
        prepare()
        progress("Änderungen suchen", -1, -1)
        val before = scan()
        hashes.save()
        if (head != null && head.rev == state.rev && digest(before) == state.local) return true

        var read: CryptoSession? = null
        var manifest: SyncManifest? = null
        if (head != null) {
            read = openSession(head.manifest)
            manifest = decode(head.manifest, read)
            work.mkdirs()
            fetch(manifest, before, read)
            exclusive { merge() }
        }

        for (p in participants) warnings += p.prepareExport()
        prepare()
        val after = scan()
        hashes.save()
        val localDigest = digest(after)
        if (head != null && localDigest == state.local) {
            // Merging changed nothing here and nothing changed here since the last
            // sync. Sending again would only make the devices take turns forever.
            writeState(State(head.rev, localDigest))
            return allReports.values.all { it.added + it.updated + it.removed + it.conflicts == 0 }
        }

        val write = writeSession(read)
        val previous = manifest?.entries.orEmpty().associateBy { it.path }
        val ids = HashMap<String, String>()
        for ((path, f) in after) ids[path] = write?.blobId(f.sha) ?: f.sha
        val missing = remote.missing(ids.values.distinct()).toSet()
        val toSend = after.entries.filter { ids.getValue(it.key) in missing }.distinctBy { ids.getValue(it.key) }
        val total = toSend.sumOf { it.value.size }
        Space.require(work, if (write != null) toSend.maxOfOrNull { it.value.size } ?: 0 else 0, "Das Hochladen")
        var sent = 0L
        for ((path, f) in toSend) {
            val base = sent
            val id = ids.getValue(path)
            progress("Hochladen", base, total)
            send(id, f, write) { s, _ -> progress("Hochladen", base + s, total) }
            sent += f.size
        }
        uploaded += sent

        val entries = after.map { (path, f) ->
            val id = ids.getValue(path)
            val known = previous[path]?.takeIf { it.blob == id }?.stored ?: 0
            SyncEntry(path, f.size, f.sha, f.modified, id, known)
        }
        val body = json.encodeToString(SyncManifest.serializer(), SyncManifest(created = System.currentTimeMillis(), device = device, entries = entries)).toByteArray(Charsets.UTF_8)
        progress("Abschließen", -1, -1)
        val rev = remote.commit(head?.rev, write?.encrypt(body) ?: body, ids.values.distinct(), device)
        // From here on the other devices can see our state.
        writeState(State(rev, localDigest))
        for (p in participants) p.committed()
        return false
    }

    private fun readState(): State = runCatching { json.decodeFromString(State.serializer(), stateFile.readText()) }.getOrDefault(State())

    private fun writeState(s: State) = atomicWrite(stateFile, json.encodeToString(State.serializer(), s))

    private class LocalFile(val file: File, val size: Long, val sha: String, val modified: Long)

    private fun scan(): Map<String, LocalFile> {
        val found = LinkedHashMap<String, LocalFile>()
        for (root in roots) walk(root, root.dir, "", found)
        return found.toSortedMap()
    }

    private fun walk(root: BackupRoot, dir: File, rel: String, out: MutableMap<String, LocalFile>) {
        val dirCanon = runCatching { dir.canonicalPath }.getOrNull() ?: return
        for (f in dir.listFiles().orEmpty()) {
            val r = if (rel.isEmpty()) f.name else "$rel/${f.name}"
            if (root.exclude(r)) continue
            if (runCatching { f.canonicalFile.parent != dirCanon }.getOrDefault(true)) continue
            when {
                f.isDirectory -> walk(root, f, r, out)
                f.isFile -> {
                    val path = "${root.name}/$r"
                    if (!ZipPaths.isSafe(path)) continue
                    try {
                        val modified = f.lastModified()
                        out[path] = LocalFile(f, f.length(), hashes.sha256(f, path), modified)
                    } catch (_: FileNotFoundException) {
                        // Vanished while looking at it.
                    }
                }
            }
        }
    }

    /** Identifies a whole local state; the times stay out, only content counts. */
    private fun digest(files: Map<String, LocalFile>): String =
        Hashing.sha256(files.entries.joinToString("\n") { "${it.key} ${it.value.sha}" }.byteInputStream())

    private fun openSession(manifest: ByteArray): CryptoSession? {
        if (!isEncrypted(manifest)) return null
        val pw = password ?: throw SyncPasswordException(wrong = false)
        return try {
            BackupCrypto.sessionFor(manifest, pw)
        } catch (_: WrongPasswordException) {
            throw SyncPasswordException(wrong = true)
        }
    }

    /** Never falls back to plain once the state is encrypted, whatever this device is set to. */
    private fun writeSession(read: CryptoSession?): CryptoSession? = when {
        read != null -> read
        encrypt -> BackupCrypto.session(password ?: throw SyncPasswordException(wrong = false))
        else -> null
    }

    private fun isEncrypted(b: ByteArray) = b.size >= BackupCrypto.MAGIC.size && b.copyOf(BackupCrypto.MAGIC.size).contentEquals(BackupCrypto.MAGIC)

    private fun decode(raw: ByteArray, session: CryptoSession?): SyncManifest {
        val text = (session?.decrypt(raw) ?: raw).toString(Charsets.UTF_8)
        val m = try {
            json.decodeFromString(SyncManifest.serializer(), text)
        } catch (e: Exception) {
            throw BackupException("Der Stand auf dem Server ist beschädigt.", e)
        }
        if (m.format > SyncManifest.FORMAT) throw BackupException("Ein anderes Gerät nutzt eine neuere App-Version. Bitte zuerst die App aktualisieren.")
        val allowed = roots.mapTo(HashSet()) { it.name }
        for (e in m.entries) {
            if (!ZipPaths.isSafe(e.path) || e.path.endsWith("/") || e.path.substringBefore('/') !in allowed) {
                throw BackupException("Unsicherer Pfad im Stand auf dem Server: ${e.path}")
            }
        }
        return m
    }

    /** Fetches what differs from here; what a broken run already fetched stays and is checked again. */
    private fun fetch(manifest: SyncManifest, local: Map<String, LocalFile>, session: CryptoSession?) {
        stage.mkdirs()
        val wanted = manifest.entries.filter { e -> always(e.path) || local[e.path]?.sha != e.sha256 }
        val wantedPaths = wanted.mapTo(HashSet()) { it.path }
        // Whatever else lies in the stage belongs to an older state.
        stage.walkBottomUp().forEach { f ->
            if (f == stage) return@forEach
            val rel = f.relativeTo(stage).invariantSeparatorsPath
            if (f.isFile && rel !in wantedPaths) f.delete()
            if (f.isDirectory && f.list().isNullOrEmpty()) f.delete()
        }
        val needed = wanted.filter { e ->
            val f = ZipPaths.resolve(stage, e.path)
            !(f.isFile && f.length() == e.size && Hashing.sha256(f) == e.sha256)
        }
        val total = needed.sumOf { if (it.stored > 0) it.stored else it.size }
        Space.require(stage, needed.sumOf { it.size } * 2, "Das Herunterladen")
        var done = 0L
        for (e in needed) {
            val base = done
            val size = if (e.stored > 0) e.stored else e.size
            progress("Herunterladen", base, total)
            receive(e, session) { got, _ -> progress("Herunterladen", base + got, total) }
            done += size
        }
        downloaded += done
        RemoteIndex.write(stage, manifest.entries.map { BackupEntry(it.path, it.size, it.sha256, it.modified) })
    }

    private fun always(path: String) = ALWAYS_FETCH.any { path.startsWith(it) }

    private fun receive(e: SyncEntry, session: CryptoSession?, onBytes: (Long, Long) -> Unit) {
        val dest = ZipPaths.resolve(stage, e.path)
        dest.parentFile?.mkdirs()
        val raw = File(work, "in-" + e.blob)
        try {
            try {
                remote.getBlob(e.blob, raw, onBytes)
            } catch (x: SyncException) {
                // Cleaned up on the server meanwhile: the state must be newer.
                if (x.error == SyncError.NOT_FOUND) throw SyncConflictException()
                throw x
            }
            val tmp = File(dest.parentFile, dest.name + ".part")
            try {
                val md = MessageDigest.getInstance("SHA-256")
                raw.inputStream().buffered().use { input ->
                    val src = session?.decrypting(input) ?: input
                    DigestInputStream(src, md).use { din -> tmp.outputStream().buffered().use { din.copyTo(it, 64 * 1024) } }
                }
                if (Hashing.hex(md.digest()) != e.sha256) throw BackupException("Eine Datei vom Server ist beschädigt: ${e.path}")
                tmp.setLastModified(e.modified)
                dest.delete()
                if (!tmp.renameTo(dest)) throw BackupException("Datei konnte nicht abgelegt werden: ${e.path}")
                if (e.modified > 0) dest.setLastModified(e.modified)
            } finally {
                tmp.delete()
            }
        } finally {
            raw.delete()
        }
    }

    private suspend fun merge() {
        val reports = BackupMerge.mergeExtracted(stage, participants) { _, name -> progress("Zusammenführen: $name", -1, -1) }
        for (r in reports) allReports[r.name] = allReports[r.name]?.let { old ->
            old.copy(
                added = old.added + r.added, updated = old.updated + r.updated, removed = old.removed + r.removed,
                conflicts = old.conflicts + r.conflicts, kept = r.kept, warnings = old.warnings + r.warnings, error = r.error,
            )
        } ?: r
        val failed = reports.filter { !it.ok }
        if (failed.isNotEmpty()) throw SyncMergeFailedException(reports)
    }

    private fun send(id: String, f: LocalFile, session: CryptoSession?, onBytes: (Long, Long) -> Unit) {
        if (session == null) {
            // The server checks the bytes against the id, so a file that changed since the scan is noticed.
            remote.putBlob(id, f.file, f.sha, onBytes)
            return
        }
        outgoing.mkdirs()
        val sealed = File(outgoing, id)
        try {
            val md = MessageDigest.getInstance("SHA-256")
            f.file.inputStream().buffered().use { input ->
                DigestInputStream(input, md).use { din -> sealed.outputStream().buffered().use { session.encrypt(din, it) } }
            }
            if (Hashing.hex(md.digest()) != f.sha) throw SyncConflictException("Eine Datei hat sich beim Senden geändert.")
            remote.putBlob(id, sealed, Hashing.sha256(sealed), onBytes)
        } catch (_: FileNotFoundException) {
            throw SyncConflictException("Eine Datei ist beim Senden verschwunden.")
        } finally {
            sealed.delete()
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 4
        /** Databases are always read from the other side, even when they look the same: participants need them. */
        val ALWAYS_FETCH = listOf("databases/", "files/sync/")
    }
}
