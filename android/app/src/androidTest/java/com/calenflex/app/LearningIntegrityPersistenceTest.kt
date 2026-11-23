package com.calenflex.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** These tests require a device: they exercise real SQLite rollback, not the JVM fake DAO. */
class LearningIntegrityPersistenceTest {
    @Test fun failedImportRollsBackBothCurrentAndProtectedRows() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),AppDatabase::class.java).build()
        try {
            val repo = LearningRepository(db.learningDao()) { error("No network") }
            repo.createGoal(LearningGoalDto(title="Original"))
            val before = db.learningDao().get()
            val importedGoal = json.encodeToJsonElement(LearningGoalDto(title="Importada")).jsonObject
            val imported = LearningSnapshot(envelope=LearningEnvelope(memory=LearningEnvelope().memory.withRows("goals",listOf(importedGoal))))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_import BEFORE INSERT ON learning_cache WHEN NEW.id = 1 BEGIN SELECT RAISE(ABORT, 'injected disk write failure'); END")
            try { repo.importBackup(json.encodeToString(imported)); fail("Expected SQLite failure") } catch(_: android.database.sqlite.SQLiteException) {}
            assertEquals(before,db.learningDao().get()); assertNull(db.learningDao().recovery())
        } finally { db.close() }
        Unit
    }
    @Test fun recoveredMidnightSessionCommitsOnlySelectedOccurrenceAndRejectsAChangedSelection() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),AppDatabase::class.java).build()
        try {
            val gate = Mutex()
            val connection = object : ConnectionProvider {
                override suspend fun get() = Connection()
                override suspend fun save(connection: Connection) {}
            }
            val agenda = Repository(db.stateDao(),connection,mutex=gate)
            val learning = LearningRepository(db.learningDao(),gate) { error("No network") }
            val goal = LearningGoalDto(title="Funciones"); learning.createGoal(goal)
            val activity = Activity(title="Diaria",goalId=goal.id,frequency="Diaria",startDate="2027-01-04")
            agenda.edit("Preparar") { it.copy(activities=listOf(activity),schedule=listOf(Slot(activity.id,"2027-01-04",540,600),Slot(activity.id,"2027-01-05",540,600))) }
            learning.begin(activity.id,"2027-01-04")
            val recovered = LearningRepository(db.learningDao(),gate) { error("No network") }
            val coordinator = StudyCoordinator(db,agenda,recovered)
            fun body(day: String) = buildJsonObject {
                put("requestId",UUID.randomUUID().toString()); put("goalId",goal.id); put("activityId",activity.id); put("occurrenceDate",day)
                put("topic","Funciones"); put("startedAt","2027-01-04T23:30:00-03:00"); put("completedAt","2027-01-05T00:30:00-03:00"); put("confirmed",true)
            }
            val beforeAgenda = agenda.current(); val beforeLearning = db.learningDao().get()
            try { coordinator.register(body("2027-01-05"),activity.id); fail("Expected changed selection") } catch(_: IllegalArgumentException) {}
            assertEquals(beforeAgenda,agenda.current()); assertEquals(beforeLearning,db.learningDao().get())
            coordinator.register(body("2027-01-04"),activity.id)
            val state = json.decodeFromString<State>(agenda.current().payload)
            assertEquals(listOf(true,false),state.schedule.map { it.completed })
            assertEquals("2027-01-04",recovered.current().pending.last().body.text("occurrenceDate"))
            assertNull(recovered.current().active)
        } finally { db.close() }
        Unit
    }
}
