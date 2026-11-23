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

@Composable fun ActivityEditor(original: Activity, dismiss: () -> Unit, advancedInitially: Boolean = false, save: (Activity) -> Unit) {
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
                validateState(State(activities = listOf(next)))
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
@Composable fun ProfileScreen(state: State, vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dirty by vm.dirty.collectAsStateWithLifecycle()
    val learning by vm.learning.collectAsStateWithLifecycle()
    var name by remember(state.preferences.name) { mutableStateOf(state.preferences.name) }
    var zone by remember(state.preferences.timezone) { mutableStateOf(state.preferences.timezone) }
    var theme by remember(state.preferences.theme) { mutableStateOf(state.preferences.theme) }
    var reminders by remember(state.preferences.reminderMinutes) { mutableStateOf(state.preferences.reminderMinutes.toString()) }
    var enabled by remember(state.preferences.notifications) { mutableStateOf(state.preferences.notifications) }
    var kinds by remember(state.preferences.notificationKinds) { mutableStateOf(state.preferences.notificationKinds) }
    var transition by remember(state.preferences.transition) { mutableStateOf(state.preferences.transition.toString()) }
    var availability by remember(state.availability) { mutableStateOf(state.availability) }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf<String?>(null) }
    var availabilityError by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { runCatching { vm.repository.connection.get() }.onSuccess { url = it.url; token = it.token } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> vm.message.value = if(granted) "Notificaciones habilitadas" else "Podés habilitar las notificaciones desde Ajustes de Android" }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri != null) vm.task {
        val payload = vm.repository.current().payload
        withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray()) } ?: error("No se pudo abrir el archivo") }
        vm.message.value = "Copia exportada. Guardala en un lugar privado."
    } }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri != null) vm.task {
        importText = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { stream ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var n = stream.read(buffer); while(n != -1) { output.write(buffer, 0, n); require(output.size() <= 5_000_000) { "La copia supera 5 MB" }; n = stream.read(buffer) }; output.toString("UTF-8")
        } ?: error("No se pudo leer la copia") }
    } }
    Page("Perfil y configuración") {
        Text("Todo lo que guardás queda disponible en este teléfono, incluso sin conexión.")
        Field("Tu nombre", name) { name = it }
        Field("Zona horaria IANA", zone) { zone = it }
        Choice("Tema", theme, listOf("Sistema", "Claro", "Oscuro")) { theme = it }
        Field("Transición entre actividades (min)", transition) { transition = it }
        Field("Anticipación de recordatorios (min)", reminders) { reminders = it }
        Row { Checkbox(enabled, { enabled = it; if(it && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) }); Text("Recordatorios de actividades", Modifier.padding(top = 12.dp)) }
        listOf("before" to "Antes de comenzar", "start" to "Al comenzar", "overdue" to "Actividades atrasadas", "daily" to "Resumen diario", "weekly" to "Resumen semanal", "pending" to "Pendientes").forEach { (key, label) -> Row { Checkbox(key in kinds, { checked -> kinds = if(checked) kinds + key else kinds - key }); Text(label, Modifier.padding(top = 12.dp)) } }
        Text("Android puede demorar las notificaciones para ahorrar batería; no son alarmas exactas.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { vm.task {
            ZoneId.of(zone)
            val p = state.preferences.copy(name = name, timezone = zone, theme = theme, transition = transition.toInt(), reminderMinutes = reminders.toInt(), notifications = enabled, notificationKinds = kinds)
            validateState(state.copy(preferences = p))
            vm.repository.edit("Preferencias actualizadas") { it.copy(preferences = p) }
            withContext(Dispatchers.IO) { scheduleReminders(context, vm.repository.state.first()) }
        } }) { Text("Guardar preferencias") }
        HorizontalDivider()
        Text("Disponibilidad semanal", style = MaterialTheme.typography.titleLarge)
        Text("Las franjas son restricciones obligatorias. Para reservar descanso o sueño, dejá esas horas fuera de la disponibilidad.")
        availability.forEachIndexed { i, window ->
            OutlinedCard { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DayPicker(window.days) { availability = availability.toMutableList().also { list -> list[i] = window.copy(days = it) } }
                var start by remember(window.start) { mutableStateOf(clock(window.start)) }
                var end by remember(window.end) { mutableStateOf(clock(window.end)) }
                Field("Desde", start) { start = it }
                Field("Hasta", end) { end = it }
                Row { TextButton(onClick = { try {
                    val next = Window(window.days, parseTime(start), parseTime(end, true)); require(next.end > next.start)
                    availability = availability.toMutableList().also { it[i] = next }; availabilityError = ""
                } catch(e: Exception) { availabilityError = "Revisá los horarios" } }) { Text("Actualizar franja") }
                TextButton(onClick = { availability = availability.filterIndexed { index, _ -> index != i } }) { Text("Quitar") } }
            } }
        }
        if(availabilityError.isNotBlank()) Text(availabilityError, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = { availability = availability + Window() }) { Text("Agregar franja") }
        Button(onClick = { vm.edit("Disponibilidad actualizada") { it.copy(availability = availability) } }) { Text("Guardar disponibilidad") }
        HorizontalDivider()
        Text("Horarios ocupados y bloqueos", style = MaterialTheme.typography.titleLarge)
        var blockDate by remember { mutableStateOf(today(state.preferences.timezone).toString()) }
        var blockStart by remember { mutableStateOf("12:00") }
        var blockEnd by remember { mutableStateOf("13:00") }
        state.blocked.forEach { b -> Row {
            Text("${b.date} ${clock(b.start)}–${clock(b.end)}", Modifier.weight(1f))
            TextButton(onClick = { vm.edit("Bloqueo eliminado") { it.copy(blocked = it.blocked - b) } }) { Text("Quitar") }
        } }
        Field("Fecha del bloqueo (AAAA-MM-DD)", blockDate) { blockDate = it }
        Field("Inicio del bloqueo (HH:MM)", blockStart) { blockStart = it }
        Field("Fin del bloqueo (HH:MM)", blockEnd) { blockEnd = it }
        OutlinedButton(onClick = { vm.task {
            LocalDate.parse(blockDate)
            val b = Slot(UUID.randomUUID().toString(), blockDate, parseTime(blockStart), parseTime(blockEnd, true))
            vm.repository.edit("Bloqueo agregado") { it.copy(blocked = it.blocked + b) }
        } }) { Text("Agregar bloqueo") }
        HorizontalDivider()
        Text("Servidor y sincronización", style = MaterialTheme.typography.titleLarge)
        Text(if(dirty) "Hay cambios locales pendientes de sincronizar" else "Sin cambios locales pendientes")
        Field("URL del servidor HTTPS", url) { url = it }
        OutlinedTextField(token, { token = it }, label = { Text("Credencial privada del servidor") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.task {
            val old = vm.repository.connection.get()
            require(old.url.isBlank() || old.url == url.trim().trimEnd('/')) { "Para cambiar de servidor, exportá tu copia y borrá los datos de la app primero" }
            vm.repository.connection.save(Connection(url, token)); vm.message.value = "Conexión guardada"
        } }) { Text("Guardar conexión") }
        OutlinedButton(onClick = { vm.sync() }) { Text("Sincronizar ahora") }
        Text("Ante un conflicto, exportá primero una copia. Podés elegir cuál de las dos versiones conservar.")
        TextButton(onClick = { confirm = "remote" }) { Text("Usar versión del servidor") }
        TextButton(onClick = { confirm = "local" }) { Text("Conservar esta versión local") }
        Text("La IA se configura en el servidor con AI_API_KEY; ninguna clave del proveedor se guarda en el APK.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        LearningBackup(vm, learning)
        HorizontalDivider()
        Text("Copias e historial de agenda", style = MaterialTheme.typography.titleLarge)
        OutlinedButton(onClick = { export.launch("calenflex-${today()}.json") }) { Text("Exportar copia JSON") }
        OutlinedButton(onClick = { import.launch(arrayOf("application/json", "text/plain")) }) { Text("Importar copia JSON") }
        state.history.takeLast(30).reversed().forEach { Text("${it.at.take(19)} · ${it.text}", style = MaterialTheme.typography.bodySmall) }
        Text("CalenFleX 1.1 · Español (Argentina)")
    }
    if(confirm.isNotBlank()) Confirm("Resolver sincronización", if(confirm == "remote") "Se reemplazarán los datos de este teléfono por los del servidor. Exportá una copia antes de continuar." else "Se reemplazará la versión del servidor por la de este teléfono. Exportá una copia antes de continuar.", { confirm = "" }) {
        val action = confirm; confirm = ""
        vm.task { if(action == "remote") vm.repository.useRemote() else vm.repository.keepLocal(); withContext(Dispatchers.IO) { scheduleReminders(context, vm.repository.state.first()) }; vm.message.value = "Versión aplicada" }
    }
    importText?.let { payload -> Confirm("Importar copia", "Se validará el archivo y se reemplazarán los datos locales. La importación quedará pendiente de sincronizar.", { importText = null }) { vm.importState(payload); importText = null } }
}
