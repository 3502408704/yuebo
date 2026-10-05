package com.example.local_music_player

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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "baidu_pan_downloads",
    indices = [Index(value = ["status"])],
)
internal data class BaiduPanDownloadEntity(
    @PrimaryKey val fsId: Long,
    val path: String,
    val name: String,
    val size: Long,
    val downloadedBytes: Long,
    val status: String,
    val error: String?,
    val mediaStoreUri: String?,
    val createdAtMs: Long,
    val completedAtMs: Long?,
    val subPath: String = "",
)

@Dao
internal interface BaiduPanDownloadDao {
    @Query("SELECT * FROM baidu_pan_downloads ORDER BY CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END, createdAtMs")
    fun observeAll(): Flow<List<BaiduPanDownloadEntity>>

    @Query("SELECT * FROM baidu_pan_downloads WHERE fsId = :fsId LIMIT 1")
    suspend fun findByFsId(fsId: Long): BaiduPanDownloadEntity?

    @Query("SELECT * FROM baidu_pan_downloads WHERE status IN (:statuses) ORDER BY createdAtMs")
    suspend fun findByStatuses(statuses: List<String>): List<BaiduPanDownloadEntity>

    @Query("SELECT * FROM baidu_pan_downloads WHERE status != 'COMPLETED' AND (fsId = :fsId OR (name = :name AND path = :path)) LIMIT 1")
    suspend fun findUnfinishedDuplicate(
        fsId: Long,
        name: String,
        path: String,
    ): BaiduPanDownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: BaiduPanDownloadEntity)

    @Query("DELETE FROM baidu_pan_downloads WHERE fsId = :fsId")
    suspend fun deleteByFsId(fsId: Long)
}

@Database(
    entities = [BaiduPanDownloadEntity::class],
    version = 2,
    exportSchema = false,
)
internal abstract class BaiduPanDownloadDatabase : RoomDatabase() {
    abstract fun downloads(): BaiduPanDownloadDao

    companion object {
        fun create(context: Context): BaiduPanDownloadDatabase = Room.databaseBuilder(
            context.applicationContext,
            BaiduPanDownloadDatabase::class.java,
            "baidu-pan-downloads.db",
        ).addMigrations(MIGRATION_1_2).build()

        /** v2：新增 subPath 列，保存文件夹下载时文件的目标子目录（单文件下载为空）。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE baidu_pan_downloads ADD COLUMN subPath TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
