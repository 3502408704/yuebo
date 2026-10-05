package com.example.local_music_player

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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "download_tasks",
    indices = [Index(value = ["status", "createdAtMs"])],
)
data class DownloadTaskEntity(
    @PrimaryKey val id: String,
    val platform: String,
    val query: String,
    val sequence: Int,
    val platformId: String?,
    val title: String,
    val artist: String,
    val album: String,
    val folderName: String? = null,
    val trackNo: Int? = null,
    val lyrics: String? = null,
    val mvId: String? = null,
    val artworkUrl: String?,
    val requestedQuality: String,
    val mimeType: String,
    val extension: String,
    val mediaStoreUri: String?,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val etag: String?,
    val status: String,
    val error: String?,
    val retryCount: Int,
    val nextRetryAtMs: Long?,
    val createdAtMs: Long,
    val completedAtMs: Long?,
)

@Dao
interface DownloadTaskDao {
    @Query("SELECT * FROM download_tasks ORDER BY CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END, createdAtMs")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE status IN (:statuses) ORDER BY createdAtMs")
    suspend fun findByStatuses(statuses: List<String>): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE platform = :platform AND query = :query AND sequence = :sequence AND platformId IS :platformId AND status != 'COMPLETED' LIMIT 1")
    suspend fun findUnfinishedOnlineTask(platform: String, query: String, sequence: Int, platformId: String?): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE platform = :platform AND query = :query AND sequence = :sequence AND platformId IS :platformId AND status = 'COMPLETED' LIMIT 1")
    suspend fun findCompletedOnlineTask(platform: String, query: String, sequence: Int, platformId: String?): DownloadTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: DownloadTaskEntity)

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Database(entities = [DownloadTaskEntity::class], version = 5, exportSchema = false)
abstract class DownloadTaskDatabase : RoomDatabase() {
    abstract fun tasks(): DownloadTaskDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN folderName TEXT")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN trackNo INTEGER")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN lyrics TEXT")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN mvId TEXT")
            }
        }

        fun create(context: Context): DownloadTaskDatabase = Room.databaseBuilder(
            context.applicationContext,
            DownloadTaskDatabase::class.java,
            "download-tasks.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
    }
}
