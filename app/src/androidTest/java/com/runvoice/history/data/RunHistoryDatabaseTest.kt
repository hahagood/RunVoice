package com.runvoice.history.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.runvoice.history.model.RunArchiveStatus
import com.runvoice.history.model.RunRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunHistoryDatabaseTest {
    private lateinit var database: RunHistoryDatabase
    private lateinit var dao: RunHistoryDao

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RunHistoryDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.runHistoryDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun insertQueryAndDelete_roundTripsRecord() = runBlocking {
        val record = RunRecord(
            id = "run-1000-2000",
            startedAtEpochMillis = 1_000L,
            finishedAtEpochMillis = 2_000L,
            elapsedSeconds = 60L,
            distanceMeters = 250f,
            averagePaceSecondsPerKm = 240,
            maxHeartRateBpm = 170,
            traceLocalPath = "/private/trace.csv",
            tracePublicReference = "content://trace",
            posterReference = "content://poster",
            archiveStatus = RunArchiveStatus.Complete,
            createdAtEpochMillis = 3_000L
        )

        dao.upsert(record.toEntity())

        assertEquals(record, dao.getById(record.id)?.toModel())
        assertEquals(listOf(record), dao.observeBetween(1_500L, 2_500L).first().map { it.toModel() })
        assertEquals(1, dao.deleteById(record.id))
        assertNull(dao.getById(record.id))
    }
}
