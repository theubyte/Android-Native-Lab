package de.millione.schulranzen.study

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import de.millione.schulranzen.core.backup.MergeParticipant
import de.millione.schulranzen.core.backup.MergeReport
import de.millione.schulranzen.study.data.StudyDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Merges study.db from a backup of another device into this one. The tables
 * use counters as ids, which differ between devices, so rows are matched by
 * what they mean instead:
 *
 * - deck: name; exam: subject + date + kind; lesson: day + period + cycle
 * - card: deck + front text + front picture. The card that was reviewed
 *   later (then: more often) keeps its schedule.
 * - review, mock run: their parent + time stamp; marked place: target + page + label
 *
 * Nothing is deleted: what exists only here stays.
 */
class StudyMergeParticipant(private val context: Context) : MergeParticipant {
    override val name = "Lernen"

    override suspend fun merge(backupDir: File): MergeReport = withContext(Dispatchers.IO) {
        val file = File(backupDir, "databases/study.db")
        if (!file.exists()) return@withContext MergeReport(name, warnings = listOf("Keine Lerndaten im Backup"))
        val remote = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        val local = StudyDatabase.get(context).openHelper.writableDatabase
        var added = 0; var updated = 0; var kept = 0
        try {
            local.beginTransaction()
            try {
                // Decks and exams first: cards, links and mock runs point at them.
                val deckIds = mapRows(remote, local, "deck", key = { it.str("name").lowercase() }) { added++ }
                val examIds = mapRows(remote, local, "exam", key = { "${it.str("subject").lowercase()}|${it["dateEpochDay"]}|${it["kind"]}" }) { added++ }
                mapRows(remote, local, "lesson", key = { "${it["day"]}|${it["period"]}|${it["cycle"]}" }) { added++ }
                remote.rows("SELECT * FROM period").forEach { r ->
                    if (local.count("SELECT COUNT(*) FROM period WHERE number = ?", r["number"]) == 0) { local.insertRow("period", r); added++ }
                }

                // Cards: insert new ones, take the schedule of the more recently learnt copy.
                val cardIds = HashMap<Long, Long>()
                val images = File(context.filesDir, "study/images").also { it.mkdirs() }
                for (r in remote.rows("SELECT * FROM card")) {
                    val deck = deckIds[(r["deckId"] as Long)] ?: continue
                    val match = local.rows(
                        "SELECT * FROM card WHERE deckId = ? AND front = ? AND IFNULL(frontImage, '') = ?",
                        deck, r.str("front"), r["frontImage"]?.toString() ?: "",
                    ).firstOrNull()
                    listOf("frontImage", "backImage").forEach { col -> (r[col] as? String)?.let { copyIn(File(backupDir, "files/study/$it"), File(context.filesDir, "study/$it"), images) } }
                    if (match == null) {
                        cardIds[r["id"] as Long] = local.insertRow("card", r.without("id") + ("deckId" to deck)); added++
                    } else {
                        cardIds[r["id"] as Long] = match["id"] as Long
                        if (newer(r, match)) {
                            local.update("card", SQLiteDatabase.CONFLICT_NONE, values(r.pick("reps", "intervalDays", "ease", "lapses", "dueEpochDay", "lastReviewEpochDay", "back", "backImage")), "id = ?", arrayOf(match["id"]))
                            updated++
                        } else kept++
                    }
                }
                for (r in remote.rows("SELECT * FROM review")) {
                    val card = cardIds[r["cardId"] as Long] ?: continue
                    if (local.count("SELECT COUNT(*) FROM review WHERE cardId = ? AND at = ?", card, r["at"]) == 0) local.insertRow("review", r.without("id") + ("cardId" to card))
                }
                for (r in remote.rows("SELECT * FROM study_link")) {
                    val exam = (r["examId"] as? Long)?.let { examIds[it] }
                    val same = local.rows("SELECT * FROM study_link WHERE kind = ? AND targetId = ? AND page = ? AND label = ?", r["kind"], r["targetId"], r["page"], r["label"]).firstOrNull()
                    if (same == null) { local.insertRow("study_link", r.without("id") + ("examId" to exam)); added++ }
                    else if (same["doneAt"] == null && r["doneAt"] != null) { local.update("study_link", SQLiteDatabase.CONFLICT_NONE, values(r.pick("doneAt")), "id = ?", arrayOf(same["id"])); updated++ }
                }
                for (r in remote.rows("SELECT * FROM mock_run")) {
                    val exam = examIds[r["examId"] as Long] ?: continue
                    if (local.count("SELECT COUNT(*) FROM mock_run WHERE examId = ? AND at = ?", exam, r["at"]) == 0) local.insertRow("mock_run", r.without("id") + ("examId" to exam))
                }
                local.setTransactionSuccessful()
            } finally {
                local.endTransaction()
            }
        } finally {
            remote.close()
        }
        MergeReport(name, added = added, updated = updated, kept = kept)
    }

    /**
     * Rows of [table] matched by [key]; missing ones are inserted with a new
     * id. Returns remote id → local id.
     */
    private fun mapRows(remote: SQLiteDatabase, local: SupportSQLiteDatabase, table: String, key: (Map<String, Any?>) -> String, onAdd: () -> Unit): Map<Long, Long> {
        val here = local.rows("SELECT * FROM $table").associateBy(key)
        val out = HashMap<Long, Long>()
        for (r in remote.rows("SELECT * FROM $table")) {
            val match = here[key(r)]
            out[r["id"] as Long] = if (match != null) match["id"] as Long else local.insertRow(table, r.without("id")).also { onAdd() }
        }
        return out
    }

    /** Was the remote card learnt more recently (or as recently, but more often)? */
    private fun newer(r: Map<String, Any?>, l: Map<String, Any?>): Boolean {
        val rd = r["lastReviewEpochDay"] as? Long ?: -1; val ld = l["lastReviewEpochDay"] as? Long ?: -1
        return rd > ld || (rd == ld && (r["reps"] as Long) > (l["reps"] as Long))
    }

    private fun copyIn(src: File, dst: File, dir: File) {
        if (!src.exists() || dst.exists() || !dst.canonicalPath.startsWith(dir.parentFile!!.canonicalPath)) return
        dst.parentFile?.mkdirs()
        val tmp = File(dst.parentFile, dst.name + ".tmp")
        src.copyTo(tmp, overwrite = true)
        tmp.renameTo(dst)
    }
}

private fun Cursor.toMap(): Map<String, Any?> = (0 until columnCount).associate { i ->
    columnNames[i] to when (getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
        Cursor.FIELD_TYPE_BLOB -> getBlob(i)
        else -> getString(i)
    }
}

private fun SQLiteDatabase.rows(sql: String, vararg args: Any?): List<Map<String, Any?>> =
    rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(c.toMap()) } }

private fun SupportSQLiteDatabase.rows(sql: String, vararg args: Any?): List<Map<String, Any?>> =
    query(sql, args).use { c -> buildList { while (c.moveToNext()) add(c.toMap()) } }

private fun SupportSQLiteDatabase.count(sql: String, vararg args: Any?): Int =
    query(sql, args).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

private fun SupportSQLiteDatabase.insertRow(table: String, row: Map<String, Any?>): Long =
    insert(table, SQLiteDatabase.CONFLICT_IGNORE, values(row))

private fun values(row: Map<String, Any?>) = ContentValues().apply {
    row.forEach { (k, v) ->
        when (v) {
            null -> putNull(k)
            is Long -> put(k, v)
            is Double -> put(k, v)
            is ByteArray -> put(k, v)
            else -> put(k, v.toString())
        }
    }
}

private fun Map<String, Any?>.str(k: String) = this[k]?.toString().orEmpty()
private fun Map<String, Any?>.without(k: String) = this - k
private fun Map<String, Any?>.pick(vararg k: String) = filterKeys { it in k }
