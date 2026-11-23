package com.calenflex.app

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

internal fun learningFixture(): LearningSnapshot {
    val ids = (1..10).map { "00000000-0000-4000-8000-${it.toString().padStart(12,'0')}" }
    val (g,t,s,e,source) = ids
    val memory = json.parseToJsonElement("""{
        "schemaVersion":1,
        "goals":[{"id":"$g","title":"Python"}],
        "topics":[{"id":"$t","goalId":"$g","title":"Funciones"}],
        "sessions":[{"id":"$s","goalId":"$g","topicId":"$t","completedAt":"2027-01-05T01:00:00Z","actualMinutes":30,"status":"completed"}],
        "events":[{"id":"$e","goalId":"$g","topicId":"$t","sessionId":"$s","description":"Avance","occurredAt":"2027-01-05T01:00:00Z","sourceReferenceId":"$source","evidenceType":"confirmed"}],
        "sources":[{"id":"$source","importedAt":"2027-01-05T01:00:00Z","contentDigest":"${"a".repeat(64)}"}],
        "milestones":[{"id":"${ids[5]}","goalId":"$g","title":"Primer hito"}],
        "competencies":[{"id":"${ids[6]}","topicId":"$t","estimatedLevel":0.5,"confidence":0.5,"evidenceIds":["$e"],"assessmentMethod":"Ejercicios"}],
        "nextSteps":[{"id":"${ids[7]}","goalId":"$g","topicId":"$t","description":"Practicar"}],
        "receipts":[{"requestId":"${ids[8]}","goalId":"$g","entityId":"$s","fingerprint":"${"b".repeat(64)}"}]
    }""").jsonObject
    return LearningSnapshot(envelope = LearningEnvelope(7,memory))
}
private fun LearningSnapshot.change(group: String, change: (JsonObject) -> JsonObject): LearningSnapshot =
    copy(envelope = envelope.copy(memory = envelope.memory.withRows(group,envelope.memory.rows(group).map(change))))
private fun JsonObject.changed(key: String, value: JsonElement): JsonObject = JsonObject(toMutableMap().apply { put(key,value) })
internal fun progressFor(goal: String, request: String = UUID.randomUUID().toString()): JsonObject = buildJsonObject {
    put("requestId",request); put("goalId",goal); put("topic","Funciones"); put("completedAt","2027-01-05T01:00:00Z"); put("actualMinutes",30); put("confirmed",true)
}

class LearningIntegrityTest {
    @Test fun invalidBackupsPreserveTheEntireExistingPayloadAndRecovery() = runTest {
        val valid = learningFixture()
        val variants = listOf(
            valid.change("sessions") { it.changed("id",JsonPrimitive("bad-uuid")) },
            valid.change("sessions") { it.changed("goalId",JsonPrimitive(UUID.randomUUID().toString())) },
            valid.change("sessions") { JsonObject(it - "completedAt") },
            valid.change("sessions") { it.changed("startedAt",JsonPrimitive("2027-01-06T01:00:00Z")) },
            valid.change("sessions") { it.changed("status",JsonPrimitive("invented")) },
            valid.change("sources") { it.changed("importedAt",JsonPrimitive("yesterday")) },
            valid.change("events") { it.changed("sourceReferenceId",JsonPrimitive(UUID.randomUUID().toString())) },
            valid.change("topics") { it.changed("parentTopicId",it["id"]!!) },
            valid.change("milestones") { it.changed("goalId",JsonPrimitive(UUID.randomUUID().toString())) },
            valid.change("competencies") { it.changed("evidenceIds",buildJsonArray { add(UUID.randomUUID().toString()) }) },
            valid.change("nextSteps") { it.changed("dependencies",buildJsonArray { add(it["id"]!!) }) },
            valid.change("receipts") { it.changed("entityId",JsonPrimitive(UUID.randomUUID().toString())) },
            valid.copy(envelope = valid.envelope.copy(memory = valid.envelope.memory.changed("events",buildJsonArray { add("not-an-object") }))),
            valid.copy(envelope = valid.envelope.copy(memory = JsonObject(valid.envelope.memory - "sources"))),
            valid.copy(pending = listOf(LearningCommand(kind="progress",body=progressFor(UUID.randomUUID().toString())))),
            valid.copy(pending = listOf(LearningCommand(kind="progress",body=progressFor(valid.goals().single().id).changed("confirmed",JsonPrimitive(false)))))
        )
        val dao = MemoryLearningDao(); val repo = LearningRepository(dao) { error("No network") }
        repo.createGoal(LearningGoalDto(title="Memoria original con comando pendiente"))
        repo.begin(UUID.randomUUID().toString(),"2027-01-04")
        val before = dao.get()
        variants.forEachIndexed { index, invalid ->
            try { repo.importBackup(json.encodeToString(invalid)); fail("Accepted invalid backup $index") } catch(_: IllegalArgumentException) {}
            assertEquals("Full payload changed for $index",before,dao.get())
            assertNull(dao.recovery())
        }
    }
    @Test fun legacyBackupWithQueuedProgressStillImportsAndReplaysWithoutLosingCommands() = runTest {
        val api = LearningApiFake(); val source = LearningRepository(MemoryLearningDao()) { api }
        val goal = LearningGoalDto(title="Sin conexión"); source.createGoal(goal)
        source.registerProgress(progressFor(goal.id))
        val legacy = JsonObject(json.encodeToJsonElement(source.current()).jsonObject - "recoveryBase")
        val dao = MemoryLearningDao(); val restored = LearningRepository(dao) { api }
        restored.importBackup(legacy.toString())
        assertEquals(source.current().pending,restored.current().pending)
        restored.resumeImportedBackup(); restored.sync()
        assertTrue(restored.current().pending.isEmpty())
        assertEquals(1,restored.current().envelope.memory.rows("sessions").size)
        assertEquals(2,api.writes)
    }
    @Test fun validImportProtectsAndCanRecoverAllPreviousDataIncludingPendingAndActive() = runTest {
        val dao = MemoryLearningDao(); val repo = LearningRepository(dao) { error("No network") }
        repo.createGoal(LearningGoalDto(title="Anterior")); repo.begin(UUID.randomUUID().toString(),"2027-01-04")
        val before = dao.get()!!.payload
        val valid = learningFixture()
        repo.importBackup(json.encodeToString(valid))
        assertEquals(before,repo.previousBackup())
        assertEquals(valid.copy(restorePending=true),repo.current())
        val imported = dao.get()!!.payload
        // An unresolved protected copy cannot be overwritten by another import.
        try { repo.importBackup(json.encodeToString(valid)); fail("Expected protected copy") } catch(_: IllegalArgumentException) {}
        assertEquals(imported,dao.get()!!.payload); assertEquals(before,repo.previousBackup())
        repo.recoverPreviousBackup()
        assertEquals(before,dao.get()!!.payload); assertEquals(imported,repo.previousBackup())
        repo.recoverPreviousBackup()
        assertEquals(imported,dao.get()!!.payload); assertEquals(before,repo.previousBackup())
    }
    @Test fun recoveryDetectsEveryMissingCollectionAndDifferentContentWithoutWriting() = runTest {
        val valid = learningFixture()
        val changed = LearningIntegrity.collections.map { key -> valid.envelope.copy(memory=valid.envelope.memory.withRows(key,emptyList())) } + listOf(
            valid.change("goals") { it.changed("title",JsonPrimitive("Título remoto distinto")) }.envelope,
            valid.change("sessions") { it.changed("completedAt",JsonPrimitive("2027-01-06T01:00:00Z")) }.envelope,
            valid.change("sessions") { it.changed("status",JsonPrimitive("skipped")) }.envelope,
            valid.change("nextSteps") { it.changed("description",JsonPrimitive("Otra recomendación")) }.envelope,
            valid.envelope.copy(memory=valid.envelope.memory.withRows("sources",valid.envelope.memory.rows("sources") + valid.envelope.memory.rows("sources").single().changed("id",JsonPrimitive(UUID.randomUUID().toString()))))
        )
        changed.forEach { remote ->
            val dao = MemoryLearningDao(); val api = LearningApiFake(); val repo = LearningRepository(dao) { api }
            repo.createGoal(LearningGoalDto(title="Anterior")); repo.importBackup(json.encodeToString(valid))
            val before = dao.get(); val previous = dao.recovery()
            api.remoteLearning = remote
            try { repo.resumeImportedBackup(); fail("Expected conflict") } catch(_: IllegalArgumentException) {}
            assertEquals(before,dao.get()); assertEquals(previous,dao.recovery())
            try { repo.sync(); fail("Expected recovery guard") } catch(_: IllegalArgumentException) {}
            assertEquals(before,dao.get()); assertEquals(previous,dao.recovery())
        }
    }
    @Test fun restoringEmptyServerPreservesConfirmedDataAndRejectsDifferentExistingPreferences() = runTest {
        val valid = learningFixture(); val api = LearningApiFake(); val dao = MemoryLearningDao(); val repo = LearningRepository(dao) { api }
        repo.createGoal(LearningGoalDto(title="Local anterior")); repo.importBackup(json.encodeToString(valid))
        val before = dao.get(); val previous = dao.recovery()
        api.remoteLearning = LearningEnvelope(memory=LearningEnvelope().memory.changed("preferences",buildJsonObject { put("preferredSessionMinutes",60) }))
        try { repo.restoreEmptyServer(); fail("Expected preferences conflict") } catch(_: IllegalArgumentException) {}
        assertEquals(before,dao.get()); assertEquals(previous,dao.recovery()); assertEquals(0,api.writes)
        api.remoteLearning = LearningEnvelope()
        repo.restoreEmptyServer(); repo.sync()
        assertTrue(sameLearningMemory(valid.envelope.memory,repo.current().envelope.memory))
        assertEquals(previous,dao.recovery()); assertEquals(1,api.writes)
        assertFalse(repo.current().restorePending); assertNull(repo.current().recoveryBase)
    }
    @Test fun matchingRecoveryRetainsReceiptsAndGuardsChangesBeforeSync() = runTest {
        val valid = learningFixture(); val api = LearningApiFake(); api.remoteLearning = valid.envelope
        val dao = MemoryLearningDao(); val repo = LearningRepository(dao) { api }
        repo.importBackup(json.encodeToString(valid)); repo.resumeImportedBackup()
        val reviewed = repo.current()
        api.remoteLearning = valid.change("goals") { it.changed("title",JsonPrimitive("Cambio concurrente")) }.envelope.copy(revision=8)
        try { repo.sync(); fail("Expected concurrent recovery conflict") } catch(_: IllegalArgumentException) {}
        assertEquals(reviewed,repo.current())
        api.remoteLearning = valid.envelope
        repo.sync()
        assertEquals(valid.envelope,repo.current().envelope)
        assertNull(repo.current().recoveryBase)
        assertEquals(valid.envelope.memory.rows("receipts"),repo.current().envelope.memory.rows("receipts"))
    }
    @Test fun pendingAndConfirmedRecordsSurviveMatchingRecoveryAndSync() = runTest {
        val valid = learningFixture(); val api = LearningApiFake(); api.remoteLearning = valid.envelope
        val sourceDao = MemoryLearningDao(); sourceDao.put(LearningStored(payload=json.encodeToString(valid)))
        val source = LearningRepository(sourceDao) { api }; source.createGoal(LearningGoalDto(title="Pendiente"))
        val backup = source.current()
        val repo = LearningRepository(MemoryLearningDao()) { api }
        repo.importBackup(json.encodeToString(backup)); repo.resumeImportedBackup()
        assertEquals(backup.pending,repo.current().pending)
        repo.sync()
        assertTrue(repo.current().pending.isEmpty())
        assertEquals(2,repo.current().goals().size)
        assertEquals(valid.envelope.memory.rows("receipts"),repo.current().envelope.memory.rows("receipts"))
    }
}

class LearningIdempotencyTest {
    @Test fun pendingRequestComparesAllContentAndKeepsOriginalOnConflict() = runTest {
        val dao = MemoryLearningDao(); val api = LearningApiFake(); val repo = LearningRepository(dao) { api }
        val goal = LearningGoalDto(title="Objetivo"); repo.createGoal(goal)
        val original = progressFor(goal.id)
        repo.registerProgress(original)
        val before = dao.get()
        repo.registerProgress(JsonObject(original.entries.reversed().associate { it.toPair() }))
        assertEquals(before,dao.get())
        listOf("actualMinutes" to JsonPrimitive(90),"topic" to JsonPrimitive("Corregido"),"notes" to JsonPrimitive("Notas nuevas"),"completedAt" to JsonPrimitive("2027-01-06T01:00:00Z")).forEach { (key,value) ->
            try { repo.registerProgress(original.changed(key,value)); fail("Conflict reported as success") } catch(e: IllegalArgumentException) { assertTrue(e.message!!.contains("Conflicto")) }
            assertEquals(before,dao.get())
        }
        repo.registerProgress(original.changed("requestId",JsonPrimitive(UUID.randomUUID().toString())))
        assertEquals(3,repo.current().pending.size)
        assertEquals(original,repo.current().pending[1].body)
        repo.sync()
        assertTrue(repo.current().pending.isEmpty()); assertEquals(2,repo.current().envelope.memory.rows("sessions").size)
    }
    @Test fun canonicalReceiptFingerprintMatchesExistingBackendContract() {
        val body = json.parseToJsonElement("""{"requestId":"00000000-0000-4000-8000-000000000001","goalId":"00000000-0000-4000-8000-000000000002","topic":" Funciones ","completedAt":"2027-01-05T01:00:00.120000+00:00","actualMinutes":30,"understood":[" algo "],"confirmed":true,"notes":"","sourceType":"manual","assessment":{"correctCount":1,"totalCount":2,"method":" Práctica ","confidence":1}}""").jsonObject
        assertEquals("d17ab08a09ff261362c6a92655e02748648713e6dceee5dadaa7592aaeb00be9",learningRequestFingerprint(body,"progress"))
        assertEquals(canonicalLearningCommand(body,"progress"),canonicalLearningCommand(body.changed("topic",JsonPrimitive("Funciones")),"progress"))
        listOf(
            0.0001 to "750c0701808e0a2214e44bddcf8bea70dc3e2758046fd5af4837a8882ce56bc6",
            -0.0 to "77a1d94fd2bce1e201fc3daa8a068c7f6cf104cb0421758faf326beb3ee31337",
            Double.MIN_VALUE to "a59a1a0192afb9f9a165d12577482c63dbfe244581ae62e82e1db63cb5408d73"
        ).forEach { (confidence,expected) ->
            val variant = body.changed("assessment",body.getValue("assessment").jsonObject.changed("confidence",JsonPrimitive(confidence)))
            assertEquals(expected,learningRequestFingerprint(variant,"progress"))
        }
    }
    @Test fun confirmedReceiptAndPendingCommandMustAgreeWithoutChangingImportedOrPreviousData() = runTest {
        val valid = learningFixture(); val goal = valid.goals().single().id
        val request = valid.envelope.memory.rows("receipts").single().text("requestId")
        val session = valid.envelope.memory.rows("sessions").single().text("id")
        val body = progressFor(goal,request).changed("sessionId",JsonPrimitive(session))
        val receipt = valid.envelope.memory.rows("receipts").single().changed("fingerprint",JsonPrimitive(learningRequestFingerprint(body,"progress")))
        val correct = valid.copy(envelope=valid.envelope.copy(memory=valid.envelope.memory.withRows("receipts",listOf(receipt))),pending=listOf(LearningCommand(kind="progress",body=body)))
        val dao = MemoryLearningDao(); val repo = LearningRepository(dao) { error("No network") }
        repo.createGoal(LearningGoalDto(title="Anterior")); val before = dao.get()
        val wrong = correct.copy(pending=listOf(LearningCommand(kind="progress",body=body.changed("topic",JsonPrimitive("Otro")))))
        try { repo.importBackup(json.encodeToString(wrong)); fail("Expected receipt conflict") } catch(_: IllegalArgumentException) {}
        assertEquals(before,dao.get()); assertNull(dao.recovery())
        repo.importBackup(json.encodeToString(correct))
        val imported = dao.get(); repo.registerProgress(body); assertEquals(imported,dao.get())
        try { repo.registerProgress(body.changed("actualMinutes",JsonPrimitive(90))); fail("Expected confirmed conflict") } catch(_: IllegalArgumentException) {}
        assertEquals(imported,dao.get()); assertEquals(before!!.payload,repo.previousBackup())
    }
    @Test fun failedPullAfterAcknowledgementKeepsTheCompleteQueueForIdempotentReplay() = runTest {
        val dao = MemoryLearningDao(); val api = LearningApiFake(); val repo = LearningRepository(dao) { api }
        val goal = LearningGoalDto(title="Objetivo"); repo.createGoal(goal); repo.sync()
        val body = progressFor(goal.id); repo.registerProgress(body)
        val before = dao.get()
        api.failLearningCall = api.learningCalls + 2
        try { repo.sync(); fail("Expected failed pull after successful write") } catch(_: java.io.IOException) {}
        assertEquals(before,dao.get())
        assertEquals(1,api.remoteLearning.memory.rows("sessions").size)
        val reopened = LearningRepository(dao) { api }; reopened.registerProgress(body)
        assertEquals(before,dao.get())
        reopened.sync()
        assertEquals(1,reopened.current().envelope.memory.rows("sessions").size)
        assertTrue(reopened.current().pending.isEmpty()); assertEquals(2,api.writes)
    }
    @Test fun defaultFieldsAreEquivalentAndRetryAfterNetworkCommitDoesNotDuplicate() = runTest {
        val dao = MemoryLearningDao(); val api = LearningApiFake(); val repo = LearningRepository(dao) { api }
        val goal = LearningGoalDto(title="Objetivo"); repo.createGoal(goal); repo.sync()
        val original = progressFor(goal.id)
        repo.registerProgress(original)
        repo.registerProgress(original.changed("notes",JsonPrimitive("")).changed("sourceType",JsonPrimitive("manual")).changed("startedAt",JsonNull))
        assertEquals(1,repo.current().pending.size)
        api.failProgressOnce = true
        try { repo.sync(); fail("Expected network error after commit") } catch(_: java.io.IOException) {}
        val reopened = LearningRepository(dao) { api }
        val before = dao.get(); reopened.registerProgress(original); assertEquals(before,dao.get())
        try { reopened.registerProgress(original.changed("actualMinutes",JsonPrimitive(90))); fail("Expected conflict") } catch(_: IllegalArgumentException) {}
        assertEquals(before,dao.get())
        reopened.sync()
        assertEquals(1,reopened.current().envelope.memory.rows("sessions").size)
        assertTrue(reopened.current().pending.isEmpty()); assertEquals(2,api.writes)
    }
}
