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

@Database(entities = [Alarm::class], version = 1, exportSchema = true)
abstract class AlarmDatabase : RoomDatabase() {
    abstract fun alarms(): AlarmDao

    companion object {
        /**
         * Lives in device-protected storage, so alarms can be read and ring after a reboot
         * even before the phone is unlocked for the first time.
         */
        fun create(context: Context): AlarmDatabase {
            val storage = context.createDeviceProtectedStorageContext()
            return Room.databaseBuilder(storage, AlarmDatabase::class.java, "alarms.db").build()
        }
    }
}
