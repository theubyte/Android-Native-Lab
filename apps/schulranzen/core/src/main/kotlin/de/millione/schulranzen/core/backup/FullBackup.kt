package de.millione.schulranzen.core.backup

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * One folder of app data that goes into the backup under [name]
 * (e.g. "files", "databases", "shared_prefs"). [exclude] gets the path
 * relative to [dir] with "/" separators.
 */
class BackupRoot(val name: String, val dir: File, val exclude: (String) -> Boolean = { false }) {
    init { require(ZipPaths.isSafe(name) && !name.contains('/')) { "Ungültiger Wurzelname: $name" } }
}

/** What kind of file a user picked. [ENCRYPTED] needs a password first ([BackupCrypto]). */
enum class ArchiveKind { OWN, V15, ENCRYPTED, UNKNOWN }

/**
 * Full backup and restore as plain file operations, independent of Android,
 * so the whole round trip is covered by JVM tests. The Android side only
 * supplies the folders and the hooks around it.
 */
object FullBackup {
    /** Progress callback: bytes done, bytes total. */
    fun interface Progress { fun update(done: Long, total: Long) }

    /**
     * Writes all files below [roots] to [out] as ZIP, then the manifest.
     * Hashes are taken from the very bytes written, so a file that changes
     * while it is read is still described correctly. Files that vanish
     * during the run are skipped. [out] is not closed.
     */
    fun write(
        roots: List<BackupRoot>,
        out: OutputStream,
        info: BackupManifest,
        progress: Progress = Progress { _, _ -> },
    ): BackupManifest {
        val files = ArrayList<Pair<String, File>>()
        for (root in roots) collect(root, root.dir, "", files)
        files.sortBy { it.first }
        val total = files.sumOf { it.second.length() }
        var done = 0L
        val entries = ArrayList<BackupEntry>(files.size)
        val zip = ZipOutputStream(out)
        zip.setLevel(Deflater.BEST_SPEED)
        val buf = ByteArray(64 * 1024)
        for ((path, file) in files) {
            val modified = file.lastModified()
            val input = try { file.inputStream() } catch (_: FileNotFoundException) { continue }
            val md = MessageDigest.getInstance("SHA-256")
            var size = 0L
            zip.putNextEntry(ZipEntry(path).apply { time = file.lastModified() })
            DigestInputStream(input.buffered(), md).use { din ->
                while (true) {
                    val n = din.read(buf)
                    if (n < 0) break
                    zip.write(buf, 0, n)
                    size += n
                    done += n
                    progress.update(done, total)
                }
            }
            zip.closeEntry()
            entries += BackupEntry(path, size, Hashing.hex(md.digest()), modified)
        }
        val manifest = info.copy(format = BackupManifest.FORMAT, app = BackupManifest.APP_ID, entries = entries)
        zip.putNextEntry(ZipEntry(BackupManifest.FILE_NAME))
        zip.write(BackupManifest.json.encodeToString(BackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.finish()
        zip.flush()
        progress.update(total, total)
        return manifest
    }

    private fun collect(root: BackupRoot, dir: File, rel: String, out: MutableList<Pair<String, File>>) {
        val children = dir.listFiles() ?: return
        val dirCanon = runCatching { dir.canonicalPath }.getOrNull() ?: return
        for (f in children) {
            val r = if (rel.isEmpty()) f.name else "$rel/${f.name}"
            if (root.exclude(r)) continue
            // Never follow links out of the data folder.
            if (runCatching { f.canonicalFile.parent != dirCanon }.getOrDefault(true)) continue
            if (f.isDirectory) collect(root, f, r, out)
            else if (f.isFile) {
                val path = "${root.name}/$r"
                if (ZipPaths.isSafe(path)) out += path to f
            }
        }
    }

    /** Looks at a ZIP without extracting it. */
    fun detect(zip: File): ArchiveKind = if (BackupCrypto.isEncrypted(zip)) ArchiveKind.ENCRYPTED else try {
        ZipFile(zip).use { z ->
            when {
                z.getEntry(BackupManifest.FILE_NAME) != null -> ArchiveKind.OWN
                z.getEntry(BackupManifest.V15_FILE_NAME) != null -> ArchiveKind.V15
                z.entries().asSequence().any { it.name.startsWith("files/notebook-v") || it.name == "shared_prefs/library_v9.xml" } -> ArchiveKind.V15
                else -> ArchiveKind.UNKNOWN
            }
        }
    } catch (_: IOException) { ArchiveKind.UNKNOWN }

    /** Reads and checks the manifest of one of our backups. */
    fun readManifest(zip: File): BackupManifest = openZip(zip).use { z -> readManifest(z) }

    private fun readManifest(z: ZipFile): BackupManifest {
        val entry = z.getEntry(BackupManifest.FILE_NAME) ?: run {
            if (z.getEntry(BackupManifest.V15_FILE_NAME) != null) throw V15BackupException()
            throw BackupException("Keine Schulranzen-Sicherung: Inhaltsverzeichnis fehlt.")
        }
        if (entry.size > 32L * 1024 * 1024) throw BackupException("Inhaltsverzeichnis des Backups ist zu groß.")
        val text = z.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        val m = try {
            BackupManifest.json.decodeFromString(BackupManifest.serializer(), text)
        } catch (e: Exception) {
            throw BackupException("Inhaltsverzeichnis des Backups ist beschädigt.", e)
        }
        if (m.app != BackupManifest.APP_ID) throw BackupException("Dieses Backup stammt nicht aus dem Schulranzen.")
        if (m.format > BackupManifest.FORMAT) throw BackupException("Das Backup stammt aus einer neueren App-Version. Bitte zuerst die App aktualisieren.")
        val seen = HashSet<String>()
        for (e in m.entries) {
            if (!ZipPaths.isSafe(e.path) || e.path.endsWith("/")) throw BackupException("Unsicherer Pfad im Backup: ${e.path}")
            if (!seen.add(e.path)) throw BackupException("Doppelter Eintrag im Backup: ${e.path}")
            if (e.size < 0) throw BackupException("Ungültige Größe im Backup: ${e.path}")
        }
        return m
    }

    private fun openZip(zip: File): ZipFile = try {
        ZipFile(zip)
    } catch (e: ZipException) {
        throw BackupException("Die Datei ist kein gültiges ZIP-Backup.", e)
    }

    /**
     * Extracts the archive into [stage] (emptied first) and proves it: every
     * manifest entry present with the right size and SHA-256, nothing extra,
     * no path leaving the folder, only [allowedRoots] as top folders. Each
     * allowed root exists afterwards, empty if the backup had none, so the
     * swap replaces it as a whole.
     */
    fun extractVerified(zip: File, stage: File, allowedRoots: Set<String>, progress: Progress = Progress { _, _ -> }): BackupManifest {
        stage.deleteTree()
        if (!stage.mkdirs()) throw BackupException("Arbeitsordner für die Wiederherstellung konnte nicht angelegt werden.")
        openZip(zip).use { z ->
            val manifest = readManifest(z)
            val expected = manifest.entries.associateBy { it.path }
            for (e in manifest.entries) {
                if (e.path.substringBefore('/') !in allowedRoots) throw BackupException("Unerwarteter Bereich im Backup: ${e.path}")
            }
            val total = manifest.totalSize
            var done = 0L
            val got = HashSet<String>()
            val buf = ByteArray(64 * 1024)
            for (entry in z.entries()) {
                val name = entry.name
                if (name == BackupManifest.FILE_NAME) continue
                if (entry.isDirectory) continue
                val exp = expected[name] ?: throw BackupException("Backup enthält eine nicht verzeichnete Datei: $name")
                if (!got.add(name)) throw BackupException("Doppelter Eintrag im Backup: $name")
                val dest = ZipPaths.resolve(stage, name)
                dest.parentFile?.mkdirs()
                val md = MessageDigest.getInstance("SHA-256")
                var size = 0L
                z.getInputStream(entry).use { input ->
                    dest.outputStream().buffered().use { o ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            size += n
                            // Stop zip bombs early: never write more than announced.
                            if (size > exp.size) throw BackupException("Backup-Datei ist größer als angegeben: $name")
                            md.update(buf, 0, n)
                            o.write(buf, 0, n)
                            done += n
                            progress.update(done, total)
                        }
                    }
                }
                if (size != exp.size || !Hashing.hex(md.digest()).equals(exp.sha256, ignoreCase = true)) {
                    throw BackupException("Prüfsumme stimmt nicht: $name. Das Backup ist beschädigt.")
                }
                // Keep the original time: merging decides by it.
                val time = if (exp.modified > 0) exp.modified else entry.time
                if (time > 0) dest.setLastModified(time)
            }
            val missing = expected.keys - got
            if (missing.isNotEmpty()) throw BackupException("Backup unvollständig, es fehlt: ${missing.first()}")
            for (r in allowedRoots) File(stage, r).mkdirs()
            return manifest
        }
    }

    /**
     * Replaces the live folders by the staged ones. Each live folder is
     * renamed into [trash] and the staged folder renamed into its place;
     * renames in one file system are atomic, so a folder is always either
     * the old or the new version. If any step fails, everything moved so
     * far is moved back. [preserve] lists paths (e.g. "shared_prefs/x.xml")
     * copied from the live data into the stage first, so they survive.
     */
    fun swap(stage: File, targets: Map<String, File>, trash: File, preserve: List<String> = emptyList()) {
        for (p in preserve) {
            val root = p.substringBefore('/')
            val live = targets[root] ?: continue
            val src = File(live, p.substringAfter('/'))
            if (src.isFile) {
                val dst = ZipPaths.resolve(stage, p)
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
            }
        }
        trash.deleteTree()
        if (!trash.mkdirs()) throw BackupException("Arbeitsordner für die Wiederherstellung konnte nicht angelegt werden.")
        val movedOut = ArrayList<String>()
        val movedIn = ArrayList<String>()
        try {
            for ((name, live) in targets) {
                val staged = File(stage, name)
                if (!staged.isDirectory) continue
                if (live.exists()) {
                    if (!live.renameTo(File(trash, name))) throw BackupException("Alte Daten konnten nicht beiseitegelegt werden ($name).")
                    movedOut += name
                }
                live.parentFile?.mkdirs()
                if (!staged.renameTo(live)) throw BackupException("Neue Daten konnten nicht eingesetzt werden ($name).")
                movedIn += name
            }
        } catch (e: Exception) {
            // Roll back: put the old folders back where they were.
            for (name in movedIn) targets[name]?.let { live -> live.renameTo(File(stage, name)) || live.deleteTree() }
            for (name in movedOut) targets[name]?.let { live -> File(trash, name).renameTo(live) }
            throw if (e is BackupException) e else BackupException("Wiederherstellung fehlgeschlagen: ${e.message}", e)
        }
        trash.deleteTree()
        stage.deleteTree()
    }
}
