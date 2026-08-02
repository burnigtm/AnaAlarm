package com.anaalarm.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [AlarmEntity::class, MessageEntity::class, DailyLogEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AnaDatabase : RoomDatabase() {

    abstract fun alarmDao(): AlarmDao
    abstract fun messageDao(): MessageDao
    abstract fun dailyLogDao(): DailyLogDao

    companion object {
        @Volatile
        private var instance: AnaDatabase? = null

        fun get(context: Context): AnaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AnaDatabase::class.java,
                    "anaalarm.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }

        /** Visible for tests — clears the process singleton. */
        internal fun clearInstance() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
