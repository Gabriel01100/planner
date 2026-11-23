package com.calenflex.app

import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** Validation of the existing v1 learning aggregate, before any persistence or recovery write. */
internal object LearningIntegrity {
    val collections = listOf("goals", "milestones", "topics", "competencies", "sessions", "events", "sources", "nextSteps", "receipts")
    private val digest = Regex("[0-9a-f]{64}")
    private fun fail(message: String): Nothing = throw IllegalArgumentException("Datos educativos inválidos: $message")
    private fun check(ok: Boolean, message: String) { if(!ok) fail(message) }
    private fun JsonObject.value(key: String) = this[key]?.takeUnless { it == JsonNull }
    private fun JsonObject.string(key: String, max: Int, required: Boolean = false): String? {
        val v = value(key) ?: return null.also { check(!required, "falta $key") }
        check(v is JsonPrimitive && v.isString, "$key debe ser texto")
        return v.jsonPrimitive.content.also { check(it.length <= max && (!required || it.isNotBlank()), "$key fuera de rango") }
    }
    private fun JsonObject.uuid(key: String, required: Boolean = false): String? = string(key, 36, required)?.also {
        check(runCatching { UUID.fromString(it).toString() == it.lowercase() }.getOrDefault(false), "UUID inválido en $key")
    }
    private fun JsonObject.integer(key: String, min: Int, max: Int, required: Boolean = false): Int? {
        val v = value(key) ?: return null.also { check(!required, "falta $key") }
        check(v is JsonPrimitive && !v.isString && v.intOrNull in min..max, "$key fuera de rango")
        return v.jsonPrimitive.int
    }
    private fun JsonObject.fraction(key: String): Double? {
        val v = value(key) ?: return null
        val n = (v as? JsonPrimitive)?.doubleOrNull
        check(v is JsonPrimitive && !v.isString && n != null && n.isFinite() && n in 0.0..1.0, "$key fuera de rango")
        return n
    }
    private fun JsonObject.bool(key: String, required: Boolean = false): Boolean? {
        val v = value(key) ?: return null.also { check(!required, "falta $key") }
        check(v is JsonPrimitive && !v.isString && v.booleanOrNull != null, "$key debe ser booleano")
        return v.jsonPrimitive.boolean
    }
    private fun JsonObject.date(key: String) = string(key, 10)?.also { check(runCatching { LocalDate.parse(it).toString() == it }.getOrDefault(false), "fecha inválida en $key") }
    private fun JsonObject.time(key: String, required: Boolean = false): Instant? = string(key, 80, required)?.let {
        runCatching { OffsetDateTime.parse(it).toInstant() }.getOrElse { fail("marca temporal inválida en $key") }
    }
    private fun JsonObject.enum(key: String, values: String) { string(key, 100)?.let { check(it in values.split('|'), "$key no reconocido") } }
    private fun JsonObject.array(key: String, max: Int, required: Boolean = false): JsonArray {
        val v = this[key] ?: return JsonArray(emptyList()).also { check(!required, "falta $key") }
        check(v is JsonArray && v.size <= max, "colección $key inválida")
        return v.jsonArray
    }
    private fun JsonObject.texts(key: String, max: Int = 50, nonblank: Boolean = false) {
        array(key, max).forEach { check(it is JsonPrimitive && it.isString && (!nonblank || (it.content.length <= 1000 && it.content.isNotBlank())), "contenido inválido en $key") }
    }
    private fun JsonObject.ids(key: String, max: Int): List<String> = array(key, max).map { v ->
        buildJsonObject { put("id", v) }.uuid("id", true)!!
    }
    private fun JsonObject.fields(names: String) { check(keys.all { it in names.split(' ') }, "campos no compatibles: ${keys - names.split(' ').toSet()}") }
    private fun JsonObject.nullable(names: String) {
        check(entries.none { it.value == JsonNull && it.key !in names.split(' ') }, "campo obligatorio nulo")
    }
    private fun JsonObject.url(key: String) { string(key, 2000)?.let { check(it.isEmpty() || it.startsWith("https://") || it.startsWith("http://"), "referencia HTTP(S) requerida") } }
    private fun JsonObject.hash(key: String, required: Boolean = false) { string(key, 64, required)?.let { check(digest.matches(it), "$key inválido") } }
    private fun JsonObject.timing(completed: Boolean = false) {
        val start = time("startedAt"); val end = time("completedAt", completed)
        check(start == null || end == null || !start.isAfter(end), "fin anterior al inicio")
        integer("plannedMinutes", 1, 1440); integer("actualMinutes", 1, 1440)
        uuid("activityId"); date("occurrenceDate")
        check(value("occurrenceDate") == null || value("activityId") != null, "ocurrencia sin actividad")
    }
    fun goal(row: JsonObject) {
        row.fields("id title description category priority targetDate status estimatedTotalMinutes")
        row.nullable("targetDate estimatedTotalMinutes")
        row.uuid("id", true); row.string("title", 200, true); row.string("description", 4000); row.string("category", 80)
        row.integer("priority", 1, 4); row.integer("estimatedTotalMinutes", 5, 1000000); row.date("targetDate")
        row.enum("status", "active|paused|completed|archived")
    }
    fun command(body: JsonObject, kind: String, goals: Set<String>) {
        if(kind == "goal") { goal(body); return }
        body.nullable(if(kind == "resource") "sourceUrl contentDigest" else "assessment sessionId activityId occurrenceDate startedAt plannedMinutes actualMinutes evaluation recommendedNextTopic nextEstimatedMinutes sourceUrl externalReference sourceContentDigest")
        body.uuid("requestId", true); check(body.uuid("goalId", true) in goals, "objetivo del comando inexistente")
        check(body.bool("confirmed", true) == true, "comando sin confirmación")
        if(kind == "resource") {
            body.fields("requestId goalId title summary sourceType sourceUrl contentDigest confirmed")
            body.string("title", 200, true); body.string("summary", 2000); body.hash("contentDigest"); body.url("sourceUrl")
        } else {
            check(kind == "progress", "comando desconocido")
            body.fields("assessment requestId goalId topic subject notes sessionId activityId occurrenceDate startedAt completedAt plannedMinutes actualMinutes understood needsReinforcement difficulties exercises subtopics evaluation pendingTasks recommendedNextTopic nextEstimatedMinutes sourceType sourceTitle sourceUrl externalReference sourceContentDigest confirmed")
            body.string("topic", 200, true); body.string("subject", 200); body.string("notes", 4000); body.uuid("sessionId")
            body.timing(true); body.string("evaluation", 2000); body.string("recommendedNextTopic", 200); body.integer("nextEstimatedMinutes", 5, 1440)
            listOf("understood", "needsReinforcement", "difficulties", "exercises", "subtopics").forEach { body.texts(it, nonblank = true) }; body.texts("pendingTasks", 20, true)
            body.string("sourceTitle", 200); body.string("externalReference", 200); body.url("sourceUrl"); body.hash("sourceContentDigest")
            body.value("assessment")?.let { v ->
                check(v is JsonObject, "evaluación inválida"); val a = v.jsonObject
                a.fields("correctCount totalCount method confidence")
                val correct = a.integer("correctCount", 0, 10000, true)!!; val total = a.integer("totalCount", 1, 10000, true)!!
                check(correct <= total && a.fraction("confidence") != null, "evaluación fuera de rango"); a.string("method", 1000, true)
            }
        }
        body.enum("sourceType", "chatgpt|notebooklm|text|pdf|link|manual")
    }
    fun snapshot(snapshot: LearningSnapshot) {
        check(snapshot.envelope.revision >= 0, "revisión negativa")
        val memory = snapshot.envelope.memory
        memory.fields("schemaVersion goals milestones topics competencies sessions events sources nextSteps receipts preferences")
        check(memory.integer("schemaVersion", 1, 1, true) == 1, "versión incompatible")
        val limits = listOf(1000,5000,5000,5000,10000,10000,10000,5000,20000)
        val rows = collections.mapIndexed { index, key -> key to memory.array(key, limits[index], true).map { check(it is JsonObject, "entidad inválida en $key"); it.jsonObject } }.toMap()
        val nullableFields = mapOf(
            "goals" to "targetDate estimatedTotalMinutes", "milestones" to "estimatedMinutes completedAt", "topics" to "parentTopicId",
            "competencies" to "estimatedLevel lastEvaluatedAt", "sessions" to "topicId activityId occurrenceDate startedAt completedAt plannedMinutes actualMinutes",
            "events" to "topicId sessionId sourceReferenceId evaluation", "sources" to "sourceUrl externalReference", "nextSteps" to "topicId estimatedMinutes activityId", "receipts" to "goalId"
        )
        rows.forEach { (key, values) ->
            values.forEach { it.nullable(nullableFields.getValue(key)) }
            val ids = values.map { it.uuid(if(key == "receipts") "requestId" else "id", true)!! }
            check(ids.distinct().size == ids.size, "identificadores duplicados en $key")
        }
        val goals = rows.getValue("goals").associateBy { it.text("id") }
        val topics = rows.getValue("topics").associateBy { it.text("id") }
        val sessions = rows.getValue("sessions").associateBy { it.text("id") }
        val sources = rows.getValue("sources").associateBy { it.text("id") }
        val events = rows.getValue("events").associateBy { it.text("id") }
        val steps = rows.getValue("nextSteps").associateBy { it.text("id") }
        rows.getValue("goals").forEach { goal(it) }
        listOf("milestones", "topics", "sessions", "events", "nextSteps").forEach { key -> rows.getValue(key).forEach { row ->
            check(row.uuid("goalId", true) in goals, "objetivo inexistente en $key")
            row.uuid("topicId")?.let { check(topics[it]?.text("goalId") == row.text("goalId"), "tema de otro objetivo o inexistente") }
        } }
        rows.getValue("milestones").forEach { r ->
            r.fields("id goalId title description order status estimatedMinutes completedAt"); r.string("title",200,true); r.string("description",2000)
            r.integer("order",0,10000); r.enum("status","pending|active|completed"); r.integer("estimatedMinutes",5,100000); r.time("completedAt")
        }
        topics.values.forEach { r ->
            r.fields("id goalId title subject description parentTopicId"); r.string("title",200,true); r.string("subject",200); r.string("description",2000)
            val seen = mutableSetOf(r.text("id")); var parent = r.uuid("parentTopicId")
            while(parent != null) {
                check(seen.add(parent) && topics[parent]?.text("goalId") == r.text("goalId"), "jerarquía de temas inválida")
                parent = topics.getValue(parent).uuid("parentTopicId")
            }
        }
        sessions.values.forEach { r ->
            r.fields("id goalId topicId activityId occurrenceDate startedAt completedAt plannedMinutes actualMinutes status notes")
            r.enum("status","planned|in_progress|completed|skipped"); r.timing(r.text("status") == "completed"); r.string("notes",4000)
        }
        sources.values.forEach { r ->
            r.fields("id sourceType sourceTitle sourceUrl externalReference importedAt contentDigest")
            r.enum("sourceType","chatgpt|notebooklm|text|pdf|link|manual"); r.string("sourceTitle",200); r.url("sourceUrl"); r.string("externalReference",200)
            r.time("importedAt",true); r.hash("contentDigest",true)
        }
        events.values.forEach { r ->
            r.fields("id goalId topicId sessionId eventType description occurredAt sourceReferenceId evidenceType understood needsReinforcement difficulties exercises evaluation subtopics")
            // Description may be empty, but the field must exist in v1.
            check(r.value("description") != null, "evento sin descripción"); r.string("description",4000); r.time("occurredAt",true)
            r.enum("eventType","session|assessment|correction|note"); r.enum("evidenceType","confirmed|estimate|recommendation")
            r.uuid("sessionId")?.let { check(sessions[it]?.text("goalId") == r.text("goalId"), "sesión de evidencia inválida") }
            r.uuid("sourceReferenceId")?.let { check(it in sources,"fuente inexistente") }
            listOf("understood","needsReinforcement","difficulties","exercises","subtopics").forEach { r.texts(it) }; r.string("evaluation",2000)
        }
        rows.getValue("competencies").forEach { r ->
            r.fields("id topicId estimatedLevel confidence lastEvaluatedAt evidenceIds assessmentMethod")
            val topic = r.uuid("topicId",true); check(topic in topics,"tema de competencia inexistente")
            val evidence = r.ids("evidenceIds",1000)
            evidence.forEach { check(events[it]?.text("topicId") == topic && events[it]?.text("evidenceType").let { type -> type == "confirmed" || (type == "" && events[it] != null) }, "evidencia de competencia inválida") }
            val level = r.fraction("estimatedLevel"); val confidence = r.fraction("confidence") ?: 0.0
            val method = r.string("assessmentMethod",1000).orEmpty(); r.time("lastEvaluatedAt")
            check(if(level == null) confidence == 0.0 else evidence.isNotEmpty() && method.isNotBlank(), "competencia sin justificación")
        }
        steps.values.forEach { r ->
            r.fields("id goalId topicId description estimatedMinutes priority dependencies status activityId rationale isEstimate")
            r.string("description",2000,true); r.integer("estimatedMinutes",5,1440); r.integer("priority",1,4); r.enum("status","pending|active|completed|cancelled")
            r.uuid("activityId"); r.string("rationale",2000); r.bool("isEstimate")
            r.ids("dependencies",100).forEach { check(steps[it]?.text("goalId") == r.text("goalId"),"dependencia inexistente o de otro objetivo") }
        }
        var remaining = steps.mapValues { it.value.ids("dependencies",100).toSet() }
        while(remaining.isNotEmpty()) {
            val ready = remaining.filterValues { it.isEmpty() }.keys
            check(ready.isNotEmpty(),"dependencias cíclicas")
            remaining = remaining.filterKeys { it !in ready }.mapValues { it.value - ready }
        }
        rows.getValue("receipts").forEach { r ->
            r.fields("requestId goalId fingerprint entityId"); r.hash("fingerprint",true)
            val entity = r.uuid("entityId",true); check(entity in sessions || entity in sources,"entidad del recibo inexistente")
            r.uuid("goalId")?.let { check(it in goals,"objetivo del recibo inexistente"); check(entity !in sessions || sessions[entity]?.text("goalId") == it,"recibo de otro objetivo") }
        }
        check(memory["preferences"] != JsonNull,"preferencias nulas")
        memory.value("preferences")?.let { v ->
            check(v is JsonObject,"preferencias inválidas"); val p = v.jsonObject
            p.nullable("")
            p.fields("automaticEnabled maxShiftMinutes autonomy preferredSessionMinutes breakMinutes dailyStudyLimitMinutes maxAutomaticMoves")
            p.bool("automaticEnabled"); p.enum("autonomy","suggestions|bounded"); p.integer("maxShiftMinutes",5,60); p.integer("preferredSessionMinutes",5,180)
            p.integer("breakMinutes",0,60); p.integer("dailyStudyLimitMinutes",15,720); p.integer("maxAutomaticMoves",0,5)
        }
        snapshot.active?.let { a ->
            buildJsonObject { put("activityId",a.activityId); put("startedAt",a.startedAt); a.occurrenceDate?.let { put("occurrenceDate",it) } }.let { r -> r.uuid("activityId",true); r.time("startedAt",true); r.date("occurrenceDate") }
        }
        check(snapshot.pending.size <= 20000 && snapshot.pending.map { it.id }.distinct().size == snapshot.pending.size,"cola duplicada o demasiado grande")
        val requests = mutableMapOf<String, Pair<String, JsonObject>>()
        snapshot.pending.forEach { c ->
            buildJsonObject { put("id",c.id) }.uuid("id",true); check(c.kind in listOf("goal","progress","resource"),"comando desconocido")
            command(c.body,c.kind,goals.keys)
            if(c.kind == "goal") check(goals[c.body.text("id")]?.let { canonicalEntity(it,"goals") } == canonicalEntity(c.body,"goals"),"objetivo pendiente diferente")
            else {
                val key = c.body.text("requestId"); val value = c.kind to canonicalLearningCommand(c.body,c.kind)
                check(requests.put(key,value).let { it == null || it == value },"requestId reutilizado con otros datos")
                rows.getValue("receipts").firstOrNull { it.text("requestId") == key }?.let { receipt ->
                    check(receipt.text("fingerprint") == learningRequestFingerprint(c.body,c.kind),"comando diferente del recibo confirmado")
                }
                if(c.kind == "progress") {
                    val optimistic = c.body.text("sessionId").ifBlank { key }
                    val receipt = rows.getValue("receipts").firstOrNull { it.text("requestId") == key }
                    val stored = sessions[optimistic] ?: receipt?.let { sessions[it.text("entityId")] }
                    check(stored != null,"falta la sesión del comando pendiente")
                    val s = canonicalEntity(stored!!,"sessions"); val command = canonicalEntity(c.body,"progress")
                    check(s.text("status") == "completed" && listOf("goalId","completedAt","actualMinutes","notes").all { s[it] == command[it] },"sesión pendiente diferente")
                    // Older Android placeholders omitted timer and occurrence fields.
                    listOf("activityId","occurrenceDate","startedAt","plannedMinutes").forEach { field ->
                        check(s[field] == null || command[field] == null || s[field] == command[field],"identidad o tiempo del comando diferente")
                    }
                }
            }
        }
    }
}

// Defaults are those of learning_models.py. Compare values, not object key/collection order.
private val learningDefaults = mapOf(
    "goals" to "{\"description\":\"\",\"category\":\"Aprendizaje\",\"priority\":2,\"status\":\"active\"}",
    "milestones" to "{\"description\":\"\",\"order\":0,\"status\":\"pending\"}",
    "topics" to "{\"subject\":\"\",\"description\":\"\"}",
    "competencies" to "{\"confidence\":0,\"evidenceIds\":[],\"assessmentMethod\":\"\"}",
    "sessions" to "{\"status\":\"planned\",\"notes\":\"\"}",
    "sources" to "{\"sourceType\":\"manual\",\"sourceTitle\":\"\"}",
    "events" to "{\"eventType\":\"note\",\"evidenceType\":\"confirmed\",\"understood\":[],\"needsReinforcement\":[],\"difficulties\":[],\"exercises\":[],\"subtopics\":[]}",
    "nextSteps" to "{\"priority\":2,\"dependencies\":[],\"status\":\"pending\",\"rationale\":\"\",\"isEstimate\":true}",
    "preferences" to "{\"automaticEnabled\":false,\"maxShiftMinutes\":30,\"autonomy\":\"suggestions\",\"preferredSessionMinutes\":45,\"breakMinutes\":10,\"dailyStudyLimitMinutes\":180,\"maxAutomaticMoves\":2}",
    "progress" to "{\"subject\":\"\",\"notes\":\"\",\"understood\":[],\"needsReinforcement\":[],\"difficulties\":[],\"exercises\":[],\"subtopics\":[],\"pendingTasks\":[],\"sourceType\":\"manual\",\"sourceTitle\":\"\"}",
    "resource" to "{\"summary\":\"\",\"sourceType\":\"text\"}"
)
private fun normalizedLearningValue(value: JsonElement, key: String = ""): JsonElement = when(value) {
    is JsonObject -> JsonObject(value.mapValues { normalizedLearningValue(it.value,it.key) })
    is JsonArray -> JsonArray(value.map { normalizedLearningValue(it,key) })
    is JsonPrimitive -> if(value.isString) {
        var text = value.content.trim()
        if(key.endsWith("At")) text = runCatching {
            val at = OffsetDateTime.parse(text)
            at.format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")) +
                (if(at.nano / 1000 == 0) "" else "." + (at.nano / 1000).toString().padStart(6,'0')) + at.offset.id
        }.getOrDefault(text)
        if(key == "id" || key.endsWith("Id")) text = runCatching { UUID.fromString(text).toString() }.getOrDefault(text)
        JsonPrimitive(text)
    } else if(value != JsonNull && value.booleanOrNull == null && value.doubleOrNull != null) {
        if(key == "confidence" || key == "estimatedLevel") JsonPrimitive(value.double) else json.parseToJsonElement(java.math.BigDecimal(value.content).stripTrailingZeros().toPlainString())
    } else value
}
internal fun canonicalEntity(body: JsonObject, kind: String): JsonObject {
    val defaults = learningDefaults[kind]?.let { json.parseToJsonElement(it).jsonObject }.orEmpty()
    return normalizedLearningValue(JsonObject((defaults + body).filterValues { it != JsonNull })).jsonObject
}
internal fun canonicalLearningCommand(body: JsonObject, kind: String): JsonObject =
    JsonObject(canonicalEntity(body,kind).filterKeys { it != "requestId" && it != "confirmed" })

internal fun containsLearningMemory(container: JsonObject, expected: JsonObject): Boolean =
    LearningIntegrity.collections.all { key ->
        val identity = if(key == "receipts") "requestId" else "id"
        val remote = container.rows(key).associateBy { it.text(identity) }
        expected.rows(key).all { local -> remote[local.text(identity)]?.let { canonicalEntity(it,key) } == canonicalEntity(local,key) }
    } && canonicalEntity(container["preferences"] as? JsonObject ?: buildJsonObject {},"preferences") ==
        canonicalEntity(expected["preferences"] as? JsonObject ?: buildJsonObject {},"preferences")

internal fun sameLearningMemory(first: JsonObject, second: JsonObject): Boolean =
    containsLearningMemory(first,second) && containsLearningMemory(second,first)

/** Same SHA-256 input as the server: validated command, defaults omitted, sorted JSON keys.
 * Used only to verify persisted receipts, never to generate an independent request identity.
 */
internal fun learningRequestFingerprint(body: JsonObject, kind: String): String {
    val defaults = canonicalEntity(buildJsonObject {},kind)
    val content = JsonObject(canonicalLearningCommand(body,kind).filter { (key,value) -> defaults[key] != value })
    fun encode(value: JsonElement, key: String = ""): String = when(value) {
        is JsonObject -> value.toSortedMap().entries.joinToString(", ","{","}") { JsonPrimitive(it.key).toString() + ": " + encode(it.value,it.key) }
        is JsonArray -> value.joinToString(", ","[","]") { encode(it,key) }
        is JsonPrimitive -> if(key == "confidence" && !value.isString && value != JsonNull) {
            val n = value.double
            if(n == 0.0) { if(value.content.startsWith("-")) "-0.0" else "0.0" } else {
                // Python emits the shortest round-trip decimal, with scientific
                // notation below 1e-4. Confidence is restricted to finite [0, 1].
                val decimal = (1..17).asSequence().map { precision ->
                    java.math.BigDecimal(n).round(java.math.MathContext(precision,java.math.RoundingMode.HALF_EVEN))
                }.first { it.toDouble() == n }.stripTrailingZeros()
                if(n >= 0.0001) decimal.toPlainString().let { if('.' in it) it else "$it.0" }
                else {
                    val exponent = decimal.precision() - decimal.scale() - 1
                    decimal.movePointLeft(exponent).toPlainString() + "e-" + (-exponent).toString().padStart(2,'0')
                }
            }
        } else value.toString()
    }
    return java.security.MessageDigest.getInstance("SHA-256").digest(encode(content).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
