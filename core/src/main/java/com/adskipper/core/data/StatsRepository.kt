package com.adskipper.core.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "skip_records")
data class SkipRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val timestamp: Long,
    val layer: String,
    val elapsedMs: Long,
)

data class LayerCount(val layer: String, val cnt: Int)
data class PackageCount(val packageName: String, val cnt: Int)

@Dao
interface SkipRecordDao {
    @Insert
    suspend fun insert(record: SkipRecord)

    @Query("SELECT COUNT(*) FROM skip_records")
    fun totalCount(): Flow<Int>

    @Query("SELECT layer, COUNT(*) AS cnt FROM skip_records GROUP BY layer")
    fun countByLayer(): Flow<List<LayerCount>>

    @Query("SELECT packageName, COUNT(*) AS cnt FROM skip_records GROUP BY packageName ORDER BY cnt DESC")
    fun countByPackage(): Flow<List<PackageCount>>

    @Query("SELECT * FROM skip_records ORDER BY timestamp DESC LIMIT 20")
    fun recent(): Flow<List<SkipRecord>>
}

@Database(entities = [SkipRecord::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun skipRecordDao(): SkipRecordDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "adskipper.db"
            ).build().also { instance = it }
        }
    }
}

class StatsRepository(context: Context) {
    private val dao = AppDatabase.get(context).skipRecordDao()

    val totalCount = dao.totalCount()
    val countByLayer = dao.countByLayer()
    val countByPackage = dao.countByPackage()
    val recent = dao.recent()

    suspend fun record(packageName: String, layer: String, elapsedMs: Long) {
        dao.insert(SkipRecord(
            packageName = packageName,
            timestamp = System.currentTimeMillis(),
            layer = layer,
            elapsedMs = elapsedMs,
        ))
    }
}
