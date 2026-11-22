package com.calenflex.app

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class MemoryDao : StateDao {
    val data = MutableStateFlow<StoredState?>(null)
    override fun observe(): Flow<StoredState?> = data
    override suspend fun get() = data.value
    override suspend fun put(value: StoredState) { data.value = value }
}
class FakeConnection : ConnectionProvider {
    override suspend fun get() = Connection()
    override suspend fun save(connection: Connection) {}
}
class FakeApi : Api {
    var remote = SyncEnvelope(0, State())
    override suspend fun pull() = remote
    override suspend fun push(body: SyncEnvelope): SyncEnvelope {
        check(body.revision == remote.revision) { "Conflict" }
        remote = body.copy(revision = body.revision + 1)
        return remote
    }
    override suspend fun plan(body: PlanRequest): PlanResult = error("unused")
    override suspend fun ai(body: AIRequest): AIResponse = error("unused")
}
class RepositoryTest {
    @Test fun localWritesStayAvailableBeforeSync() = runTest {
        val dao = MemoryDao(); val repo = Repository(dao, FakeConnection())
        val a = Activity(title = "Estudiar")
        repo.edit("Crear") { it.copy(activities = listOf(a)) }
        assertTrue(repo.current().dirty)
        assertEquals(a, repo.state.first().activities.single())
    }
    @Test fun conflictPreservesLocalAndRemote() = runTest {
        val dao = MemoryDao(); val api = FakeApi(); val repo = Repository(dao, FakeConnection(), api)
        repo.edit("Local") { it.copy(activities = listOf(Activity(title = "Local"))) }
        api.remote = SyncEnvelope(2, State(activities = listOf(Activity(title = "Remota"))))
        val before = repo.current()
        try { repo.sync(); fail("Expected conflict") } catch(e: IllegalStateException) { }
        assertEquals(before, repo.current())
        assertEquals("Remota", api.remote.state.activities.single().title)
    }
    @Test fun successfulSyncAcknowledgesDirtyVersion() = runTest {
        val repo = Repository(MemoryDao(), FakeConnection(), FakeApi())
        repo.edit("Crear") { it.copy(activities = listOf(Activity(title = "A"))) }
        repo.sync()
        assertFalse(repo.current().dirty)
        assertEquals(1, repo.current().revision)
    }
    @Test fun stalePreviewCannotOverwriteRecentEdit() = runTest {
        val repo = Repository(MemoryDao(), FakeConnection())
        val base = repo.current().payload
        repo.edit("Cambio") { it.copy(preferences = it.preferences.copy(name = "Gabriel")) }
        try { repo.applyPlan(base, PlanResult(emptyList(), emptyList(), emptyList(), emptyList())); fail("Expected stale preview") } catch(e: IllegalArgumentException) { }
        assertEquals("Gabriel", repo.state.first().preferences.name)
    }
    @Test fun invalidWriteDoesNotReplacePersistedData() = runTest {
        val repo = Repository(MemoryDao(), FakeConnection())
        repo.edit("Inicial") { it.copy(activities = listOf(Activity(title = "Correcta"))) }
        val before = repo.current()
        try { repo.edit("Inválida") { it.copy(activities = listOf(Activity(title = ""))) }; fail("Expected validation") } catch(e: IllegalArgumentException) { }
        assertEquals(before, repo.current())
    }
}

class AvailabilityTest {
    @Test fun freeTimeMergesOverlappingAvailabilityAndSubtractsOccupiedTime() {
        val day = java.time.LocalDate.of(2027, 1, 4)
        val a = Activity(title = "A", startDate = day.toString())
        val state = State(activities = listOf(a), availability = listOf(Window(start = 540, end = 720), Window(start = 600, end = 780)),
            schedule = listOf(Slot(a.id, day.toString(), 600, 660)))
        assertEquals(listOf(540 to 600, 660 to 780), freeWindows(state, day))
    }
    @Test fun outsideAvailabilityNeverBecomesFreeTime() {
        val day = java.time.LocalDate.of(2027, 1, 4)
        assertEquals(emptyList<Pair<Int, Int>>(), freeWindows(State(availability = emptyList()), day))
    }
}
