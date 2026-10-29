package de.millione.schulranzen.core.backup

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Writes test data exactly like the old Java app (NotebookView.writePages,
 * LibraryStore, StudyStore, BookAnnotationStore, BackupManager), so the
 * importer is checked against the real byte layout.
 */
object V15Fixture {
    class Pt(val x: Float, val y: Float, val p: Float = 1f, val t: Float = 0f)
    class St(val color: Int, val tool: Int, val width: Float, val pts: List<Pt>)
    class Tx(val x: Float, val y: Float, val size: Float, val color: Int, val text: String)
    class Im(val path: String, val x: Float, val y: Float, val w: Float, val h: Float, val anchored: Boolean = false)
    class Pg(
        val bookmark: Boolean = false, val locked: Boolean = false, val paper: Int = 0, val title: String = "", val bg: String = "",
        val strokes: List<St> = emptyList(), val texts: List<Tx> = emptyList(), val images: List<Im> = emptyList(),
    )

    /** NotebookView.writeState with SB15 (or SB14) magic. */
    fun notebookV15(pages: List<Pg>, trash: List<Pg> = emptyList(), magic: Int = V15Notebook.MAGIC_V15): ByteArray = bytes { d ->
        d.writeInt(magic); d.writeInt(pages.size); d.writeInt(0)
        writePages(d, pages, pressure = true, images = true)
        d.writeInt(trash.size); writePages(d, trash, pressure = true, images = true)
    }

    fun notebookV13(pages: List<Pg>): ByteArray = bytes { d ->
        d.writeInt(V15Notebook.MAGIC_V13); d.writeInt(pages.size); d.writeInt(0)
        writePages(d, pages, pressure = false, images = true); d.writeInt(0)
    }

    fun notebookV9(pages: List<Pg>): ByteArray = bytes { d ->
        d.writeInt(V15Notebook.MAGIC_V9); d.writeInt(pages.size); d.writeInt(0)
        writePages(d, pages, pressure = false, images = false); d.writeInt(0)
    }

    fun notebookV8(pages: List<Pg>): ByteArray = bytes { d ->
        d.writeInt(V15Notebook.MAGIC_V8); d.writeInt(pages.size); d.writeInt(0)
        for (p in pages) { d.writeBoolean(p.bookmark); d.writeInt(p.paper); legacyStrokes(d, p.strokes) }
    }

    fun notebookV7(pages: List<Pg>): ByteArray = bytes { d ->
        d.writeInt(V15Notebook.MAGIC_V7); d.writeInt(pages.size)
        for (p in pages) { d.writeBoolean(p.bookmark); d.writeInt(p.paper); legacyStrokes(d, p.strokes) }
    }

    fun notebookV3(strokes: List<St>): ByteArray = bytes { d -> legacyStrokes(d, strokes) }

    private fun legacyStrokes(d: DataOutputStream, strokes: List<St>) {
        d.writeInt(strokes.size)
        for (s in strokes) { d.writeInt(s.color); d.writeInt(s.tool); d.writeInt(s.pts.size); for (p in s.pts) { d.writeFloat(p.x); d.writeFloat(p.y) } }
    }

    private fun writePages(d: DataOutputStream, list: List<Pg>, pressure: Boolean, images: Boolean) {
        for (pg in list) {
            d.writeBoolean(pg.bookmark); d.writeBoolean(pg.locked); d.writeInt(pg.paper); d.writeUTF(pg.title); d.writeUTF(pg.bg)
            d.writeInt(pg.strokes.size)
            for (st in pg.strokes) {
                d.writeInt(st.color); d.writeInt(st.tool); d.writeFloat(st.width); d.writeInt(st.pts.size)
                for (p in st.pts) { d.writeFloat(p.x); d.writeFloat(p.y); if (pressure) { d.writeFloat(p.p); d.writeFloat(p.t) } }
            }
            d.writeInt(pg.texts.size)
            for (t in pg.texts) { d.writeFloat(t.x); d.writeFloat(t.y); d.writeFloat(t.size); d.writeInt(t.color); d.writeUTF(t.text) }
            if (images) {
                d.writeInt(pg.images.size)
                for (im in pg.images) { d.writeUTF(im.path); d.writeFloat(im.x); d.writeFloat(im.y); d.writeFloat(im.w); d.writeFloat(im.h); d.writeBoolean(im.anchored) }
            }
        }
    }

    private fun bytes(block: (DataOutputStream) -> Unit): ByteArray {
        val bo = ByteArrayOutputStream()
        DataOutputStream(bo).use(block)
        return bo.toByteArray()
    }

    /** Android SharedPreferences XML with escaped string values. */
    fun prefs(values: Map<String, Any>): ByteArray {
        val sb = StringBuilder("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n")
        for ((k, v) in values) when (v) {
            is String -> sb.append("    <string name=\"").append(esc(k)).append("\">").append(esc(v)).append("</string>\n")
            is Int -> sb.append("    <int name=\"$k\" value=\"$v\" />\n")
            is Long -> sb.append("    <long name=\"$k\" value=\"$v\" />\n")
            is Boolean -> sb.append("    <boolean name=\"$k\" value=\"$v\" />\n")
        }
        return sb.append("</map>\n").toString().toByteArray(Charsets.UTF_8)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** A V15 BackupManager ZIP: files and prefs plus backup-manifest.json (optional). */
    fun zip(target: File, entries: Map<String, ByteArray>, manifest: Boolean = true, tamper: String? = null) {
        ZipOutputStream(target.outputStream()).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(if (name == tamper) data.copyOf().also { it[0] = (it[0] + 1).toByte() } else data)
                z.closeEntry()
            }
            if (manifest) {
                val list = entries.entries.joinToString(",") { (n, d) ->
                    "{\"path\":\"$n\",\"size\":${d.size},\"sha256\":\"${sha(d)}\"}"
                }
                z.putNextEntry(ZipEntry("backup-manifest.json"))
                z.write("{\"format\":15,\"created\":1700000000000,\"app\":\"Digitaler Schulranzen\",\"entries\":[$list]}".toByteArray())
                z.closeEntry()
            }
        }
    }

    fun sha(d: ByteArray): String = Hashing.hex(MessageDigest.getInstance("SHA-256").digest(d))
}

/** Runs a suspend block that never really suspends (fake sinks). */
fun <T> blocking(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return result!!.getOrThrow()
}
