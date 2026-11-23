package com.calenflex.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID

fun goalLabel(goal: LearningGoalDto, goals: List<LearningGoalDto>): String = if(goals.count { it.title == goal.title } <= 1) goal.title else "${goal.title} · ${goal.description.ifBlank { goal.category }.take(35)} · ${goals.indexOf(goal) + 1}"

@Composable fun TodayScreen(state: State, learning: LearningSnapshot, begin: (Activity, String) -> Unit, finish: () -> Unit, generate: () -> Unit, detail: (Activity) -> Unit, cancelActive: () -> Unit) {
    val day = today(state.preferences.timezone).toString()
    val slots = state.schedule.filter { it.date == day && !it.completed }.sortedBy { it.start }
    val active = learning.active?.let { running -> state.activities.find { it.id == running.activityId } }
    val next = slots.firstOrNull()?.let { slot -> state.activities.find { it.id == slot.activityId } to slot }
    Page("Hola${state.preferences.name.takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty()}") {
        Text(today(state.preferences.timezone).format(java.time.format.DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", java.util.Locale.forLanguageTag("es-AR"))))
        if(active != null) {
            ElevatedCard { Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("En curso", color = MaterialTheme.colorScheme.primary)
                Text(active.title, style = MaterialTheme.typography.headlineSmall)
                Text("Registrá lo que trabajaste cuando termines. Podés corregir el tiempo antes de guardar.")
                Button(onClick = finish) { Text("Finalizar sesión") }
                TextButton(onClick = cancelActive) { Text("Detener sin completar") }
            } }
        } else if(learning.active != null) {
            Empty("La actividad que estaba en curso ya no está disponible.")
            TextButton(onClick = cancelActive) { Text("Cerrar sesión en curso") }
        } else if(next?.first != null) {
            val a = next.first!!; val slot = next.second
            ElevatedCard { Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Tu próxima actividad", color = MaterialTheme.colorScheme.primary)
                Text(a.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("${clock(slot.start)} · ${a.duration} minutos · ${a.category}")
                a.goalId?.let { id -> learning.goals().find { it.id == id }?.let { Text("Objetivo: ${it.title}") } }
                Button(onClick = { begin(a, slot.date) }) { Text("Comenzar") }
            } }
        } else Empty("Hoy tenés espacio para avanzar a tu ritmo. Agregá una actividad o revisá tu planificación.")
        if(slots.size > 1) Text("Después", style = MaterialTheme.typography.titleMedium)
        slots.drop(1).forEach { slot -> state.activities.find { it.id == slot.activityId }?.let { a -> OutlinedCard(onClick = { detail(a) }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(a.title, fontWeight = FontWeight.Bold); Text("${clock(slot.start)} · ${a.duration} min") } } } }
        OutlinedButton(onClick = generate) { Text("Revisar mi planificación") }
    }
}

@Composable fun GoalsScreen(snapshot: LearningSnapshot, create: () -> Unit, edit: (LearningGoalDto) -> Unit, remove: (LearningGoalDto) -> Unit, register: (LearningGoalDto) -> Unit, sync: () -> Unit) {
    val context = LocalContext.current
    Page("Objetivos") {
        Text("Lo que querés aprender, con avances basados en tus sesiones.")
        if(snapshot.pending.isNotEmpty()) Text("${snapshot.pending.size} registros guardados en este teléfono, pendientes de sincronización", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = create) { Text("Nuevo objetivo") }; TextButton(onClick = sync) { Text("Actualizar") } }
        val goals = snapshot.goals()
        if(goals.isEmpty()) Empty("Elegí algo que quieras aprender. No necesitás definir todo el plan ahora.")
        goals.forEach { goal ->
            val sessions = snapshot.envelope.memory.rows("sessions").filter { it.text("goalId") == goal.id && it.text("status") == "completed" }
            val minutes = sessions.sumOf { it.number("actualMinutes") ?: 0 }
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(goal.title, style = MaterialTheme.typography.titleLarge)
                if(goal.description.isNotBlank()) Text(goal.description)
                val topicIds = snapshot.envelope.memory.rows("topics").filter { it.text("goalId") == goal.id }.map { it.text("id") }
                snapshot.envelope.memory.rows("competencies").filter { it.text("topicId") in topicIds }.forEach { competency ->
                    Text("Última evaluación: ${competency.text("assessmentMethod")} · nivel ${competency.text("estimatedLevel")} · confianza declarada ${competency.text("confidence")}", style = MaterialTheme.typography.bodySmall)
                }
                Text("${sessions.size} ${if(sessions.size == 1) "sesión" else "sesiones"} · $minutes min registrados")
                if(sessions.any { it.number("actualMinutes") == null }) Text("Hay sesiones con duración desconocida", style = MaterialTheme.typography.bodySmall)
                goal.estimatedTotalMinutes?.let { target -> LinearProgressIndicator(progress = { (minutes.toFloat() / target).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()); Text("Tiempo orientativo: $minutes de $target min; no representa dominio", style = MaterialTheme.typography.labelSmall) }
                snapshot.insights.filter { it.text("goalId") == goal.id }.take(2).forEach { Text(it.text("explanation"), style = MaterialTheme.typography.bodySmall); Text(it.text("recommendation"), style = MaterialTheme.typography.bodyMedium) }
                val milestones = snapshot.envelope.memory.rows("milestones").filter { it.text("goalId") == goal.id && it.text("status") != "completed" }
                milestones.take(2).forEach { Text("Próximo hito: ${it.text("title")}") }
                snapshot.pending.filter { it.kind == "resource" && it.body.text("goalId") == goal.id }.take(3).forEach { Text("Recurso: ${it.body.text("title")} · pendiente de sincronizar", style = MaterialTheme.typography.bodySmall) }
                snapshot.envelope.memory.rows("events").filter { it.text("goalId") == goal.id && it.text("eventType") == "note" }.takeLast(3).forEach { event ->
                    Text(event.text("description"), style = MaterialTheme.typography.bodySmall)
                    val url = snapshot.envelope.memory.rows("sources").find { it.text("id") == event.text("sourceReferenceId") }?.text("sourceUrl").orEmpty()
                    if(url.startsWith("https://") || url.startsWith("http://")) TextButton(onClick = { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }) { Text("Abrir referencia") }
                }
                val next = snapshot.envelope.memory.rows("nextSteps").filter { it.text("goalId") == goal.id && it.text("status") in listOf("pending", "active") }
                next.take(2).forEach { Text("Siguiente paso: ${it.text("description")}", style = MaterialTheme.typography.bodyMedium) }
                Row { TextButton(onClick = { register(goal) }) { Text("Registrar sesión") }; TextButton(onClick = { edit(goal) }) { Text("Corregir") }; TextButton(onClick = { remove(goal) }) { Text("Eliminar") } }
                sessions.takeLast(3).reversed().forEach { Text("${it.text("completedAt").take(10)} · ${it.number("actualMinutes")?.let { m -> "$m min" } ?: "Duración no registrada"} · ${it.text("notes")}", style = MaterialTheme.typography.bodySmall) }
            } }
        }
    }
}

@Composable fun GoalEditor(original: LearningGoalDto?, dismiss: () -> Unit, save: (LearningGoalDto) -> Unit) {
    var title by remember { mutableStateOf(original?.title.orEmpty()) }
    var description by remember { mutableStateOf(original?.description.orEmpty()) }
    var date by remember { mutableStateOf(original?.targetDate.orEmpty()) }
    var status by remember { mutableStateOf(original?.status ?: "active") }
    var error by remember { mutableStateOf("") }
    FullDialog(if(original == null) "Nuevo objetivo" else "Corregir objetivo", dismiss) {
        Field("¿Qué querés aprender?", title) { title = it }
        Field("Qué te gustaría lograr (opcional)", description) { description = it }
        Field("Fecha orientativa (AAAA-MM-DD, opcional)", date) { date = it }
        val statuses = linkedMapOf("Activo" to "active", "En pausa" to "paused", "Completado" to "completed", "Archivado" to "archived")
        Choice("Estado", statuses.entries.find { it.value == status }?.key ?: "Activo", statuses.keys.toList()) { status = statuses.getValue(it) }
        if(error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(onClick = { try { require(title.isNotBlank() && title.length <= 200) { "Indicá un título de hasta 200 caracteres" }; date.takeIf { it.isNotBlank() }?.let { java.time.LocalDate.parse(it) }; save((original ?: LearningGoalDto(title = title.trim())).copy(title = title.trim(), description = description, targetDate = date.ifBlank { null }, status = status)) } catch(e: Exception) { error = "Revisá el título y la fecha" } }) { Text("Guardar objetivo") }
    }
}

@Composable fun ProgressEditor(snapshot: LearningSnapshot, initialGoal: String?, topicHint: String = "", minutesHint: Int? = null, activityId: String? = null, imported: JsonObject? = null, startedAt: String? = null, occurrenceDate: String? = null, plannedMinutes: Int? = null, timezone: String = "America/Argentina/Buenos_Aires", completeWithoutStudy: (() -> Unit)? = null, dismiss: () -> Unit, save: (JsonObject) -> Unit) {
    var selected by remember { mutableStateOf(initialGoal) }
    var topic by remember { mutableStateOf(imported?.text("topic")?.takeIf { it.isNotBlank() } ?: topicHint) }
    var actual by remember { mutableStateOf(imported?.number("actualMinutes")?.toString() ?: minutesHint?.toString().orEmpty()) }
    var understood by remember { mutableStateOf((imported?.get("understood") as? JsonArray)?.joinToString("\n") { it.jsonPrimitive.content }.orEmpty()) }
    var reinforce by remember { mutableStateOf((imported?.get("needsReinforcement") as? JsonArray)?.joinToString("\n") { it.jsonPrimitive.content }.orEmpty()) }
    var difficulties by remember { mutableStateOf((imported?.get("difficulties") as? JsonArray)?.joinToString("\n") { it.jsonPrimitive.content }.orEmpty()) }
    var subject by remember { mutableStateOf(imported?.text("subject").orEmpty()) }
    var subtopics by remember { mutableStateOf((imported?.get("subtopics") as? JsonArray)?.joinToString("\n") { it.jsonPrimitive.content }.orEmpty()) }
    var exercises by remember { mutableStateOf((imported?.get("exercises") as? JsonArray)?.joinToString("\n") { it.jsonPrimitive.content }.orEmpty()) }
    var evaluation by remember { mutableStateOf(imported?.text("evaluation").orEmpty()) }
    var assessed by remember { mutableStateOf(false) }
    var correct by remember { mutableStateOf("") }
    var total by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("") }
    var confidence by remember { mutableStateOf("0.5") }
    var moreDetails by remember { mutableStateOf(imported != null) }
    var notes by remember { mutableStateOf(imported?.text("summary").orEmpty()) }
    var next by remember { mutableStateOf(imported?.text("recommendedNextTopic").orEmpty()) }
    var completedDate by remember { mutableStateOf(today(timezone).toString()) }
    val completedTime = remember { java.time.LocalTime.now(java.time.ZoneId.of(timezone)) }
    var error by remember { mutableStateOf("") }
    val requestId = remember { UUID.randomUUID().toString() }
    FullDialog("Registrar lo que trabajaste", dismiss) {
        completeWithoutStudy?.let { complete -> TextButton(onClick = complete) { Text("Completar actividad sin registro de estudio") } }
        Text("Guardamos tu registro, sin asumir que ya dominás el tema. Revisá los datos antes de confirmar.")
        if(snapshot.goals().isEmpty()) Empty("Primero creá un objetivo para asociar la sesión.")
        snapshot.goals().forEach { goal -> FilterChip(selected = selected == goal.id, onClick = { selected = goal.id }, label = { Text(goalLabel(goal, snapshot.goals())) }) }
        occurrenceDate?.let { Text("Ocurrencia seleccionada: $it") }
        Field("Fecha de la sesión (AAAA-MM-DD)", completedDate) { completedDate = it }
        Field("Tema trabajado", topic) { topic = it }
        Field("Duración real en minutos (opcional)", actual) { actual = it }
        if(minutesHint != null) Text("El cronómetro mide tiempo transcurrido; corregilo si hubo pausas.", style = MaterialTheme.typography.bodySmall)
        Field("Qué comprendiste (un contenido por línea)", understood) { understood = it }
        Field("Qué necesita refuerzo", reinforce) { reinforce = it }
        Field("Dificultades detectadas", difficulties) { difficulties = it }
        Field("Materia o área (opcional)", subject) { subject = it }
        TextButton(onClick = { moreDetails = !moreDetails }) { Text(if(moreDetails) "Ocultar detalles" else "Agregar subtemas, ejercicios o evaluación") }
        if(moreDetails) {
            Row { Checkbox(assessed, { assessed = it }); Text("Registrar evaluación con resultados") }
            if(assessed) {
                Field("Resultados correctos", correct) { correct = it }
                Field("Total evaluado", total) { total = it }
                Field("Método de evaluación", method) { method = it }
                Field("Confianza declarada (0 a 1)", confidence) { confidence = it }
                Text("Es una estimación declarada basada en estos resultados, no una certificación de dominio.")
            }
            Field("Subtemas (uno por línea)", subtopics) { subtopics = it }
            Field("Ejercicios realizados (uno por línea)", exercises) { exercises = it }
            Field("Evaluación, si existe", evaluation) { evaluation = it }
        }
        Field("Notas", notes) { notes = it }
        Field("Qué conviene continuar (opcional)", next) { next = it }
        if(error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(onClick = { try {
            require(selected != null && topic.isNotBlank())
            val minutes = actual.takeIf { it.isNotBlank() }?.toInt(); require(minutes == null || minutes in 1..1440)
            save(buildJsonObject {
                put("requestId", requestId); put("goalId", selected!!); put("topic", topic.trim())
                put("completedAt", java.time.LocalDate.parse(completedDate).atTime(completedTime).atZone(java.time.ZoneId.of(timezone)).toInstant().toString()); put("actualMinutes", minutes?.let { JsonPrimitive(it) } ?: JsonNull)
                put("notes", notes); put("confirmed", true); put("sourceType", if(imported == null) "manual" else imported.text("_sourceType").ifBlank { "text" })
                put("understood", JsonArray(understood.lines().filter { it.isNotBlank() }.map { JsonPrimitive(it.trim()) }))
                put("needsReinforcement", JsonArray(reinforce.lines().filter { it.isNotBlank() }.map { JsonPrimitive(it.trim()) }))
                if(activityId != null) put("activityId", activityId)
                if(startedAt != null) put("startedAt", startedAt)
                if(occurrenceDate != null) put("occurrenceDate", occurrenceDate)
                if(plannedMinutes != null) put("plannedMinutes", plannedMinutes)
                if(assessed) {
                    val c = correct.toInt(); val t = total.toInt(); val f = confidence.toDouble()
                    require(t in 1..10000 && c in 0..t && f in 0.0..1.0 && method.isNotBlank())
                    put("assessment", buildJsonObject { put("correctCount",c);put("totalCount",t);put("method",method);put("confidence",f) })
                }
                put("subject", subject)
                put("subtopics", JsonArray(subtopics.lines().filter { it.isNotBlank() }.map { JsonPrimitive(it.trim()) }))
                put("exercises", JsonArray(exercises.lines().filter { it.isNotBlank() }.map { JsonPrimitive(it.trim()) }))
                if(evaluation.isNotBlank()) put("evaluation", evaluation)
                put("difficulties", JsonArray(difficulties.lines().filter { it.isNotBlank() }.map { JsonPrimitive(it.trim()) }))
                imported?.text("_sourceDigest")?.takeIf { it.isNotBlank() }?.let { put("sourceContentDigest", it) }
                if(next.isNotBlank()) put("recommendedNextTopic", next)
            })
        } catch(e: Exception) { error = "Elegí un objetivo y revisá tema y duración" } }) { Text("Confirmar y guardar sesión") }
    }
}

@Composable fun AssistantScreen(vm: AppViewModel, importContent: () -> Unit, review: (JsonObject) -> Unit) {
    var text by remember { mutableStateOf("") }
    val result by vm.latestLearningProposal.collectAsStateWithLifecycle()
    val turns by vm.learningConversation.collectAsStateWithLifecycle()
    val snapshot by vm.learning.collectAsStateWithLifecycle()
    var goal by remember { mutableStateOf<String?>(null) }
    var shareProgress by remember { mutableStateOf(false) }
    Page("Asistente") {
        Text("Contame qué querés lograr o compartí el resultado de una sesión. Las propuestas se revisan antes de guardar.")
        turns.forEach { turn -> Text((if(turn.text("role") == "user") "Vos: " else "CalenFleX: ") + turn.text("text")) }
        Choice("Objetivo", snapshot.goals().find { it.id == goal }?.let { goalLabel(it, snapshot.goals()) } ?: "Sin seleccionar", listOf("Sin seleccionar") + snapshot.goals().map { goalLabel(it, snapshot.goals()) }) { title -> goal = snapshot.goals().find { goalLabel(it, snapshot.goals()) == title }?.id; if(goal == null) shareProgress = false }
        Row { Checkbox(shareProgress, { shareProgress = it }, enabled = goal != null); Text("Compartir el progreso resumido de este objetivo", Modifier.padding(top = 12.dp)) }
        Field("¿En qué avanzamos?", text) { text = it }
        Text("Al analizar se envían este texto, hasta ocho mensajes recientes y títulos de hasta veinte objetivos activos. El progreso se comparte solo si lo activás. No se envía tu historial completo.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { vm.analyzeLearning(text, goal, shareProgress) { text = "" } }, enabled = text.isNotBlank()) { Text("Analizar y proponer") }
        OutlinedButton(onClick = importContent) { Text("Pegar contenido compartido") }
        result?.get("proposal")?.jsonObject?.let { proposal ->
            Text(proposal.text("summary"), style = MaterialTheme.typography.titleMedium)
            (proposal["questions"] as? JsonArray)?.forEach { Text(it.jsonPrimitive.content) }
            TextButton(onClick = { review(JsonObject(proposal.toMutableMap().apply { put("_sourceType", result?.get("sourceType") ?: JsonPrimitive("manual")); result?.get("contentDigest")?.let { put("_sourceDigest", it) } })) }) { Text("Revisar propuesta") }
        }
    }
}

@Composable fun ResourceEditor(snapshot: LearningSnapshot, draft: JsonObject, dismiss: () -> Unit, save: (JsonObject) -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf(draft.text("topic").ifBlank { "Recurso compartido" }) }
    var summary by remember { mutableStateOf(draft.text("summary")) }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val requestId = remember { UUID.randomUUID().toString() }
    FullDialog("Guardar recurso", dismiss) {
        Text("Una referencia de material no cuenta como una sesión realizada ni demuestra dominio.")
        snapshot.goals().forEach { goal -> FilterChip(selected == goal.id, { selected = goal.id }, label = { Text(goalLabel(goal, snapshot.goals())) }) }
        Field("Título del recurso", title) { title = it }
        Field("Resumen revisado", summary) { summary = it }
        Field("Enlace de origen (opcional)", url) { url = it }
        if(error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(onClick = { try {
            require(selected != null && title.isNotBlank() && title.length <= 200 && summary.length <= 2000)
            require(url.isBlank() || url.startsWith("https://") || url.startsWith("http://"))
            save(buildJsonObject { put("requestId", requestId); put("goalId", selected!!); put("title", title); put("summary", summary); put("confirmed", true); put("sourceType", draft.text("_sourceType").ifBlank { "text" }); if(url.isNotBlank()) put("sourceUrl", url); draft.text("_sourceDigest").takeIf { it.isNotBlank() }?.let { put("contentDigest", it) } })
        } catch(e: Exception) { error = "Elegí un objetivo y revisá título, resumen y enlace" } }) { Text("Confirmar y guardar recurso") }
    }
}
