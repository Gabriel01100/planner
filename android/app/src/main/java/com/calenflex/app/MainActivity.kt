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
                composable("Hoy") { TodayScreen(state, learning, { a, date -> vm.task { vm.learningRepository.begin(a.id, date) } }, { finishingActive = true; progressEditor = true; selectedGoal = state.activities.find { it.id == learning.active?.activityId }?.goalId?.takeIf { id -> learning.goals().any { it.id == id } }; proposalDraft = null }, { vm.generate() }, { detail = it }, { vm.task { vm.learningRepository.clearActive() } }) }
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
            vm.saveGoal(goal) { goalEditor = false }
        }
        deletingGoal?.let { goal -> Confirm("Eliminar objetivo", "Se eliminarán este objetivo y sus registros educativos del servidor. La agenda histórica se conserva.", { deletingGoal = null }) { vm.task { vm.learningRepository.deleteGoal(goal.id) }; deletingGoal = null } }
        if(progressEditor) {
            val active = if(finishingActive) learning.active else null
            val activity = active?.let { a -> state.activities.find { it.id == a.activityId } }
            val elapsed = active?.let { runCatching { java.time.Duration.between(java.time.Instant.parse(it.startedAt), java.time.Instant.now()).toMinutes().coerceIn(1, 1440).toInt() }.getOrNull() }
            ProgressEditor(learning, selectedGoal, activity?.title.orEmpty(), elapsed, activity?.id, proposalDraft, startedAt = active?.startedAt, occurrenceDate = active?.occurrenceDate, plannedMinutes = activity?.duration, timezone = state.preferences.timezone, completeWithoutStudy = if(activity == null) null else ({ vm.finishWithoutStudy(activity) { progressEditor = false } }), dismiss = { progressEditor = false }) { body ->
                vm.recordProgress(body, activity) { progressEditor = false }
            }
        }
        if(incoming != null || pasteImport) ImportReview(incoming, vm, { clearIncoming(); pasteImport = false }, { proposalChoice = it; clearIncoming(); pasteImport = false })
        resourceDraft?.let { draft -> ResourceEditor(learning, draft, { resourceDraft = null }) { body -> vm.task(onSuccess = { resourceDraft = null }) { vm.learningRepository.registerResource(body); vm.message.value = "Recurso guardado, pendiente de sincronización" } } }
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
        editing?.let { a -> ActivityEditor(a, { editing = null; occurrence = null; editingProposal = false }, advancedInitially = editingProposal, availableActivities = state.activities) { next -> val source = occurrence; if(source == null) vm.save(next) { editing = null; editingProposal = false } else { vm.exception(source.first, source.second, next) { editing = null; occurrence = null; editingProposal = false } } } }
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
