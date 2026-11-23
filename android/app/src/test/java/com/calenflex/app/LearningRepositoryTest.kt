package com.calenflex.app

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MemoryLearningDao : LearningDao {
    val stored = MutableStateFlow<LearningStored?>(null)
    override fun observe(): Flow<LearningStored?> = stored
    override suspend fun get() = stored.value
    var previous: LearningStored? = null
    override suspend fun recovery() = previous
    override suspend fun discardRecovery() { previous = null }
    override suspend fun put(value: LearningStored) { if(value.id == 2) previous = value else stored.value = value }
}
class LearningApiFake : FakeApi() {
    var remoteLearning = LearningEnvelope()
    var writes = 0
    var failOnce = false
    var failProgressOnce = false
    var learningCalls = 0
    var failLearningCall: Int? = null
    override suspend fun learning(): LearningEnvelope {
        learningCalls++
        if(learningCalls == failLearningCall) throw java.io.IOException("network failed during learning pull")
        return remoteLearning
    }
    override suspend fun learningInsights() = buildJsonObject { put("items", JsonArray(emptyList())) }
    override suspend fun createLearningGoal(body: JsonObject): MutationDto {
        val old = remoteLearning.memory.rows("goals").any { it.text("id") == body.text("id") }
        if(!old) { remoteLearning = remoteLearning.copy(revision = remoteLearning.revision + 1, memory = remoteLearning.memory.withRows("goals", remoteLearning.memory.rows("goals") + body)); writes++ }
        if(failOnce) { failOnce = false; throw java.io.IOException("network failed after commit") }
        return MutationDto(remoteLearning.revision, body.text("id"), old, "Saved")
    }
    override suspend fun correctLearning(body: JsonObject): MutationDto {
        remoteLearning = LearningEnvelope(remoteLearning.revision + 1,body.getValue("memory").jsonObject)
        writes++
        return MutationDto(remoteLearning.revision,UUID.randomUUID().toString(),false,"Restored")
    }
    override suspend fun registerLearningProgress(body: JsonObject): MutationDto {
        val id = body.text("requestId")
        val old = remoteLearning.memory.rows("sessions").any { it.text("id") == id }
        if(!old) { remoteLearning = remoteLearning.copy(revision = remoteLearning.revision + 1, memory = remoteLearning.memory.withRows("sessions", remoteLearning.memory.rows("sessions") + buildJsonObject { put("id", id); put("goalId", body.text("goalId")); put("completedAt", body["completedAt"]!!); body["occurrenceDate"]?.let { put("occurrenceDate", it) }; body["activityId"]?.let { put("activityId", it) }; put("actualMinutes", body["actualMinutes"] ?: JsonNull); put("status", "completed") })); writes++ }
        if(failProgressOnce) { failProgressOnce = false; throw java.io.IOException("network failed after progress commit") }
        return MutationDto(remoteLearning.revision, id, old, "Saved")
    }
}
class LearningRepositoryTest {
    @Test fun queuedGoalsSurviveRepositoryRecreationAndNetworkFailure() = runTest {
        val dao = MemoryLearningDao(); val api = LearningApiFake()
        val first = LearningRepository(dao) { api }
        first.createGoal(LearningGoalDto(title = "Python"))
        val reopened = LearningRepository(dao) { api }
        assertEquals("Python", reopened.current().goals().single().title)
        api.failOnce = true
        try { reopened.sync(); fail("Expected network error") } catch(e: java.io.IOException) {}
        assertEquals(1, reopened.current().pending.size)
        reopened.sync()
        assertTrue(reopened.current().pending.isEmpty())
        assertEquals(1, api.writes)
        assertEquals(1, reopened.current().goals().size)
    }
    @Test fun progressIsQueuedAfterGoalAndSyncsWithoutInventingUnknownDuration() = runTest {
        val dao = MemoryLearningDao(); val api = LearningApiFake(); val repo = LearningRepository(dao) { api }
        val goal = LearningGoalDto(title = "Python")
        repo.createGoal(goal)
        val body = buildJsonObject { put("requestId", UUID.randomUUID().toString()); put("goalId", goal.id); put("topic", "Listas"); put("completedAt", "2026-11-23T12:00:00Z"); put("confirmed", true); put("actualMinutes", JsonNull) }
        repo.registerProgress(body)
        repo.registerProgress(body)
        assertEquals(2, repo.current().pending.size)
        repo.sync()
        assertEquals(2, api.writes)
        assertNull(repo.current().envelope.memory.rows("sessions").single().number("actualMinutes"))
    }
    @Test fun activeSessionAndUnknownGoalAreHandledWithoutDataLoss() = runTest {
        val repo = LearningRepository(MemoryLearningDao()) { error("No network") }
        repo.begin("activity")
        assertEquals("activity", repo.current().active?.activityId)
        try { repo.begin("other"); fail("Expected active session") } catch(e: IllegalArgumentException) {}
        try { repo.registerProgress(buildJsonObject { put("goalId", "missing"); put("topic", "A") }); fail("Expected missing goal") } catch(e: IllegalArgumentException) {}
        assertEquals("activity", repo.current().active?.activityId)
    }
}

class LearningBackupTest {
    @Test fun importingBackupPreservesPendingRecordsAndRequiresExplicitRecovery() = runTest {
        val sourceDao = MemoryLearningDao(); val api = LearningApiFake(); val source = LearningRepository(sourceDao) { api }
        source.createGoal(LearningGoalDto(title = "Copiado sin conexión"))
        val backup = json.encodeToString(source.current())
        val restored = LearningRepository(MemoryLearningDao()) { api }
        restored.importBackup(backup)
        assertEquals(1, restored.current().pending.size)
        assertTrue(restored.current().restorePending)
        try { restored.sync(); fail("Expected explicit recovery") } catch(e: IllegalArgumentException) {}
        restored.resumeImportedBackup()
        restored.sync()
        assertEquals("Copiado sin conexión", restored.current().goals().single().title)
        assertTrue(restored.current().pending.isEmpty())
    }
    @Test fun remoteRegressionDoesNotEraseLocalLearning() = runTest {
        val dao = MemoryLearningDao(); val api = LearningApiFake(); val repo = LearningRepository(dao) { api }
        repo.createGoal(LearningGoalDto(title = "Importante")); repo.sync()
        api.remoteLearning = LearningEnvelope()
        val before = repo.current()
        try { repo.sync(); fail("Expected regression guard") } catch(e: IllegalArgumentException) {}
        assertEquals(before, repo.current())
    }
}
