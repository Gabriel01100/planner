package com.calenflex.app

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class StudyOccurrenceTest {
    private fun state(): State {
        val activity = Activity(title="Diaria",frequency="Diaria",startDate="2027-01-04")
        return State(activities=listOf(activity),schedule=listOf(Slot(activity.id,"2027-01-04",540,600),Slot(activity.id,"2027-01-05",540,600)),preferences=Preferences(timezone="America/Argentina/Buenos_Aires"))
    }
    @Test fun selectedDateSurvivesRecoveryMidnightLateRegistrationAndSync() = runTest {
        val endings = listOf("2027-01-04T23:45:00Z","2027-01-05T00:30:00Z","2027-01-05T00:30:00-03:00","2027-01-05T08:00:00-03:00","2027-01-09T08:00:00Z")
        endings.forEach { end ->
            val dao = MemoryLearningDao(); val api = LearningApiFake(); val repo = LearningRepository(dao) { api }
            val goal = LearningGoalDto(title="Funciones"); repo.createGoal(goal)
            val state = state(); val activity = state.activities.single()
            repo.begin(activity.id,"2027-01-04")
            val activePayload = dao.get()!!.payload
            val recovered = LearningRepository(dao) { api }
            val active = recovered.current().active!!
            assertEquals("2027-01-04",active.occurrenceDate)
            assertEquals(activePayload,dao.get()!!.payload)
            val body = JsonObject(progressFor(goal.id).toMutableMap().apply {
                put("activityId",JsonPrimitive(active.activityId)); put("occurrenceDate",JsonPrimitive(active.occurrenceDate))
                put("startedAt",JsonPrimitive("2027-01-04T23:30:00Z")); put("completedAt",JsonPrimitive(end))
            })
            validateStudySelection(active,body)
            val completed = state.withCompletedStudyOccurrence(active.activityId,body.text("occurrenceDate"))
            assertEquals(listOf(true,false),completed.schedule.map { it.completed })
            assertEquals("Pendiente",completed.activities.single().status)
            recovered.registerProgress(body)
            assertNull(recovered.current().active)
            assertEquals("2027-01-04",recovered.current().pending.last().body.text("occurrenceDate"))
            recovered.sync()
            assertEquals("2027-01-04",recovered.current().envelope.memory.rows("sessions").single().text("occurrenceDate"))
        }
    }
    @Test fun oldJsonLoadsButRecurringCompletionRemainsExplicitlyUnresolved() {
        val state = state(); val id = state.activities.single().id
        val old = json.decodeFromString<ActiveStudy>("""{"activityId":"$id","startedAt":"2027-01-04T23:30:00Z"}""")
        assertNull(old.occurrenceDate)
        try { state.withCompletedStudyOccurrence(id,old.occurrenceDate); fail("Ambiguous legacy session completed") } catch(e: IllegalArgumentException) { assertTrue(e.message!!.contains("antigua")) }
        assertEquals(listOf(false,false),state.schedule.map { it.completed })
        val unique = state.copy(activities=state.activities.map { it.copy(frequency="Única") },schedule=state.schedule.take(1))
        assertEquals(listOf(true),unique.withCompletedStudyOccurrence(id,null).schedule.map { it.completed })
        val postponed = unique.copy(schedule=listOf(Slot(id,"2027-01-05",540,600)))
        assertTrue(postponed.withCompletedStudyOccurrence(id,null).schedule.single().completed)
        assertTrue(postponed.withCompletedStudyOccurrence(id,"2027-01-05").schedule.single().completed)
    }
    @Test fun anotherOccurrenceCannotReplaceTheSelectedOneOrClearActive() = runTest {
        val dao = MemoryLearningDao(); val repo = LearningRepository(dao) { error("No network") }
        val goal = LearningGoalDto(title="Funciones"); repo.createGoal(goal)
        val id = UUID.randomUUID().toString(); repo.begin(id,"2027-01-04")
        val before = dao.get()
        val wrong = JsonObject(progressFor(goal.id).toMutableMap().apply { put("activityId",JsonPrimitive(id)); put("occurrenceDate",JsonPrimitive("2027-01-05")) })
        try { repo.registerProgress(wrong); fail("Expected selected occurrence conflict") } catch(_: IllegalArgumentException) {}
        assertEquals(before,dao.get())
        assertEquals("2027-01-04",repo.current().active!!.occurrenceDate)
    }
}
