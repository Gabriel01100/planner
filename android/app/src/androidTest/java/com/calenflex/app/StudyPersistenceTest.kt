package com.calenflex.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

class StudyPersistenceTest {
    @Test fun coordinatedCompletionSerializesEditsAndRollsBackBothDocuments() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            val gate = Mutex()
            val connection = object : ConnectionProvider {
                override suspend fun get() = Connection()
                override suspend fun save(connection: Connection) {}
            }
            val agenda = Repository(db.stateDao(),connection,mutex=gate)
            val learning = LearningRepository(db.learningDao(),gate) { error("Network not used") }
            val coordinator = StudyCoordinator(db,agenda,learning)
            val goal = LearningGoalDto(title="Practicar")
            learning.createGoal(goal)
            val a = Activity(title="Funciones",goalId=goal.id,startDate="2027-01-04")
            agenda.edit("Crear") { it.copy(activities=listOf(a)) }
            learning.begin(a.id)
            fun progress() = buildJsonObject {
                put("requestId",UUID.randomUUID().toString());put("goalId",goal.id);put("activityId",a.id)
                put("topic","Funciones");put("completedAt","2027-01-04T15:00:00Z");put("confirmed",true)
            }
            withTimeout(10_000) {
                coroutineScope {
                    val edit = async { repeat(5) { index -> agenda.edit("Nombre") { it.copy(preferences=it.preferences.copy(name="Nombre $index")) } } }
                    val complete = async { coordinator.register(progress(),a.id) }
                    edit.await();complete.await()
                }
            }
            assertEquals("Completada",json.decodeFromString<State>(agenda.current().payload).activities.single().status)
            assertEquals(1,learning.current().envelope.memory.rows("sessions").size)
            learning.begin(a.id)
            val before = learning.current()
            try { coordinator.register(progress(),UUID.randomUUID().toString()); fail("Expected invalid activity") } catch(_: IllegalArgumentException) {}
            assertEquals(before,learning.current())
        } finally { db.close() }
        Unit
    }
}
