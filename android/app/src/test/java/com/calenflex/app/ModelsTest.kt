package com.calenflex.app

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ModelsTest {
    @Test fun backupRoundTripPreservesRecurrencesAndCompletedHistory() {
        val a = Activity(title = "Inglés", frequency = "Semanal", skippedDates = listOf("2027-02-01"))
        val state = State(activities = listOf(a), schedule = listOf(Slot(a.id, a.startDate, 540, 600, true)), preferences = Preferences(name = "Gabriel"))
        val restored = json.decodeFromString<State>(json.encodeToString(state))
        validateState(restored)
        assertEquals(state, restored)
        assertTrue(restored.schedule.single().completed)
    }
    @Test fun duplicatedActivitiesAreRejected() {
        val a = Activity(title = "Estudiar")
        assertThrows(IllegalArgumentException::class.java) { validateState(State(activities = listOf(a, a))) }
    }
    @Test fun invalidDeadlineIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { validateState(State(activities = listOf(Activity(title = "A", startDate = "2027-01-02", deadline = "2027-01-01")))) }
    }
    @Test fun invalidWindowIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { validateState(State(availability = listOf(Window(start = 600, end = 500)))) }
    }
    @Test fun orphanScheduleIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { validateState(State(schedule = listOf(Slot(java.util.UUID.randomUUID().toString(), "2027-01-01", 540, 600)))) }
    }
    @Test fun timeParserChecksMinutesAndMidnight() {
        assertEquals(1440, parseTime("24:00", true))
        assertEquals(570, parseTime("09:30"))
        assertThrows(IllegalArgumentException::class.java) { parseTime("12:70") }
        assertThrows(IllegalArgumentException::class.java) { parseTime("24:00") }
    }
}
