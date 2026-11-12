package at.mikag.lavender.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY hour, minute, id")
    fun observeAll(): Flow<List<Alarm>>

    @Query("SELECT * FROM alarms ORDER BY hour, minute, id")
    suspend fun getAll(): List<Alarm>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun get(id: Long): Alarm?

    @Query("SELECT * FROM alarms WHERE id = :id")
    fun observe(id: Long): Flow<Alarm?>

    @Insert
    suspend fun insert(alarm: Alarm): Long

    @Update
    suspend fun update(alarm: Alarm)

    @Delete
    suspend fun delete(alarm: Alarm)
}

@Database(entities = [Alarm::class], version = 2, exportSchema = true)
abstract class AlarmDatabase : RoomDatabase() {
    abstract fun alarms(): AlarmDao

    companion object {
        /**
         * Lives in device-protected storage, so alarms can be read and ring after a reboot
         * even before the phone is unlocked for the first time.
         */
        fun create(context: Context): AlarmDatabase {
            val storage = context.createDeviceProtectedStorageContext()
            return Room.databaseBuilder(storage, AlarmDatabase::class.java, "alarms.db")
                .addMigrations(MIGRATION_1_2)
                .build()
        }

        /** 0.5.0: shuffle and wake-up playlists. Existing alarms keep everything else. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alarms ADD COLUMN shuffle INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE alarms ADD COLUMN wakeupPlaylistId TEXT")
                db.execSQL("ALTER TABLE alarms ADD COLUMN wakeupPreparedAt INTEGER")
                db.execSQL("ALTER TABLE alarms ADD COLUMN wakeupTrackCount INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
