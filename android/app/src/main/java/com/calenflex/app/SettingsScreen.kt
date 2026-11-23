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
    val exportConflict = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri != null) vm.task {
        val payload = vm.repository.current().conflict ?: error("No hay una copia de conflicto guardada")
        withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray()) } ?: error("No se pudo abrir el archivo") }
        vm.message.value = "Se exportaron las dos versiones y la base de sincronización"
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
        Button(onClick = { vm.configureConnection(url, token) }) { Text("Guardar conexión") }
        OutlinedButton(onClick = { vm.sync() }) { Text("Sincronizar ahora") }
        Text("Los cambios independientes se fusionan. Ante un conflicto incompatible, exportá ambas versiones antes de elegir cuál conservar.")
        TextButton(onClick = { exportConflict.launch("calenflex-conflicto-${today()}.json") }) { Text("Exportar ambas versiones del conflicto") }
        TextButton(onClick = { confirm = "remote" }) { Text("Usar versión del servidor") }
        TextButton(onClick = { confirm = "local" }) { Text("Conservar esta versión local") }
        Text("La IA se configura en el servidor con AI_API_KEY; ninguna clave del proveedor se guarda en el APK.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        ConnectionSettings(vm, learning, url)
        LearningBackup(vm, learning)
        HorizontalDivider()
        Text("Copias e historial de agenda", style = MaterialTheme.typography.titleLarge)
        OutlinedButton(onClick = { export.launch("calenflex-${today()}.json") }) { Text("Exportar copia JSON") }
        OutlinedButton(onClick = { import.launch(arrayOf("application/json", "text/plain")) }) { Text("Importar copia JSON") }
        state.history.takeLast(30).reversed().forEach { Text("${it.at.take(19)} · ${it.text}", style = MaterialTheme.typography.bodySmall) }
        Text("CalenFleX 1.2 · Español (Argentina)")
    }
    if(confirm.isNotBlank()) Confirm("Resolver sincronización", if(confirm == "remote") "Se reemplazarán los datos de este teléfono por los del servidor. Exportá una copia antes de continuar." else "Se reemplazará la versión del servidor por la de este teléfono. Exportá una copia antes de continuar.", { confirm = "" }) {
        val action = confirm; confirm = ""
        vm.task { if(action == "remote") vm.repository.useRemote() else vm.repository.keepLocal(); withContext(Dispatchers.IO) { scheduleReminders(context, vm.repository.state.first()) }; vm.message.value = "Versión aplicada" }
    }
    importText?.let { payload -> Confirm("Importar copia", "Se validará el archivo y se reemplazarán los datos locales. La importación quedará pendiente de sincronizar.", { importText = null }) { vm.importState(payload); importText = null } }
}
