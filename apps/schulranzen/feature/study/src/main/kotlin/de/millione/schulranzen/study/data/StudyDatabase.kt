package de.millione.schulranzen.study.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
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
    /**
     * Stable id of a notebook page. Pages can be reordered and moved to other notebooks,
     * so [page] alone can point at the wrong one. Null for books and for links made
     * before this existed; those fall back to [page].
     */
    val pageId: String? = null,
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
    /** Time really used; 0 in runs from before this was recorded. */
    @ColumnInfo(defaultValue = "0") val seconds: Int = 0,
    /** Time allowed; null means without a limit. */
    val limitSeconds: Int? = null,
)

/**
 * One question of a finished mock exam, kept as it was asked: the card may change
 * or go later, the history of the run should not. [cardId] finds the card again
 * for a relearning round and for the error list.
 */
@Entity(
    tableName = "mock_answer",
    foreignKeys = [
        ForeignKey(MockRun::class, ["id"], ["runId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Card::class, ["id"], ["cardId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("runId"), Index("cardId")],
)
data class MockAnswerRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val cardId: Long?,
    val position: Int,
    val topic: String,
    /** Name of a [de.millione.schulranzen.core.study.QuestionType]. */
    val type: String,
    val question: String,
    val solution: String,
    val given: String,
    /** Name of a [de.millione.schulranzen.core.study.SelfGrade]. */
    val grade: String,
)

/** Focus time of one sitting, for the statistics. */
@Entity(tableName = "focus_session", indices = [Index("epochDay")])
data class FocusSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long = System.currentTimeMillis(),
    val epochDay: Long,
    val minutes: Int,
)

/** One line of the answer log. */
data class ReviewLogRow(val cardId: Long, val deckId: Long, val epochDay: Long, val at: Long, val rating: Int)

/** A mock answer in the log; [at] is the time of its run. */
data class MockLogRow(val cardId: Long, val epochDay: Long, val at: Long, val grade: String)

data class DayMinutes(val epochDay: Long, val minutes: Int)

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

    @Query("UPDATE study_link SET targetId = :targetId, page = :page WHERE id = :id")
    suspend fun relocateLink(id: Long, targetId: String, page: Int)

    @Query("DELETE FROM study_link WHERE id = :id")
    suspend fun deleteLink(id: Long)

    // Mock exams
    @Insert
    suspend fun insertMockRun(r: MockRun): Long

    @Insert
    suspend fun insertMockAnswers(a: List<MockAnswerRow>)

    @Query("SELECT * FROM mock_answer WHERE runId = :runId ORDER BY position")
    suspend fun mockAnswers(runId: Long): List<MockAnswerRow>

    @Query("SELECT * FROM mock_run ORDER BY at DESC")
    fun allMockRuns(): Flow<List<MockRun>>

    // Logs for plan, error list and statistics
    @Query("SELECT r.cardId AS cardId, c.deckId AS deckId, r.epochDay AS epochDay, r.at AS at, r.rating AS rating FROM review r JOIN card c ON c.id = r.cardId")
    fun reviewLog(): Flow<List<ReviewLogRow>>

    @Query("SELECT a.cardId AS cardId, 0 AS epochDay, m.at AS at, a.grade AS grade FROM mock_answer a JOIN mock_run m ON m.id = a.runId WHERE a.cardId IS NOT NULL")
    fun mockLog(): Flow<List<MockLogRow>>

    @Query("SELECT * FROM card")
    fun allCards(): Flow<List<Card>>

    @Query("SELECT * FROM card WHERE id IN (:ids)")
    suspend fun cardsById(ids: List<Long>): List<Card>

    @Query("SELECT front FROM card WHERE deckId = :deckId")
    suspend fun frontsOf(deckId: Long): List<String>

    @Query("SELECT * FROM card WHERE deckId = :deckId ORDER BY createdAt")
    suspend fun cardsOfDeck(deckId: Long): List<Card>

    @Query(
        """SELECT d.id, d.name, d.colorIndex, d.subjectId,
           COUNT(c.id) AS total,
           COALESCE(SUM(CASE WHEN c.dueEpochDay <= :today THEN 1 ELSE 0 END), 0) AS due,
           COALESCE(SUM(CASE WHEN c.lastReviewEpochDay IS NULL THEN 1 ELSE 0 END), 0) AS fresh
           FROM deck d LEFT JOIN card c ON c.deckId = d.id
           GROUP BY d.id ORDER BY d.name COLLATE NOCASE"""
    )
    suspend fun deckCountsNow(today: Long): List<DeckCounts>

    @Query("SELECT COUNT(*) FROM card WHERE dueEpochDay <= :today AND lastReviewEpochDay IS NOT NULL")
    suspend fun dueReviewsNow(today: Long): Int

    @Query("SELECT COUNT(*) FROM card WHERE lastReviewEpochDay IS NULL")
    suspend fun freshNow(): Int

    @Query("SELECT COUNT(*) FROM review WHERE epochDay = :day")
    suspend fun reviewsOnNow(day: Long): Int

    @Query("SELECT * FROM exam WHERE dateEpochDay >= :today ORDER BY dateEpochDay, id")
    suspend fun examsFromNow(today: Long): List<Exam>

    @Query("SELECT * FROM study_link WHERE examId = :examId ORDER BY createdAt")
    suspend fun linksNow(examId: Long): List<StudyLink>

    @Query("SELECT * FROM mock_run WHERE examId = :examId ORDER BY at DESC")
    suspend fun mockRunsNow(examId: Long): List<MockRun>

    // Focus
    @Insert
    suspend fun insertFocus(f: FocusSession): Long

    @Query("SELECT epochDay, SUM(minutes) AS minutes FROM focus_session GROUP BY epochDay")
    fun focusByDay(): Flow<List<DayMinutes>>

    @Query("SELECT * FROM mock_run WHERE examId = :examId ORDER BY at DESC")
    fun mockRuns(examId: Long): Flow<List<MockRun>>
}

@Database(
    entities = [
        PeriodRow::class, Lesson::class, SettingRow::class, Exam::class, Deck::class, Card::class, Review::class,
        StudyLink::class, MockRun::class, MockAnswerRow::class, FocusSession::class,
    ],
    version = 3,
    exportSchema = true,
    // 1 → 2 only adds the tables for exam links and mock exams.
    // 2 → 3 only adds the mock answers, the focus log, two columns on mock_run and pageId on study_link.
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
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
