package de.millione.schulranzen.study.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import de.millione.schulranzen.core.study.CardSchedule
import kotlinx.coroutines.flow.Flow

/** A period of the school day; [number] is the one printed in the timetable. */
@Entity(tableName = "period")
data class PeriodRow(
    @PrimaryKey val number: Int,
    val startMinute: Int,
    val endMinute: Int,
)

/**
 * One lesson in the weekly grid. The subject is stored by name and colour so
 * the timetable works on its own; [subjectId] optionally links it to a
 * subject of the app's library.
 */
@Entity(tableName = "lesson", indices = [Index(value = ["day", "period"])])
data class Lesson(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 1 = Monday … 6 = Saturday (ISO day of week). */
    val day: Int,
    val period: Int,
    /** Name of a [de.millione.schulranzen.core.study.WeekCycle]. */
    val cycle: String = "EVERY",
    val subject: String,
    val colorIndex: Int,
    val subjectId: Long? = null,
    val room: String = "",
    val teacher: String = "",
    val note: String = "",
)

/** Small key-value store for timetable options; one row per option. */
@Entity(tableName = "setting")
data class SettingRow(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "exam", indices = [Index("dateEpochDay")])
data class Exam(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subject: String,
    val colorIndex: Int,
    val subjectId: Long? = null,
    val dateEpochDay: Long,
    /** Name of an [de.millione.schulranzen.study.ExamKind]. */
    val kind: String,
    /** Topics and free notes, one per line. */
    val topics: String = "",
    /** Sources such as "Fachkunde S. 112–118", one per line. */
    val sources: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "deck")
data class Deck(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorIndex: Int,
    val subjectId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "card",
    foreignKeys = [ForeignKey(Deck::class, ["id"], ["deckId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("deckId"), Index("dueEpochDay")],
)
data class Card(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deckId: Long,
    val front: String,
    val back: String,
    /** Reserved for pictures (cut-outs from notebooks or books), relative to filesDir/study. */
    val frontImage: String? = null,
    val backImage: String? = null,
    val reps: Int = 0,
    val intervalDays: Int = 0,
    val ease: Double = 2.5,
    val lapses: Int = 0,
    val dueEpochDay: Long,
    val lastReviewEpochDay: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val schedule: CardSchedule
        get() = CardSchedule(reps, intervalDays, ease, lapses, dueEpochDay, lastReviewEpochDay)

    fun with(s: CardSchedule) = copy(
        reps = s.reps, intervalDays = s.intervalDays, ease = s.ease, lapses = s.lapses,
        dueEpochDay = s.dueEpochDay, lastReviewEpochDay = s.lastReviewEpochDay,
    )
}

/** Review log: drives "gelernt heute" and the streak, and lets a rating be undone. */
@Entity(
    tableName = "review",
    foreignKeys = [ForeignKey(Card::class, ["id"], ["cardId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("cardId"), Index("epochDay")],
)
data class Review(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val epochDay: Long,
    /** Ordinal of [de.millione.schulranzen.core.study.Rating]. */
    val rating: Int,
    val at: Long = System.currentTimeMillis(),
)

/**
 * A place in a notebook or book marked as exam material ("Prüfungsstoff"),
 * or kept on the bookmark list when [examId] is null. [kind] is a
 * [de.millione.schulranzen.study.StudyLinkKind] name; [targetId] the
 * notebook or book id of the host app; [page] 0-based. The optional
 * rectangle is in page fractions (0..1) so it survives zoom and rendering
 * size. [doneAt] marks the material as worked through (learning path).
 */
@Entity(
    tableName = "study_link",
    foreignKeys = [ForeignKey(Exam::class, ["id"], ["examId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("examId")],
)
data class StudyLink(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val examId: Long?,
    val kind: String,
    val targetId: String,
    val page: Int,
    val rectX: Float? = null,
    val rectY: Float? = null,
    val rectW: Float? = null,
    val rectH: Float? = null,
    val label: String,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val doneAt: Long? = null,
) {
    val linkKind: de.millione.schulranzen.study.StudyLinkKind get() = de.millione.schulranzen.study.StudyLinkKind.of(kind)

    /** The marked area in page fractions, or null for the whole page. */
    val rect: de.millione.schulranzen.study.LinkRect?
        get() = if (rectX != null && rectY != null && rectW != null && rectH != null) de.millione.schulranzen.study.LinkRect(rectX, rectY, rectW, rectH) else null
}

/** A finished mock exam; the best result drives the learning path. */
@Entity(
    tableName = "mock_run",
    foreignKeys = [ForeignKey(Exam::class, ["id"], ["examId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("examId")],
)
data class MockRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val examId: Long,
    val at: Long = System.currentTimeMillis(),
    val asked: Int,
    val points: Double,
    val percent: Double,
    val grade: Int,
    /** Weak topics, one per line, weakest first. */
    val weakTopics: String = "",
)

/** Card counts of one deck for the learning path. */
data class DeckProgress(val total: Int, val seen: Int, val solid: Int, val due: Int)

/** Deck with its card counts for a given day. */
data class DeckCounts(
    val id: Long,
    val name: String,
    val colorIndex: Int,
    val subjectId: Long?,
    val total: Int,
    val due: Int,
    val fresh: Int,
)

@Dao
internal interface StudyDao {
    // Timetable
    @Query("SELECT * FROM period ORDER BY number")
    fun periods(): Flow<List<PeriodRow>>

    @Query("SELECT COUNT(*) FROM period")
    suspend fun periodCount(): Int

    @Query("DELETE FROM period")
    suspend fun clearPeriods()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeriods(p: List<PeriodRow>)

    @Query("SELECT * FROM lesson ORDER BY day, period")
    fun lessons(): Flow<List<Lesson>>

    @Upsert
    suspend fun upsertLesson(l: Lesson): Long

    @Query("DELETE FROM lesson WHERE id = :id")
    suspend fun deleteLesson(id: Long)

    @Query("DELETE FROM lesson WHERE period > :maxPeriod")
    suspend fun deleteLessonsAfter(maxPeriod: Int)

    @Query("SELECT * FROM setting")
    fun settings(): Flow<List<SettingRow>>

    @Upsert
    suspend fun putSetting(s: SettingRow)

    // Exams
    @Query("SELECT * FROM exam ORDER BY dateEpochDay, id")
    fun exams(): Flow<List<Exam>>

    @Query("SELECT * FROM exam WHERE dateEpochDay >= :today ORDER BY dateEpochDay, id")
    fun examsFrom(today: Long): Flow<List<Exam>>

    @Query("SELECT * FROM exam WHERE dateEpochDay > :today AND (subject = :subject COLLATE NOCASE OR (:subjectId IS NOT NULL AND subjectId = :subjectId)) ORDER BY dateEpochDay LIMIT 1")
    suspend fun nextExamFor(subject: String, subjectId: Long?, today: Long): Exam?

    @Query("SELECT * FROM exam WHERE id = :id")
    suspend fun exam(id: Long): Exam?

    @Upsert
    suspend fun upsertExam(e: Exam): Long

    @Query("DELETE FROM exam WHERE id = :id")
    suspend fun deleteExam(id: Long)

    // Decks and cards
    @Query(
        """SELECT d.id, d.name, d.colorIndex, d.subjectId,
           COUNT(c.id) AS total,
           COALESCE(SUM(CASE WHEN c.dueEpochDay <= :today THEN 1 ELSE 0 END), 0) AS due,
           COALESCE(SUM(CASE WHEN c.lastReviewEpochDay IS NULL THEN 1 ELSE 0 END), 0) AS fresh
           FROM deck d LEFT JOIN card c ON c.deckId = d.id
           GROUP BY d.id ORDER BY d.name COLLATE NOCASE"""
    )
    fun deckCounts(today: Long): Flow<List<DeckCounts>>

    @Query("SELECT * FROM deck WHERE id = :id")
    suspend fun deck(id: Long): Deck?

    @Query("SELECT * FROM deck WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun deckByName(name: String): Deck?

    @Query("SELECT COUNT(*) FROM deck")
    suspend fun deckCount(): Int

    @Upsert
    suspend fun upsertDeck(d: Deck): Long

    @Query("DELETE FROM deck WHERE id = :id")
    suspend fun deleteDeck(id: Long)

    @Query("SELECT * FROM card WHERE deckId = :deckId ORDER BY createdAt")
    fun cards(deckId: Long): Flow<List<Card>>

    @Query("SELECT * FROM card WHERE dueEpochDay <= :today AND (:deckId IS NULL OR deckId = :deckId)")
    suspend fun dueCards(today: Long, deckId: Long?): List<Card>

    @Query("SELECT COUNT(*) FROM card WHERE dueEpochDay <= :today")
    fun dueCount(today: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM card")
    fun cardCount(): Flow<Int>

    @Query("SELECT * FROM card WHERE id = :id")
    suspend fun card(id: Long): Card?

    @Insert
    suspend fun insertCard(c: Card): Long

    @Update
    suspend fun updateCard(c: Card)

    @Delete
    suspend fun deleteCard(c: Card)

    @Insert
    suspend fun insertReview(r: Review): Long

    @Query("DELETE FROM review WHERE id = :id")
    suspend fun deleteReview(id: Long)

    @Query("SELECT DISTINCT epochDay FROM review WHERE epochDay > :since")
    fun reviewDays(since: Long): Flow<List<Long>>

    @Query("SELECT COUNT(*) FROM review WHERE epochDay = :day")
    fun reviewsOn(day: Long): Flow<Int>

    // Cards, more
    @Query("SELECT * FROM card WHERE deckId IN (:deckIds)")
    suspend fun cardsOf(deckIds: List<Long>): List<Card>

    @Query(
        """SELECT COUNT(*) AS total,
           COALESCE(SUM(CASE WHEN lastReviewEpochDay IS NOT NULL THEN 1 ELSE 0 END), 0) AS seen,
           COALESCE(SUM(CASE WHEN reps >= 2 THEN 1 ELSE 0 END), 0) AS solid,
           COALESCE(SUM(CASE WHEN dueEpochDay <= :today THEN 1 ELSE 0 END), 0) AS due
           FROM card WHERE deckId = :deckId"""
    )
    fun deckProgress(deckId: Long, today: Long): Flow<DeckProgress>

    @Query("UPDATE card SET dueEpochDay = :today WHERE id IN (:ids) AND dueEpochDay > :today")
    suspend fun pullForward(ids: List<Long>, today: Long)

    @Query("SELECT frontImage FROM card WHERE deckId = :deckId AND frontImage IS NOT NULL UNION ALL SELECT backImage FROM card WHERE deckId = :deckId AND backImage IS NOT NULL")
    suspend fun imagesOfDeck(deckId: Long): List<String>

    // Links
    @Query("SELECT * FROM study_link WHERE examId = :examId ORDER BY createdAt")
    fun links(examId: Long): Flow<List<StudyLink>>

    @Query("SELECT * FROM study_link WHERE examId IS NULL ORDER BY createdAt DESC")
    fun bookmarks(): Flow<List<StudyLink>>

    @Query("SELECT * FROM study_link ORDER BY createdAt DESC")
    fun allLinks(): Flow<List<StudyLink>>

    @Query("SELECT * FROM study_link WHERE kind = :kind AND targetId = :targetId ORDER BY page, createdAt")
    fun linksOn(kind: String, targetId: String): Flow<List<StudyLink>>

    @Insert
    suspend fun insertLink(l: StudyLink): Long

    @Update
    suspend fun updateLink(l: StudyLink)

    @Query("DELETE FROM study_link WHERE id = :id")
    suspend fun deleteLink(id: Long)

    // Mock exams
    @Insert
    suspend fun insertMockRun(r: MockRun): Long

    @Query("SELECT * FROM mock_run WHERE examId = :examId ORDER BY at DESC")
    fun mockRuns(examId: Long): Flow<List<MockRun>>
}

@Database(
    entities = [
        PeriodRow::class, Lesson::class, SettingRow::class, Exam::class, Deck::class, Card::class, Review::class,
        StudyLink::class, MockRun::class,
    ],
    version = 2,
    exportSchema = true,
    // 1 → 2 only adds the tables for exam links and mock exams.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
internal abstract class StudyDatabase : RoomDatabase() {
    abstract fun dao(): StudyDao

    companion object {
        @Volatile private var instance: StudyDatabase? = null

        /** One database per process, however many repositories are made. */
        fun get(context: Context): StudyDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, StudyDatabase::class.java, "study.db")
                .build().also { instance = it }
        }
    }
}
