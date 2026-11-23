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
