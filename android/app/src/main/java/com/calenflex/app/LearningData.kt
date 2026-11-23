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
@Serializable data class ActiveStudy(val activityId: String, val startedAt: String)
@Serializable data class LearningSnapshot(val restorePending: Boolean = false, val insights: List<JsonObject> = emptyList(), val active: ActiveStudy? = null, val envelope: LearningEnvelope = LearningEnvelope(), val pending: List<LearningCommand> = emptyList())
@Entity(tableName = "learning_cache") data class LearningStored(@PrimaryKey val id: Int = 1, val payload: String)
@Dao interface LearningDao {
    @Query("SELECT * FROM learning_cache WHERE id = 1") fun observe(): Flow<LearningStored?>
    @Query("SELECT * FROM learning_cache WHERE id = 1") suspend fun get(): LearningStored?
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

class LearningRepository(private val dao: LearningDao, private val api: suspend () -> Api) {
    private val mutex = Mutex()
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
    suspend fun begin(activityId: String) = mutex.withLock {
        val snapshot = current()
        require(snapshot.active == null) { "Finalizá la sesión que está en curso" }
        store(snapshot.copy(active = ActiveStudy(activityId, Instant.now().toString())))
    }
    suspend fun clearActive() = mutex.withLock { store(current().copy(active = null)) }
    suspend fun registerProgress(body: JsonObject) = mutex.withLock {
        val snapshot = current()
        require(snapshot.goals().any { it.id == body.text("goalId") }) { "Elegí el objetivo de esta sesión" }
        require(body.text("topic").isNotBlank() && body.text("topic").length <= 200) { "Indicá el tema trabajado" }
        require(body.number("actualMinutes") == null || body.number("actualMinutes") in 1..1440) { "Revisá la duración real" }
        require(body.text("confirmed") == "true") { "Confirmá la sesión" }
        require(body.text("notes").length <= 4000 && body.text("subject").length <= 200 && body.text("evaluation").length <= 2000) { "Resumí las notas, materia o evaluación" }
        listOf("understood", "needsReinforcement", "difficulties", "exercises", "subtopics", "pendingTasks").forEach { key ->
            val values = body[key] as? JsonArray ?: JsonArray(emptyList())
            require(values.size <= if(key == "pendingTasks") 20 else 50) { "Hay demasiados elementos en el registro" }
            require(values.all { it.jsonPrimitive.content.isNotBlank() && it.jsonPrimitive.content.length <= 1000 }) { "Cada contenido debe tener entre 1 y 1000 caracteres" }
        }
        require(body.text("recommendedNextTopic").length <= 200)
        body.text("sourceContentDigest").takeIf { it.isNotBlank() }?.let { require(it.matches(Regex("[0-9a-f]{64}"))) }

        UUID.fromString(body.text("requestId")); val completedAt = Instant.parse(body.text("completedAt")); body.text("startedAt").takeIf { it.isNotBlank() }?.let { require(!Instant.parse(it).isAfter(completedAt)) { "La fecha de finalización es anterior al inicio" } }
        if(snapshot.pending.any { it.body.text("requestId") == body.text("requestId") }) return@withLock
        val local = buildJsonObject {
            put("id", body.text("sessionId").ifBlank { body.text("requestId") }); put("goalId", body.text("goalId"))
            put("completedAt", body.text("completedAt")); put("status", "completed")
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
            store(snapshot)
        }
        val remote = client.learning()
        require(remote.revision >= snapshot.envelope.revision) { "La revisión del servidor retrocedió. Conservamos tu memoria local; revisá la conexión o restaurá la copia." }
        require(remote.memory["schemaVersion"]?.jsonPrimitive?.int == 1) { "Memoria de una versión incompatible" }
        val insights = client.learningInsights().rows("items")
        store(snapshot.copy(envelope = remote, insights = insights))
    }
    suspend fun importBackup(text: String) = mutex.withLock {
        require(text.length <= 5_000_000) { "La copia supera 5 MB" }
        val restored = json.decodeFromString<LearningSnapshot>(text)
        require(restored.envelope.memory["schemaVersion"]?.jsonPrimitive?.int == 1)
        require(restored.pending.all { it.kind in listOf("goal", "progress", "resource") })
        require(restored.goals().map { it.id }.distinct().size == restored.goals().size)
        restored.goals().forEach { UUID.fromString(it.id); require(it.title.isNotBlank() && it.title.length <= 200 && it.priority in 1..4 && (it.estimatedTotalMinutes == null || it.estimatedTotalMinutes >= 5)); it.targetDate?.let { date -> LocalDate.parse(date) } }
        restored.pending.forEach { UUID.fromString(it.id) }
        store(restored.copy(restorePending = true))
    }
    suspend fun resumeImportedBackup() = mutex.withLock {
        val snapshot = current(); val remote = api().learning()
        val pendingGoals = snapshot.pending.filter { it.kind == "goal" }.map { it.body.text("id") }.toSet()
        val pendingSessions = snapshot.pending.filter { it.kind == "progress" }.map { it.body.text("requestId") }.toSet()
        require(snapshot.goals().all { it.id in pendingGoals || remote.memory.rows("goals").any { g -> g.text("id") == it.id } }) { "El servidor no contiene todos los objetivos de la copia. No descartamos datos; podés restaurarla en un servidor vacío." }
        require(snapshot.envelope.memory.rows("sessions").all { local -> local.text("id") in pendingSessions || remote.memory.rows("sessions").any { it.text("id") == local.text("id") } }) { "Faltan sesiones en el servidor. Conservamos la copia local." }
        store(snapshot.copy(restorePending = false, envelope = snapshot.envelope.copy(revision = remote.revision)))
    }
    suspend fun restoreEmptyServer() = mutex.withLock {
        val snapshot = current(); val client = api(); val remote = client.learning()
        require(listOf("goals", "sessions", "events", "sources", "topics", "milestones", "nextSteps", "competencies").all { remote.memory.rows(it).isEmpty() }) { "El servidor tiene registros. No lo reemplazamos para evitar perder información." }
        val pendingGoals = snapshot.pending.filter { it.kind == "goal" }.map { it.body.text("id") }.toSet()
        val pendingSessions = snapshot.pending.filter { it.kind == "progress" }.map { it.body.text("requestId") }.toSet()
        val base = snapshot.envelope.memory.withRows("goals", snapshot.envelope.memory.rows("goals").filterNot { it.text("id") in pendingGoals })
            .withRows("sessions", snapshot.envelope.memory.rows("sessions").filterNot { it.text("id") in pendingSessions })
        val result = client.correctLearning(buildJsonObject { put("revision", remote.revision); put("memory", base); put("confirmed", true) })
        store(snapshot.copy(restorePending = false, envelope = snapshot.envelope.copy(revision = result.revision)))
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
