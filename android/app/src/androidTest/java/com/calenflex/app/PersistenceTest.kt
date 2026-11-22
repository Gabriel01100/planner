package com.calenflex.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    @Test fun roomPersistsStateAndPendingSyncAcrossReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "test-${java.util.UUID.randomUUID()}.db"
        val activity = Activity(title = "Actividad sin conexión")
        val state = State(activities = listOf(activity))
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        db.stateDao().put(StoredState(payload = json.encodeToString(state), revision = 4, dirty = true))
        db.close()
        val reopened = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val row = reopened.stateDao().get()!!
        assertTrue(row.dirty)
        assertEquals(4, row.revision)
        assertEquals(state, json.decodeFromString<State>(row.payload))
        reopened.close()
        context.deleteDatabase(name)
        Unit
    }
}
