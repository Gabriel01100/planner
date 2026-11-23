package com.calenflex.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Composable fun ActivityEditor(original: Activity, dismiss: () -> Unit, advancedInitially: Boolean = false, availableActivities: List<Activity> = emptyList(), save: (Activity) -> Unit) {
    var a by remember(original) { mutableStateOf(original) }
    var duration by remember { mutableStateOf(a.duration.toString()) }
    var interval by remember { mutableStateOf(a.interval.toString()) }
    var start by remember { mutableStateOf(clock(a.windows.firstOrNull()?.start ?: 480)) }
    var end by remember { mutableStateOf(clock(a.windows.firstOrNull()?.end ?: 1320)) }
    var preferred by remember { mutableStateOf(clock(a.preferredStart)) }
    var fixed by remember { mutableStateOf(a.fixedStart?.let { clock(it) }.orEmpty()) }
    var lead by remember { mutableStateOf(a.leadMinutes.toString()) }
    var skipped by remember { mutableStateOf(a.skippedDates.joinToString(",")) }
    var excluded by remember { mutableStateOf(a.excluded.firstOrNull()?.let { "${clock(it.start)}-${clock(it.end)}" }.orEmpty()) }
    var error by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(advancedInitially) }
    var suspendFrom by remember { mutableStateOf("") }
    var suspendUntil by remember { mutableStateOf("") }
    FullDialog(if(a.createdAt.isBlank()) "Nueva actividad" else "Editar actividad", dismiss) {
        Field("Título", a.title) { a = a.copy(title = it) }
        Field("Descripción", a.description) { a = a.copy(description = it) }
        Field("Duración en minutos", duration) { duration = it }
        Choice("Prioridad", listOf("Muy alta", "Alta", "Media", "Baja")[a.priority - 1], listOf("Muy alta", "Alta", "Media", "Baja")) { a = a.copy(priority = listOf("Muy alta", "Alta", "Media", "Baja").indexOf(it) + 1) }
        TextButton(onClick = { advanced = !advanced }) { Text(if(advanced) "Ocultar opciones avanzadas" else "Opciones avanzadas") }
        if(advanced) {
        Field("Categoría", a.category) { a = a.copy(category = it) }
        Field("Inicio (AAAA-MM-DD)", a.startDate) { a = a.copy(startDate = it) }
        Field("Fecha límite (opcional)", a.deadline.orEmpty()) { a = a.copy(deadline = it.ifBlank { null }) }
        Choice("Estado", a.status, listOf("Pendiente", "Programada", "En curso", "Completada", "Cancelada", "Vencida")) { a = a.copy(status = it) }
        Choice("Frecuencia", a.frequency, listOf("Única", "Diaria", "Semanal", "Mensual", "Personalizada")) { a = a.copy(frequency = it) }
        if(a.frequency != "Única") {
            Field("Intervalo (${if(a.frequency == "Mensual") "meses" else if(a.frequency == "Semanal") "semanas" else "días"})", interval) { interval = it }
            Field("Fin de recurrencia (opcional)", a.until.orEmpty()) { a = a.copy(until = it.ifBlank { null }) }
            Field("Suspender desde (opcional)", suspendFrom) { suspendFrom = it }
            Field("Suspender hasta (opcional)", suspendUntil) { suspendUntil = it }
        }
        if(availableActivities.any { it.id != a.id }) {
            Text("Completar antes de esta actividad")
            availableActivities.filter { it.id != a.id }.forEach { dependency ->
                FilterChip(selected = dependency.id in a.dependencies, onClick = { a = a.copy(dependencies = if(dependency.id in a.dependencies) a.dependencies - dependency.id else a.dependencies + dependency.id) }, label = { Text(dependency.title) })
            }
        }
        Text("Días permitidos")
        DayPicker(a.days) { a = a.copy(days = it) }
        Field("Horario permitido desde (HH:MM)", start) { start = it }
        Field("Horario permitido hasta (HH:MM)", end) { end = it }
        Field("Horario excluido (HH:MM-HH:MM, opcional)", excluded) { excluded = it }
        Field("Hora preferida (HH:MM)", preferred) { preferred = it }
        Field("Hora fija (opcional; vacía = flexible)", fixed) { fixed = it }
        Field("Anticipación mínima en minutos", lead) { lead = it }
        Field("Omitir fechas (AAAA-MM-DD, separadas por coma)", skipped) { skipped = it }
        Text("Para posponer una actividad única, cambiá su fecha de inicio y fecha límite. Para una excepción, omití la fecha y creá una actividad única.", style = MaterialTheme.typography.bodySmall)
        }
        if(error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            try {
                val suspended = if(suspendFrom.isNotBlank() || suspendUntil.isNotBlank()) {
                    val from = LocalDate.parse(suspendFrom); val to = LocalDate.parse(suspendUntil)
                    val count = java.time.temporal.ChronoUnit.DAYS.between(from, to)
                    require(count in 0..3659) { "El período de suspensión debe tener hasta 3660 días" }
                    (0..count).map { from.plusDays(it).toString() }
                } else emptyList()
                val next = a.copy(title = a.title.trim(), duration = duration.toInt(), interval = interval.toInt(),
                    windows = listOf(Window(a.days, parseTime(start), parseTime(end, true))),
                    excluded = if(excluded.isBlank()) emptyList() else excluded.split('-').let { require(it.size == 2); listOf(Window(a.days, parseTime(it[0]), parseTime(it[1], true))) },
                    preferredStart = parseTime(preferred), fixedStart = fixed.takeIf { it.isNotBlank() }?.let { parseTime(it) },
                    leadMinutes = lead.toInt(), skippedDates = (skipped.split(',').map { it.trim() }.filter { it.isNotBlank() } + suspended).distinct())
                validateState(State(activities = availableActivities.filterNot { it.id == next.id } + next))
                save(next)
            } catch(e: Exception) { error = e.message ?: "Revisá los datos y las fechas" }
        }, modifier = Modifier.fillMaxWidth()) { Text("Guardar actividad") }
    }
}
fun parseTime(text: String, allowMidnight: Boolean = false): Int {
    val parts = text.trim().split(':')
    require(parts.size == 2) { "Usá el formato HH:MM" }
    val h = parts[0].toInt(); val m = parts[1].toInt()
    require(m in 0..59 && (h in 0..23 || allowMidnight && h == 24 && m == 0)) { "Hora inválida" }
    return h * 60 + m
}
@Composable fun DayPicker(days: List<Int>, change: (List<Int>) -> Unit) {
    Column {
        listOf(listOf(0,1,2,3), listOf(4,5,6)).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { day -> FilterChip(selected = day in days, onClick = { change(if(day in days) days - day else (days + day).sorted()) }, label = { Text(listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")[day]) }) }
        } }
    }
}
