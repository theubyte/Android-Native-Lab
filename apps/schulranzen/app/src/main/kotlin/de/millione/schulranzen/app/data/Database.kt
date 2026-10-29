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

    @Query("SELECT * FROM notebook WHERE id = :id")
    suspend fun notebook(id: String): Notebook?

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

@Database(entities = [Subject::class, Notebook::class, Page::class], version = 1, exportSchema = true)
abstract class RanzenDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao

    companion object {
        @Volatile private var instance: RanzenDatabase? = null
        fun get(context: Context): RanzenDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, RanzenDatabase::class.java, "ranzen.db")
                .build().also { instance = it }
        }
    }
}
