package com.runvoice.history.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [RunRecordEntity::class],
    version = 1,
    exportSchema = true
)
abstract class RunHistoryDatabase : RoomDatabase() {
    abstract fun runHistoryDao(): RunHistoryDao

    companion object {
        private const val DATABASE_NAME = "run-history.db"

        @Volatile
        private var instance: RunHistoryDatabase? = null

        fun getInstance(context: Context): RunHistoryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                RunHistoryDatabase::class.java,
                DATABASE_NAME
            ).build().also { instance = it }
        }
    }
}
