package com.calenflex.app

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Serializable data class LearningGoalDto(
    val id: String = UUID.randomUUID().toString(), val title: String,
    val description: String = "", val category: String = "Aprendizaje", val priority: Int = 2,
    val targetDate: String? = null, val status: String = "active", val estimatedTotalMinutes: Int? = null
)
@Serializable data class LearningEnvelope(val revision: Int = 0, val memory: JsonObject = buildJsonObject {
    put("schemaVersion", 1); listOf("goals", "milestones", "topics", "competencies", "sessions", "events", "sources", "nextSteps", "receipts").forEach { put(it, JsonArray(emptyList())) }
})
@Serializable data class MutationDto(val revision: Int, val entityId: String, val duplicate: Boolean = false, val message: String)
@Serializable data class LearningCommand(val id: String = UUID.randomUUID().toString(), val kind: String, val body: JsonObject)
@Serializable data class ActiveStudy(val activityId: String, val startedAt: String, val occurrenceDate: String? = null)
@Serializable data class LearningSnapshot(val restorePending: Boolean = false, val recoveryBase: LearningEnvelope? = null, val insights: List<JsonObject> = emptyList(), val active: ActiveStudy? = null, val envelope: LearningEnvelope = LearningEnvelope(), val pending: List<LearningCommand> = emptyList())
@Entity(tableName = "learning_cache") data class LearningStored(@PrimaryKey val id: Int = 1, val payload: String)
@Dao interface LearningDao {
    @Query("SELECT * FROM learning_cache WHERE id = 1") fun observe(): Flow<LearningStored?>
    @Query("SELECT * FROM learning_cache WHERE id = 1") suspend fun get(): LearningStored?
    @Query("SELECT * FROM learning_cache WHERE id = 2") suspend fun recovery(): LearningStored?
    @Query("DELETE FROM learning_cache WHERE id = 2") suspend fun discardRecovery()
    @Transaction suspend fun importWithRecovery(value: LearningStored) {
        require(recovery() == null) { "Ya hay una copia local protegida. Exportala y resolvé la recuperación antes de importar otra." }
        get()?.let { put(it.copy(id = 2)) }
        put(value)
    }
    @Transaction suspend fun recoverPrevious() {
        val previous = recovery() ?: error("No hay una copia anterior")
        val imported = get()
        put(previous.copy(id = 1))
        if(imported != null) put(imported.copy(id = 2)) else discardRecovery()
    }
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(value: LearningStored)
}
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `learning_cache` (`id` INTEGER NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`id`))")
    }
}
fun JsonObject.rows(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
fun JsonObject.withRows(key: String, rows: List<JsonObject>) = JsonObject(toMutableMap().apply { put(key, JsonArray(rows)) })
fun LearningSnapshot.goals(): List<LearningGoalDto> = envelope.memory.rows("goals").map { json.decodeFromJsonElement(it) }

class LearningRepository(private val dao: LearningDao, internal val mutex: Mutex = Mutex(), private val api: suspend () -> Api) {
    val snapshots = dao.observe().map { it?.let { json.decodeFromString<LearningSnapshot>(it.payload) } ?: LearningSnapshot() }
    suspend fun current() = dao.get()?.let { json.decodeFromString<LearningSnapshot>(it.payload) } ?: LearningSnapshot()
    private suspend fun store(snapshot: LearningSnapshot) { dao.put(LearningStored(payload = json.encodeToString(snapshot))) }
    suspend fun createGoal(goal: LearningGoalDto) = mutex.withLock {
        require(goal.title.isNotBlank() && goal.title.length <= 200 && goal.description.length <= 4000) { "Revisá el título y la descripción" }
        UUID.fromString(goal.id); goal.targetDate?.let { LocalDate.parse(it) }
        val snapshot = current()
        require(snapshot.goals().none { it.id == goal.id }) { "El objetivo ya existe" }
        val body = json.encodeToJsonElement(goal).jsonObject
        val memory = snapshot.envelope.memory.withRows("goals", snapshot.envelope.memory.rows("goals") + body)
        store(snapshot.copy(envelope = snapshot.envelope.copy(memory = memory), pending = snapshot.pending + LearningCommand(kind = "goal", body = body)))
    }
    suspend fun begin(activityId: String, occurrenceDate: String? = null) = mutex.withLock {
        occurrenceDate?.let { LocalDate.parse(it) }
        val snapshot = current()
        require(snapshot.active == null) { "Finalizá la sesión que está en curso" }
        store(snapshot.copy(active = ActiveStudy(activityId, Instant.now().toString(), occurrenceDate)))
    }
    suspend fun clearActive() = mutex.withLock { clearActiveLocked() }
    internal suspend fun clearActiveLocked() { store(current().copy(active = null)) }
    suspend fun registerProgress(body: JsonObject) = mutex.withLock { registerProgressLocked(body) }
    internal suspend fun registerProgressLocked(body: JsonObject) {
        val snapshot = current()
        LearningIntegrity.command(body, "progress", snapshot.goals().map { it.id }.toSet())
        snapshot.pending.firstOrNull { it.body.text("requestId") == body.text("requestId") }?.let { original ->
            require(original.kind == "progress" && canonicalLearningCommand(original.body, "progress") == canonicalLearningCommand(body, "progress")) {
                "Conflicto: el identificador ya está pendiente con otros datos. Conservamos el registro original."
            }
            return
        }
        snapshot.envelope.memory.rows("receipts").firstOrNull { it.text("requestId") == body.text("requestId") }?.let { receipt ->
            require(receipt.text("fingerprint") == learningRequestFingerprint(body,"progress")) { "Conflicto: el identificador ya fue confirmado con otros datos" }
            return
        }
        validateStudySelection(snapshot.active, body)
        val local = buildJsonObject {
            put("id", body.text("sessionId").ifBlank { body.text("requestId") }); put("goalId", body.text("goalId"))
            put("completedAt", body.text("completedAt")); put("status", "completed")
            put("occurrenceDate", body["occurrenceDate"] ?: JsonNull); put("startedAt", body["startedAt"] ?: JsonNull); put("plannedMinutes", body["plannedMinutes"] ?: JsonNull)
            put("activityId", body["activityId"] ?: JsonNull); put("notes", body.text("notes")); put("actualMinutes", body["actualMinutes"] ?: JsonNull)
        }
        val memory = snapshot.envelope.memory.withRows("sessions", snapshot.envelope.memory.rows("sessions") + local)
        store(snapshot.copy(active = if(snapshot.active != null && body.text("activityId") == snapshot.active.activityId) null else snapshot.active, envelope = snapshot.envelope.copy(memory = memory), pending = snapshot.pending + LearningCommand(kind = "progress", body = body)))
    }
    suspend fun registerResource(body: JsonObject) = mutex.withLock {
        val snapshot = current()
        require(snapshot.goals().any { it.id == body.text("goalId") }) { "Elegí un objetivo" }
        require(body.text("title").isNotBlank() && body.text("title").length <= 200 && body.text("summary").length <= 2000 && body.text("confirmed") == "true")
        UUID.fromString(body.text("requestId"))
        store(snapshot.copy(pending = snapshot.pending + LearningCommand(kind = "resource", body = body)))
    }
    suspend fun sync() = mutex.withLock {
        var snapshot = current()
        require(!snapshot.restorePending) { "Revisá la copia importada antes de sincronizar el aprendizaje" }
        val client = api()
        val initialRemote = client.learning()
        snapshot.recoveryBase?.let { base ->
            require(sameLearningMemory(base.memory, initialRemote.memory)) { "Conflicto: el servidor cambió después de revisar la copia. Conservamos ambos estados; volvé a revisar la recuperación." }
        }
        require(initialRemote.revision >= snapshot.envelope.revision) { "La memoria del servidor es anterior a tu copia. Conservamos los datos locales; revisá la recuperación." }
        while(snapshot.pending.isNotEmpty()) {
            val command = snapshot.pending.first()
            val result = when(command.kind) {
                "goal" -> client.createLearningGoal(command.body)
                "progress" -> client.registerLearningProgress(command.body)
                "resource" -> client.registerLearningResource(command.body)
                else -> error("Operación educativa no compatible")
            }
            // A network failure after server commit replays the same stable ID safely.
            snapshot = snapshot.copy(envelope = snapshot.envelope.copy(revision = result.revision), pending = snapshot.pending.drop(1))
            // Keep the durable queue until the validated pull succeeds; failed pulls replay stable IDs.
        }
        val remote = client.learning()
        require(remote.revision >= snapshot.envelope.revision) { "La revisión del servidor retrocedió. Conservamos tu memoria local; revisá la conexión o restaurá la copia." }
        require(remote.memory["schemaVersion"]?.jsonPrimitive?.int == 1) { "Memoria de una versión incompatible" }
        LearningIntegrity.snapshot(LearningSnapshot(envelope = remote))
        snapshot.recoveryBase?.let { base ->
            require(containsLearningMemory(remote.memory, base.memory)) { "Conflicto: cambió información de la copia durante la sincronización. Conservamos los datos locales." }
        }
        val insights = client.learningInsights().rows("items")
        store(snapshot.copy(envelope = remote, insights = insights, recoveryBase = null))
    }
    suspend fun importBackup(text: String) = mutex.withLock {
        require(text.length <= 5_000_000) { "La copia supera 5 MB" }
        // Decode strictly: ignored unknown fields must never disappear on a round trip.
        val strict = Json(json) { ignoreUnknownKeys = false }
        val root = strict.parseToJsonElement(text).jsonObject
        val envelope = root["envelope"] as? JsonObject
        require(envelope?.get("memory") is JsonObject && (envelope["revision"] as? JsonPrimitive)?.intOrNull != null && root["pending"] is JsonArray) { "La copia no contiene el agregado, su revisión y su cola" }
        val restored = strict.decodeFromString<LearningSnapshot>(text)
        LearningIntegrity.snapshot(restored)
        restored.recoveryBase?.let { LearningIntegrity.snapshot(LearningSnapshot(envelope = it)) }
        // A single Room transaction protects the old payload in the existing cache table.
        dao.importWithRecovery(LearningStored(payload = json.encodeToString(restored.copy(restorePending = true, recoveryBase = null))))
    }
    suspend fun previousBackup(): String? = mutex.withLock { dao.recovery()?.payload }
    suspend fun recoverPreviousBackup() = mutex.withLock { dao.recoverPrevious() }
    suspend fun discardPreviousBackup() = mutex.withLock { dao.discardRecovery() }
    suspend fun resumeImportedBackup() = mutex.withLock {
        val snapshot = current(); val remote = api().learning()
        LearningIntegrity.snapshot(snapshot)
        LearningIntegrity.snapshot(LearningSnapshot(envelope = remote))
        val pendingGoals = snapshot.pending.filter { it.kind == "goal" }.map { it.body.text("id") }.toSet()
        val pendingSessions = snapshot.pending.filter { it.kind == "progress" }.map { it.body.text("sessionId").ifBlank { it.body.text("requestId") } }.toSet()
        val confirmed = snapshot.envelope.memory
            .withRows("goals", snapshot.envelope.memory.rows("goals").filterNot { it.text("id") in pendingGoals && remote.memory.rows("goals").none { r -> r.text("id") == it.text("id") } })
            .withRows("sessions", snapshot.envelope.memory.rows("sessions").filterNot { it.text("id") in pendingSessions && remote.memory.rows("sessions").none { r -> r.text("id") == it.text("id") } })
        require(sameLearningMemory(confirmed, remote.memory)) { "Conflicto: la copia y el servidor tienen información educativa diferente. Conservamos la copia importada y la local anterior; exportalas o recuperá la anterior antes de continuar." }
        store(snapshot.copy(restorePending = false, recoveryBase = remote, envelope = snapshot.envelope.copy(revision = remote.revision)))
    }
    suspend fun restoreEmptyServer() = mutex.withLock {
        val snapshot = current(); LearningIntegrity.snapshot(snapshot); val client = api(); val remote = client.learning()
        LearningIntegrity.snapshot(LearningSnapshot(envelope = remote))
        require(LearningIntegrity.collections.all { remote.memory.rows(it).isEmpty() }) { "El servidor tiene registros. No lo reemplazamos para evitar perder información." }
        val remotePreferences = canonicalEntity(remote.memory["preferences"] as? JsonObject ?: buildJsonObject {},"preferences")
        val localPreferences = canonicalEntity(snapshot.envelope.memory["preferences"] as? JsonObject ?: buildJsonObject {},"preferences")
        require(remotePreferences == canonicalEntity(buildJsonObject {},"preferences") || remotePreferences == localPreferences) { "El servidor conserva preferencias diferentes. Revisalas antes de restaurar la copia." }
        val pendingGoals = snapshot.pending.filter { it.kind == "goal" }.map { it.body.text("id") }.toSet()
        val pendingSessions = snapshot.pending.filter { it.kind == "progress" }.map { it.body.text("sessionId").ifBlank { it.body.text("requestId") } }.toSet()
        val base = snapshot.envelope.memory.withRows("goals", snapshot.envelope.memory.rows("goals").filterNot { it.text("id") in pendingGoals })
            .withRows("sessions", snapshot.envelope.memory.rows("sessions").filterNot { it.text("id") in pendingSessions })
        LearningIntegrity.snapshot(LearningSnapshot(envelope = LearningEnvelope(memory = base)))
        val result = client.correctLearning(buildJsonObject { put("revision", remote.revision); put("memory", base); put("confirmed", true) })
        store(snapshot.copy(restorePending = false, recoveryBase = LearningEnvelope(result.revision, base), envelope = snapshot.envelope.copy(revision = result.revision)))
    }
    suspend fun deleteGoal(goalId: String) = mutex.withLock {
        val snapshot = current()
        require(snapshot.pending.isEmpty()) { "Sincronizá los registros pendientes antes de eliminar un objetivo" }
        api().deleteLearningGoal(goalId, buildJsonObject { put("revision", snapshot.envelope.revision); put("confirmed", true) })
        store(snapshot.copy(envelope = api().learning()))
    }
    suspend fun correctGoal(goal: LearningGoalDto) = mutex.withLock {
        val snapshot = current()
        require(snapshot.pending.isEmpty()) { "Sincronizá los registros pendientes antes de corregir" }
        require(goal.title.isNotBlank() && goal.title.length <= 200)
        val rows = snapshot.envelope.memory.rows("goals").map { if(it.text("id") == goal.id) json.encodeToJsonElement(goal).jsonObject else it }
        val memory = snapshot.envelope.memory.withRows("goals", rows)
        api().correctLearning(buildJsonObject { put("revision", snapshot.envelope.revision); put("memory", memory); put("confirmed", true) })
        store(snapshot.copy(envelope = api().learning()))
    }
}

val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE workspace ADD COLUMN syncBase TEXT")
        db.execSQL("ALTER TABLE workspace ADD COLUMN syncRequest TEXT")
        db.execSQL("ALTER TABLE workspace ADD COLUMN conflict TEXT")
    }
}
