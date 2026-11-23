package com.calenflex.app

import android.Manifest
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val incoming = MutableStateFlow<SharedContent?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receive(intent)
        setContent {
            val shared by incoming.collectAsStateWithLifecycle()
            Calenflex(incoming = shared, clearIncoming = { incoming.value?.files?.forEach { it.file.delete() }; incoming.value = null })
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receive(intent) }
    private fun receive(intent: Intent) {
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { readSharedIntent(this@MainActivity, intent) }?.let { incoming.value = it } }
            catch(e: Exception) { android.widget.Toast.makeText(this@MainActivity, e.message ?: "No se pudo abrir el contenido compartido", android.widget.Toast.LENGTH_LONG).show() }
        }
    }
}
private val teal = Color(0xFF006C63)
@Composable fun Calenflex(vm: AppViewModel = viewModel(), incoming: SharedContent? = null, clearIncoming: () -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val learning by vm.learning.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val dark = state.preferences.theme == "Oscuro" || state.preferences.theme == "Sistema" && isSystemInDarkTheme()
    val view = LocalView.current
    SideEffect {
        (view.context as? android.app.Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFF79D6C5)) else lightColorScheme(primary = teal, secondaryContainer = Color(0xFFDDEFEA), background = Color(0xFFF6F8F6))) {
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        var editing by remember { mutableStateOf<Activity?>(null) }
        var editingProposal by remember { mutableStateOf(false) }
        var occurrence by remember { mutableStateOf<Pair<String, String>?>(null) }
        var showAI by remember { mutableStateOf(false) }
        var goalEditor by remember { mutableStateOf(false) }
        var editedGoal by remember { mutableStateOf<LearningGoalDto?>(null) }
        var deletingGoal by remember { mutableStateOf<LearningGoalDto?>(null) }
        var finishingActive by remember { mutableStateOf(false) }
        var progressEditor by remember { mutableStateOf(false) }
        var selectedGoal by remember { mutableStateOf<String?>(null) }
        var proposalDraft by remember { mutableStateOf<JsonObject?>(null) }
        var resourceDraft by remember { mutableStateOf<JsonObject?>(null) }
        var proposalChoice by remember { mutableStateOf<JsonObject?>(null) }
        var pasteImport by remember { mutableStateOf(false) }
        var delete by remember { mutableStateOf<Activity?>(null) }
        var detail by remember { mutableStateOf<Activity?>(null) }
        val snackbar = remember { SnackbarHostState() }
        val tabs = listOf("Hoy", "Agenda", "Objetivos", "Asistente")
        val icons = listOf(Icons.Outlined.Home, Icons.Outlined.CalendarMonth, Icons.Outlined.Flag, Icons.Outlined.Forum)
        LaunchedEffect(message) { if (message.isNotBlank()) { snackbar.showSnackbar(message); vm.message.value = "" } }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = { Column(Modifier.statusBarsPadding().padding(horizontal = 22.dp, vertical = 14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("CalenFleX", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    IconButton(onClick = { nav.navigate("Estadísticas") }) { Icon(Icons.Outlined.Insights, "Ver estadísticas") }
                    IconButton(onClick = { nav.navigate("Configuración") }) { Icon(Icons.Outlined.Settings, "Configuración") }
                }
                Text("Tu tiempo, con espacio para vos", style = MaterialTheme.typography.labelMedium)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            } },
            bottomBar = { NavigationBar { tabs.forEachIndexed { i, label -> NavigationBarItem(
                selected = entry?.destination?.route == label || label == "Agenda" && entry?.destination?.route in listOf("Actividades", "Calendario"), onClick = { nav.navigate(label) { popUpTo(nav.graph.startDestinationId); launchSingleTop = true } },
                icon = { Icon(icons[i], contentDescription = label) }, label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) }) } } },
            floatingActionButton = { if (entry?.destination?.route in listOf("Hoy", "Agenda", "Actividades")) FloatingActionButton(onClick = { editing = Activity(startDate = today(state.preferences.timezone).toString()) }) { Icon(Icons.Outlined.Add, "Crear actividad") } }
        ) { padding ->
            NavHost(navController = nav, startDestination = "Hoy", modifier = Modifier.padding(padding)) {
                composable("Hoy") { TodayScreen(state, learning, { a -> vm.task { vm.learningRepository.begin(a.id) } }, { finishingActive = true; progressEditor = true; selectedGoal = state.activities.find { it.id == learning.active?.activityId }?.goalId?.takeIf { id -> learning.goals().any { it.id == id } }; proposalDraft = null }, { vm.generate() }, { detail = it }, { vm.task { vm.learningRepository.clearActive() } }) }
                composable("Agenda") { Column {
                    Row { TextButton(onClick = { nav.navigate("Actividades") }) { Text("Actividades") }; TextButton(onClick = { nav.navigate("Calendario") }) { Text("Calendario") } }
                    CalendarScreen(state, { detail = it }, { a, d -> vm.complete(a, d) })
                } }
                composable("Objetivos") { GoalsScreen(learning, { editedGoal = null; goalEditor = true }, { editedGoal = it; goalEditor = true }, { deletingGoal = it }, { finishingActive = false; selectedGoal = it.id; proposalDraft = null; progressEditor = true }, { vm.task { vm.learningRepository.sync(); vm.message.value = "Objetivos actualizados" } }) }
                composable("Asistente") { AssistantScreen(vm, { pasteImport = true }, { proposalChoice = it }) }
                composable("Calendario") { CalendarScreen(state, { detail = it }, { a, d -> vm.complete(a, d) }) }
                composable("Actividades") { ActivitiesScreen(state, { editing = it }, { delete = it }, { vm.complete(it) }, { a -> vm.save(a.copy(id = UUID.randomUUID().toString(), status = "Pendiente", createdAt = "")) }) }
                composable("Estadísticas") { StatsScreen(state) }
                composable("Configuración") { ProfileScreen(state, vm) }
            }
        }
        if(goalEditor) GoalEditor(editedGoal, { goalEditor = false }) { goal ->
            val correcting = learning.goals().any { it.id == goal.id }
            vm.task { if(correcting) vm.learningRepository.correctGoal(goal) else vm.learningRepository.createGoal(goal); vm.message.value = "Objetivo guardado" }
            goalEditor = false
        }
        deletingGoal?.let { goal -> Confirm("Eliminar objetivo", "Se eliminarán este objetivo y sus registros educativos del servidor. La agenda histórica se conserva.", { deletingGoal = null }) { vm.task { vm.learningRepository.deleteGoal(goal.id) }; deletingGoal = null } }
        if(progressEditor) {
            val active = if(finishingActive) learning.active else null
            val activity = active?.let { a -> state.activities.find { it.id == a.activityId } }
            val elapsed = active?.let { runCatching { java.time.Duration.between(java.time.Instant.parse(it.startedAt), java.time.Instant.now()).toMinutes().coerceIn(1, 1440).toInt() }.getOrNull() }
            ProgressEditor(learning, selectedGoal, activity?.title.orEmpty(), elapsed, activity?.id, proposalDraft, startedAt = active?.startedAt, plannedMinutes = activity?.duration, completeWithoutStudy = if(activity == null) null else ({ vm.task { vm.learningRepository.clearActive() }; vm.complete(activity, today(state.preferences.timezone).toString()); progressEditor = false }), dismiss = { progressEditor = false }) { body ->
                vm.task {
                    vm.learningRepository.registerProgress(body)
                    if(activity != null) {
                        vm.repository.edit("Sesión finalizada") { snapshot -> snapshot.copy(activities = snapshot.activities.map { if(it.id == activity.id && it.frequency == "Única") it.copy(status = "Completada") else it }, schedule = snapshot.schedule.map { if(it.activityId == activity.id && it.date == today(snapshot.preferences.timezone).toString()) it.copy(completed = true) else it }) }
                    }
                    vm.message.value = "Sesión guardada; podés sincronizarla cuando tengas conexión"
                }
                progressEditor = false
            }
        }
        if(incoming != null || pasteImport) ImportReview(incoming, vm, { clearIncoming(); pasteImport = false }, { proposalChoice = it; clearIncoming(); pasteImport = false })
        resourceDraft?.let { draft -> ResourceEditor(learning, draft, { resourceDraft = null }) { body -> vm.task { vm.learningRepository.registerResource(body); vm.message.value = "Recurso guardado, pendiente de sincronización" }; resourceDraft = null } }
        proposalChoice?.let { proposed -> FullDialog("Elegí cómo registrar la propuesta", { proposalChoice = null }) {
            Text(proposed.text("summary"))
            Text("Revisá y corregí los datos. No se guardarán por analizar el contenido.")
            OutlinedButton(onClick = { resourceDraft = proposed; proposalChoice = null }) { Text("Guardar como recurso") }
            Button(onClick = { editedGoal = LearningGoalDto(title = proposed.text("proposedGoalTitle").ifBlank { proposed.text("topic").ifBlank { "Nuevo objetivo" } }); goalEditor = true; proposalChoice = null }) { Text("Crear objetivo con esta propuesta") }
            Button(onClick = { finishingActive = false; proposalDraft = proposed; selectedGoal = null; progressEditor = true; proposalChoice = null }) { Text("Registrar sesión revisada") }
            (proposed["tasks"] as? JsonArray)?.forEach { task -> val draft = task.jsonObject
                OutlinedButton(onClick = { editingProposal = true; editing = Activity(title = draft.text("title"), description = draft.text("description"), duration = draft.number("estimatedMinutes") ?: 45, priority = draft.number("priority") ?: 2, startDate = draft.text("startDate"), deadline = draft.text("deadline").takeIf { it.isNotBlank() }); proposalChoice = null }) { Text("Revisar actividad: ${draft.text("title")}") }
            }
        } }
        editing?.let { a -> ActivityEditor(a, { editing = null; occurrence = null; editingProposal = false }, advancedInitially = editingProposal) { next -> val source = occurrence; if(source == null) vm.save(next) else vm.exception(source.first, source.second, next); editing = null; occurrence = null; editingProposal = false } }
        delete?.let { a -> Confirm("Eliminar actividad", "Se eliminarán ${a.title} y sus horarios. Esta acción se sincronizará con el servidor.", { delete = null }) { vm.delete(a); delete = null } }
        detail?.let { a -> AlertDialog(onDismissRequest = { detail = null }, title = { Text(a.title) }, text = { Column {
            Text(a.description.ifBlank { "Sin descripción" }); Text("${a.category} · ${a.duration} min · ${a.status}")
            state.schedule.filter { it.activityId == a.id }.take(10).forEach { slot ->
                Text("${slot.date} · ${clock(slot.start)}–${clock(slot.end)}${if (slot.completed) " · Completada" else ""}")
                if(a.frequency != "Única" && !slot.completed) TextButton(onClick = {
                    occurrence = a.id to slot.date
                    editing = a.copy(id = UUID.randomUUID().toString(), frequency = "Única", startDate = slot.date, deadline = slot.date,
                        until = null, skippedDates = emptyList(), status = "Pendiente", createdAt = "", updatedAt = "")
                    detail = null
                }) { Text("Modificar esta ocurrencia") }
            }
        } }, confirmButton = { TextButton(onClick = { editing = a; detail = null }) { Text("Editar") } }, dismissButton = { TextButton(onClick = { detail = null }) { Text("Cerrar") } }) }
        preview?.let { result -> FullDialog("Revisar planificación", { vm.preview.value = null }) {
            Text("Los horarios se guardan únicamente cuando aceptás los cambios.")
            Text("Planificación anterior", fontWeight = FontWeight.Bold)
            if (state.schedule.isEmpty()) Text("Todavía no hay horarios")
            state.schedule.take(30).forEach { s -> Text("${state.activities.find { it.id == s.activityId }?.title} · ${s.date} ${clock(s.start)}") }
            Text("Nueva planificación", style = MaterialTheme.typography.titleLarge)
            result.schedule.forEach { s -> Text("${state.activities.find { it.id == s.activityId }?.title} · ${s.date} ${clock(s.start)}–${clock(s.end)}") }
            Text("Cambios y motivos", fontWeight = FontWeight.Bold)
            result.changes.forEach { Text(it) }
            Text("Sin asignar: ${result.pending.size} · Conflictos: ${result.conflicts.size}")
            result.pending.forEach { p -> Text("${state.activities.find { it.id == p.activityId }?.title}: ${p.reason}") }
            result.conflicts.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { vm.acceptPlan() }, enabled = result.conflicts.isEmpty() && !busy) { Text("Aceptar planificación") }
            OutlinedButton(onClick = { vm.preview.value = null }) { Text("Rechazar") }
        } }
        if (showAI) FullDialog("Asistente de CalenFleX", { showAI = false }) {
            var prompt by remember { mutableStateOf("") }
            Text("Se enviarán títulos, fechas y duraciones de hasta cincuenta actividades, junto con tu disponibilidad, al proveedor configurado en tu servidor. La IA propone; vos revisás y el motor planifica.")
            Field("¿En qué te ayudo?", prompt) { prompt = it }
            Button(onClick = { vm.ask(prompt) }, enabled = prompt.isNotBlank() && !busy) { Text("Consultar") }
            assistant?.let { answer -> Text(answer.answer); answer.proposedActivity?.let { proposed -> OutlinedButton(onClick = { editing = proposed; showAI = false }) { Text("Revisar actividad propuesta") } } }
        }
    }
}
@Composable fun Page(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        content()
    }
}
@Composable fun Metric(value: String, label: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier) { Column(Modifier.padding(16.dp)) { Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary); Text(label, style = MaterialTheme.typography.labelLarge) } }
}
@Composable fun Empty(text: String) { Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) { Text(text, Modifier.fillMaxWidth().padding(24.dp)) } }
@Composable fun Dashboard(state: State, generate: () -> Unit, ai: () -> Unit, pending: () -> Unit, detail: (Activity) -> Unit, complete: (Activity, String) -> Unit) {
    val day = today(state.preferences.timezone).toString()
    val slots = state.schedule.filter { it.date == day }.sortedBy { it.start }
    val finished = slots.count { it.completed }
    Page("Hola${state.preferences.name.takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty()}") {
        Text(today(state.preferences.timezone).format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale.forLanguageTag("es-AR"))), color = MaterialTheme.colorScheme.primary)
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) { Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Un día a tu ritmo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(if (slots.isEmpty()) "Agregá tus actividades y encontrá un lugar para cada una." else "Tenés ${slots.size} actividades para hoy. $finished completadas.")
            Button(onClick = generate) { Icon(Icons.Outlined.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Organizar mi semana") }
        } }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Metric("$finished/${slots.size}", "Completadas hoy", Modifier.weight(1f)); Metric("${slots.sumOf { it.end - it.start } / 60.0}", "Horas planificadas", Modifier.weight(1f)) }
        val unscheduled = state.activities.count { a -> a.status !in listOf("Completada", "Cancelada") && (state.schedule.none { it.activityId == a.id } || state.pending.any { it.activityId == a.id }) }
        OutlinedButton(onClick = pending, modifier = Modifier.fillMaxWidth()) { Text("Revisar pendientes · $unscheduled") }
        val late = state.activities.count { it.deadline != null && it.deadline < day && it.status !in listOf("Completada", "Cancelada") }
        if (late > 0) Text("$late actividades con fecha límite vencida", color = MaterialTheme.colorScheme.error)
        state.pending.take(5).forEach { p -> Text("${state.activities.find { it.id == p.activityId }?.title}: ${p.reason}", style = MaterialTheme.typography.bodySmall) }
        val free = freeWindows(state, LocalDate.parse(day)).sumOf { it.second - it.first }
        Text("${free / 60} h ${free % 60} min disponibles · ${if(slots.isEmpty()) 0 else finished * 100 / slots.size}% de cumplimiento hoy")
        val next = slots.firstOrNull { !it.completed && it.end > java.time.ZonedDateTime.now(java.time.ZoneId.of(state.preferences.timezone)).let { n -> n.hour * 60 + n.minute } }
        next?.let { Text("Próxima: ${state.activities.find { a -> a.id == it.activityId }?.title} a las ${clock(it.start)}", fontWeight = FontWeight.Bold) }
        Text("Tu agenda de hoy", style = MaterialTheme.typography.titleLarge)
        if (slots.isEmpty()) Empty("Tu agenda está libre. Creá una actividad con el botón +.")
        slots.forEach { slot -> state.activities.find { it.id == slot.activityId }?.let { a -> SlotCard(a, slot, { detail(a) }, { complete(a, slot.date) }) } }
        OutlinedButton(onClick = ai, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Forum, null); Spacer(Modifier.width(8.dp)); Text("Consultar al asistente") }
    }
}
@Composable fun SlotCard(a: Activity, slot: Slot, open: () -> Unit, complete: () -> Unit) {
    OutlinedCard(onClick = open, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("${clock(slot.start)} — ${clock(slot.end)}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge); Text(a.title, fontWeight = FontWeight.Bold); Text(a.category, style = MaterialTheme.typography.bodySmall) }
        IconButton(onClick = complete, enabled = !slot.completed) { Icon(if (slot.completed) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, if (slot.completed) "Completada" else "Completar ${a.title}") }
    } }
}
@Composable fun CalendarScreen(state: State, detail: (Activity) -> Unit, complete: (Activity, String) -> Unit) {
    var date by remember { mutableStateOf(today(state.preferences.timezone)) }
    var mode by remember { mutableStateOf("Semana") }
    val days = when(mode) { "Día" -> 1; "Mes" -> date.lengthOfMonth(); "Agenda" -> 31; else -> 7 }
    val first = if(mode == "Mes") date.withDayOfMonth(1) else date
    Page("Calendario") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Día", "Semana", "Mes", "Agenda").forEach { FilterChip(selected = mode == it, onClick = { mode = it }, label = { Text(it) }) } }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = { date = if(mode == "Mes") date.minusMonths(1) else date.minusDays(days.toLong()) }) { Icon(Icons.Outlined.ChevronLeft, "Período anterior") }
            TextButton(onClick = { date = today(state.preferences.timezone) }) { Text(date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("es-AR")))) }
            IconButton(onClick = { date = if(mode == "Mes") date.plusMonths(1) else date.plusDays(days.toLong()) }) { Icon(Icons.Outlined.ChevronRight, "Período siguiente") }
        }
        repeat(days) { offset ->
            val day = first.plusDays(offset.toLong())
            val slots = state.schedule.filter { it.date == day.toString() }.sortedBy { it.start }
            Text(day.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.forLanguageTag("es-AR"))), fontWeight = FontWeight.Bold)
            if(slots.isEmpty()) Text("Sin actividades programadas", style = MaterialTheme.typography.bodySmall)
            slots.forEach { slot -> state.activities.find { it.id == slot.activityId }?.let { a -> SlotCard(a, slot, { detail(a) }, { complete(a, slot.date) }) } }
            val free = freeWindows(state, day)
            Text("Espacios libres: " + if(free.isEmpty()) "sin disponibilidad" else free.joinToString(" · ") { "${clock(it.first)}–${clock(it.second)}" }, style = MaterialTheme.typography.labelSmall)
            HorizontalDivider()
        }
    }
}
@Composable fun ActivitiesScreen(state: State, edit: (Activity) -> Unit, delete: (Activity) -> Unit, complete: (Activity) -> Unit, duplicate: (Activity) -> Unit) {
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("Todas") }
    var order by remember { mutableStateOf("Prioridad") }
    val visible = state.activities.filter { a ->
        (a.title.contains(search, true) || a.description.contains(search, true)) && when(filter) {
            "Pendientes" -> a.status !in listOf("Completada", "Cancelada") && (state.schedule.none { it.activityId == a.id } || state.pending.any { it.activityId == a.id })
            "Completadas" -> a.status == "Completada"
            "Vencidas" -> a.deadline != null && a.deadline < today(state.preferences.timezone).toString() && a.status !in listOf("Completada", "Cancelada")
            else -> true
        }
    }.let { list -> if(order == "Título") list.sortedBy { it.title } else list.sortedWith(compareBy<Activity> { it.priority }.thenBy { it.deadline ?: "9999" }) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Actividades", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { Field("Buscar actividades", search) { search = it } }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Todas", "Pendientes", "Completadas", "Vencidas").forEach { FilterChip(filter == it, { filter = it }, label = { Text(it) }) } } }
        item { Choice("Ordenar", order, listOf("Prioridad", "Título")) { order = it } }
        if(visible.isEmpty()) item { Empty("No hay actividades en esta vista. Creá una con + o cambiá el filtro.") }
        items(visible, key = { it.id }) { a -> OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(a.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${a.category} · ${a.duration} min · ${a.frequency}")
            Text("${listOf("Muy alta", "Alta", "Media", "Baja")[a.priority - 1]} · ${a.status}", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { edit(a) }) { Icon(Icons.Outlined.Edit, "Editar ${a.title}") }
                IconButton(onClick = { complete(a) }, enabled = a.status != "Completada") { Icon(Icons.Outlined.CheckCircle, "Completar ${a.title}") }
                IconButton(onClick = { duplicate(a) }) { Icon(Icons.Outlined.ContentCopy, "Duplicar ${a.title}") }
                IconButton(onClick = { delete(a) }) { Icon(Icons.Outlined.Delete, "Eliminar ${a.title}") }
            }
        } } }
    }
}
@Composable fun StatsScreen(state: State) {
    val slots = state.schedule
    val completed = slots.count { it.completed }
    Page("Tu progreso") {
        Text("Calculado a partir de tus registros guardados")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Metric("$completed", "Ocurrencias completadas", Modifier.weight(1f)); Metric("${if(slots.isEmpty()) 0 else completed * 100 / slots.size}%", "Cumplimiento", Modifier.weight(1f)) }
        Text("Tiempo por categoría", style = MaterialTheme.typography.titleLarge)
        if(slots.isEmpty()) Empty("Las estadísticas aparecerán cuando apliques una planificación.")
        val groups = slots.groupBy { s -> state.activities.find { it.id == s.activityId }?.category ?: "Sin categoría" }
        groups.forEach { (category, values) ->
            Text(category, fontWeight = FontWeight.Bold)
            val planned = values.sumOf { it.end - it.start }
            val done = values.filter { it.completed }.sumOf { it.end - it.start }
            LinearProgressIndicator(progress = { if(planned == 0) 0f else done.toFloat() / planned }, modifier = Modifier.fillMaxWidth())
            Text("$done de $planned min completados")
        }
        Text("Esta semana", style = MaterialTheme.typography.titleLarge)
        repeat(7) { n -> val date = today(state.preferences.timezone).minusDays(6L - n).toString(); val day = slots.filter { it.date == date }; Text("$date · ${day.count { it.completed }}/${day.size} · ${day.sumOf { it.end - it.start }} min") }
        Text("Distribución mensual", style = MaterialTheme.typography.titleLarge)
        repeat(6) { index ->
            val month = java.time.YearMonth.from(today(state.preferences.timezone)).minusMonths(5L - index)
            val records = slots.filter { it.date.startsWith(month.toString()) }
            Text("$month · ${records.count { it.completed }} completadas · ${records.sumOf { it.end - it.start }} min planificados")
        }
        Text("Actividades: ${state.activities.size} · Canceladas: ${state.activities.count { it.status == "Cancelada" }}")
        Text("El tiempo realizado corresponde a la duración planificada de las ocurrencias marcadas como completadas.", style = MaterialTheme.typography.bodySmall)
    }
}
@Composable fun Field(label: String, value: String, change: (String) -> Unit) { OutlinedTextField(value, change, label = { Text(label) }, modifier = Modifier.fillMaxWidth()) }
@Composable fun Choice(label: String, value: String, options: List<String>, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick = { expanded = true }) { Text("$label: $value"); Icon(Icons.Outlined.ExpandMore, null) }; DropdownMenu(expanded, { expanded = false }) { options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { change(option); expanded = false }) } } }
}
@Composable fun Confirm(title: String, text: String, dismiss: () -> Unit, accept: () -> Unit) { AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(text) }, confirmButton = { TextButton(onClick = accept) { Text("Confirmar") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancelar") } }) }
@Composable fun FullDialog(title: String, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) { Surface(Modifier.fillMaxSize()) { Column(Modifier.systemBarsPadding().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "Cerrar") } }; content(); Spacer(Modifier.height(24.dp)) } } }
}
