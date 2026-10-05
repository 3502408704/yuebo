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
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "quark_pan_downloads",
    indices = [Index(value = ["status"])],
)
internal data class QuarkPanDownloadEntity(
    @PrimaryKey val fid: String,
    val pdirFid: String,
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
internal interface QuarkPanDownloadDao {
    @Query("SELECT * FROM quark_pan_downloads ORDER BY CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END, createdAtMs")
    fun observeAll(): Flow<List<QuarkPanDownloadEntity>>

    @Query("SELECT * FROM quark_pan_downloads WHERE fid = :fid LIMIT 1")
    suspend fun findByFid(fid: String): QuarkPanDownloadEntity?

    @Query("SELECT * FROM quark_pan_downloads WHERE status IN (:statuses) ORDER BY createdAtMs")
    suspend fun findByStatuses(statuses: List<String>): List<QuarkPanDownloadEntity>

    @Query("SELECT * FROM quark_pan_downloads WHERE status != 'COMPLETED' AND (fid = :fid OR (name = :name AND pdirFid = :pdirFid)) LIMIT 1")
    suspend fun findUnfinishedDuplicate(
        fid: String,
        name: String,
        pdirFid: String,
    ): QuarkPanDownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: QuarkPanDownloadEntity)

    @Query("DELETE FROM quark_pan_downloads WHERE fid = :fid")
    suspend fun deleteByFid(fid: String)
}

@Database(
    entities = [QuarkPanDownloadEntity::class],
    version = 1,
    exportSchema = false,
)
internal abstract class QuarkPanDownloadDatabase : RoomDatabase() {
    abstract fun downloads(): QuarkPanDownloadDao

    companion object {
        fun create(context: Context): QuarkPanDownloadDatabase = Room.databaseBuilder(
            context.applicationContext,
            QuarkPanDownloadDatabase::class.java,
            "quark-pan-downloads.db",
        ).build()
    }
}
