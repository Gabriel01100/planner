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

@RunWith(AndroidJUnit4::class)
class LearningMigrationTest {
    @Test fun agendaV1SurvivesMigrationToLearningCacheV2() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migration-${java.util.UUID.randomUUID()}.db"
        val original = json.encodeToString(State(activities = listOf(Activity(title = "Dato anterior"))))
        context.openOrCreateDatabase(name, 0, null).use { sqlite ->
            sqlite.execSQL("CREATE TABLE workspace (id INTEGER NOT NULL PRIMARY KEY, payload TEXT NOT NULL, revision INTEGER NOT NULL, dirty INTEGER NOT NULL)")
            sqlite.execSQL("INSERT INTO workspace VALUES (1, ?, 7, 1)", arrayOf(original))
            sqlite.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            sqlite.execSQL("INSERT INTO room_master_table VALUES (42, '0969621f53b0312c451b8e6c4ddf8c23')")
            sqlite.version = 1
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
        val row = migrated.stateDao().get()!!
        assertEquals(original, row.payload)
        assertEquals(7, row.revision)
        assertTrue(row.dirty)
        assertNull(migrated.learningDao().get())
        migrated.learningDao().put(LearningStored(payload = json.encodeToString(LearningSnapshot())))
        assertNotNull(migrated.learningDao().get())
        migrated.close()
        context.deleteDatabase(name)
        Unit
    }
}

@RunWith(AndroidJUnit4::class)
class SharedInputValidationTest {
    @Test fun receivesExplicitTextAndRejectsFilePaths() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val text = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, "Contenido explícito")
        assertEquals("Contenido explícito", readSharedIntent(context, text)?.text)
        val privatePath = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.parse("file:///data/private.txt"))
        assertThrows(IllegalArgumentException::class.java) { readSharedIntent(context, privatePath) }
        val tooLarge = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, "x".repeat(20001))
        assertThrows(IllegalArgumentException::class.java) { readSharedIntent(context, tooLarge) }
    }
}
