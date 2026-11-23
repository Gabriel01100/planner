package com.calenflex.app

import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class CalendarAndSyncTest {
    @Test fun monthlyGridIncludesLeadingAndTrailingDaysAndLeapDay() {
        val cells = monthCells(LocalDate.of(2028, 2, 15))
        assertEquals(42, cells.size)
        assertEquals(java.time.DayOfWeek.MONDAY, cells.first().dayOfWeek)
        assertTrue(cells.contains(LocalDate.of(2028, 2, 29)))
        assertEquals(42, cells.distinct().size)
    }
    @Test fun persistedSyncCommandSurvivesLostReplyAndUsesSameId() = runTest {
        val dao = MemoryDao()
        var firstId: String? = null
        var attempts = 0
        val api = object : FakeApi() {
            override suspend fun merge(body: MergeCommand): SyncEnvelope {
                attempts++
                if(firstId == null) firstId = body.requestId
                assertEquals(firstId, body.requestId)
                remote = SyncEnvelope(1, body.state)
                if(attempts == 1) throw java.io.IOException("Response lost after commit")
                return remote
            }
        }
        val repo = Repository(dao, FakeConnection(), api)
        repo.edit("Crear") { it.copy(activities = listOf(Activity(title="Conservar"))) }
        try { repo.sync(); fail("Expected lost reply") } catch(_: java.io.IOException) {}
        assertTrue(repo.current().dirty)
        Repository(dao, FakeConnection(), api).sync()
        assertFalse(repo.current().dirty)
        assertEquals(2, attempts)
        assertEquals(repo.current().payload, repo.current().syncBase)
    }
    @Test fun incompatibleMergeKeepsAllThreeVersionsForExport() = runTest {
        val dao = MemoryDao()
        val base = State(activities=listOf(Activity(title="Original")))
        dao.put(StoredState(payload=json.encodeToString(base), revision=1, syncBase=json.encodeToString(base)))
        val api = object : FakeApi() {
            override suspend fun merge(body: MergeCommand): SyncEnvelope {
                throw retrofit2.HttpException(retrofit2.Response.error<SyncEnvelope>(409, okhttp3.ResponseBody.create(null,"conflict")))
            }
        }
        api.remote = SyncEnvelope(2, base.copy(activities=base.activities.map { it.copy(title="Remoto") }))
        val repo = Repository(dao, FakeConnection(), api)
        repo.edit("Local") { it.copy(activities=it.activities.map { a -> a.copy(title="Local") }) }
        try { repo.sync(); fail("Expected conflict") } catch(_: retrofit2.HttpException) {}
        val backup = json.decodeFromString<SyncConflictBackup>(repo.current().conflict!!)
        assertEquals("Original",backup.base!!.activities.single().title)
        assertEquals("Local",backup.local.activities.single().title)
        assertEquals("Remoto",backup.remote.activities.single().title)
        assertTrue(repo.current().dirty)
    }
}

class StudyCompletionTest {
    @Test fun completingRecurringSessionOnlyChangesItsSelectedDay() {
        val a = Activity(title="Practicar",frequency="Diaria")
        val state = State(activities=listOf(a),schedule=listOf(Slot(a.id,"2027-01-04",540,600),Slot(a.id,"2027-01-05",540,600)))
        val result = state.withCompletedActivity(a.id,"2027-01-04")
        assertTrue(result.schedule[0].completed)
        assertFalse(result.schedule[1].completed)
        assertEquals(a.status,result.activities[0].status)
        assertFalse(state.schedule[0].completed)
    }
}
