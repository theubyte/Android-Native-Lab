package de.millione.schulranzen.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "subject")
data class Subject(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Index into the design module's SubjectPalette. */
    val colorIndex: Int,
    val position: Int,
)

@Entity(tableName = "notebook", indices = [Index("subjectId"), Index("updatedAt")])
data class Notebook(
    @PrimaryKey val id: String,
    val title: String,
    val subjectId: Long?,
    /** Default ruling for new pages. */
    val paper: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastPage: Int = 0,
    val pageCount: Int = 1,
    val trashedAt: Long? = null,
)

@Entity(tableName = "page", indices = [Index(value = ["notebookId", "position"])])
data class Page(
    @PrimaryKey val id: String,
    val notebookId: String,
    val position: Int,
    val paper: String,
    val bookmarked: Boolean = false,
    val locked: Boolean = false,
    /** Worksheet picture (file in the image folder) or null for ruled paper. */
    val background: String? = null,
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM subject ORDER BY position")
    fun subjects(): Flow<List<Subject>>

    @Query("SELECT COUNT(*) FROM subject")
    suspend fun subjectCount(): Int

    @Insert
    suspend fun insertSubject(s: Subject): Long

    @Insert
    suspend fun insertSubjects(s: List<Subject>)

    @Query("SELECT * FROM notebook WHERE trashedAt IS NULL ORDER BY updatedAt DESC")
    fun notebooks(): Flow<List<Notebook>>

    @Query("SELECT * FROM notebook WHERE trashedAt IS NOT NULL ORDER BY trashedAt DESC")
    fun trashed(): Flow<List<Notebook>>

    @Query("DELETE FROM notebook WHERE id = :id")
    suspend fun deleteNotebookRow(id: String)

    @Query("DELETE FROM page WHERE notebookId = :id")
    suspend fun deletePagesOf(id: String)

    @Transaction
    suspend fun deleteNotebook(id: String) {
        deletePagesOf(id)
        deleteNotebookRow(id)
    }

    @Query("SELECT * FROM notebook WHERE id = :id")
    suspend fun notebook(id: String): Notebook?

    @Query("SELECT * FROM notebook")
    suspend fun allNotebooks(): List<Notebook>

    @Query("SELECT * FROM subject")
    suspend fun allSubjects(): List<Subject>

    /** Replaces a notebook with all its pages (merging a backup from another device). */
    @Transaction
    suspend fun replaceNotebook(n: Notebook, pages: List<Page>) {
        deleteNotebook(n.id)
        insertNotebook(n)
        pages.forEach { insertPage(it) }
        recount(n.id)
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNotebook(n: Notebook)

    @Update
    suspend fun updateNotebook(n: Notebook)

    @Query("UPDATE notebook SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    @Query("UPDATE notebook SET lastPage = :page WHERE id = :id")
    suspend fun setLastPage(id: String, page: Int)

    @Query("SELECT * FROM page WHERE notebookId = :notebookId ORDER BY position")
    suspend fun pages(notebookId: String): List<Page>

    @Insert
    suspend fun insertPage(p: Page)

    @Query("UPDATE page SET position = position + 1 WHERE notebookId = :notebookId AND position >= :from")
    suspend fun shiftPages(notebookId: String, from: Int)

    @Query("UPDATE notebook SET pageCount = (SELECT COUNT(*) FROM page WHERE notebookId = :id) WHERE id = :id")
    suspend fun recount(id: String)

    @Query("UPDATE page SET paper = :paper WHERE id = :id")
    suspend fun setPaper(id: String, paper: String)

    @Query("UPDATE page SET bookmarked = :on WHERE id = :id")
    suspend fun setBookmarked(id: String, on: Boolean)

    @Query("UPDATE page SET locked = :on WHERE id = :id")
    suspend fun setLocked(id: String, on: Boolean)

    @Query("DELETE FROM page WHERE id = :id")
    suspend fun deletePage(id: String)

    @Query("UPDATE page SET position = :position WHERE id = :id")
    suspend fun setPosition(id: String, position: Int)

    /** Writes the page order as given. */
    @Transaction
    suspend fun reorder(notebookId: String, ids: List<String>) {
        ids.forEachIndexed { i, id -> setPosition(id, i) }
        recount(notebookId)
    }

    @Transaction
    suspend fun removePage(notebookId: String, id: String, remaining: List<String>) {
        deletePage(id)
        reorder(notebookId, remaining)
    }

    @Transaction
    suspend fun createNotebook(n: Notebook, first: Page) {
        insertNotebook(n)
        insertPage(first)
    }

    @Transaction
    suspend fun insertPageAt(p: Page) {
        shiftPages(p.notebookId, p.position)
        insertPage(p)
        recount(p.notebookId)
    }
}

@Database(entities = [Subject::class, Notebook::class, Page::class], version = 3, exportSchema = true)
abstract class RanzenDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao

    companion object {
        /** 0.2: bookmarks and locked pages. */
        val V1_TO_V2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE page ADD COLUMN bookmarked INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE page ADD COLUMN locked INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 0.3: worksheet pages. */
        val V2_TO_V3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE page ADD COLUMN background TEXT")
            }
        }

        @Volatile private var instance: RanzenDatabase? = null
        fun get(context: Context): RanzenDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, RanzenDatabase::class.java, "ranzen.db")
                .addMigrations(V1_TO_V2, V2_TO_V3)
                .build().also { instance = it }
        }
    }
}
