package com.calenflex.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.*

@Composable fun ConnectionSettings(vm: AppViewModel, snapshot: LearningSnapshot, serverUrl: String) {
    val prefs = snapshot.envelope.memory["preferences"] as? JsonObject
    var enabled by remember(prefs) { mutableStateOf(prefs?.text("automaticEnabled") == "true") }
    var shift by remember(prefs) { mutableStateOf(prefs?.number("maxShiftMinutes")?.toString() ?: "30") }
    var moves by remember(prefs) { mutableStateOf(prefs?.number("maxAutomaticMoves")?.toString() ?: "2") }
    var confirm by remember { mutableStateOf(false) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    var unlink by remember { mutableStateOf<String?>(null) }
    val accounts by vm.linkedAccounts.collectAsStateWithLifecycle()
    val code by vm.linkCode.collectAsStateWithLifecycle()
    Text("Autonomía con límites", style = MaterialTheme.typography.titleMedium)
    Row { Checkbox(enabled, { enabled = it }); Text("Permitir ajustes pequeños de horario") }
    Text("Solo sesiones flexibles del mismo día. No cambia compromisos fijos, prioridades, fechas límite ni disponibilidad. El máximo cuenta los movimientos de los últimos siete días.")
    Field("Desplazamiento máximo en minutos (5 a 60)", shift) { shift = it }
    Field("Máximo de movimientos por siete días (0 a 5)", moves) { moves = it }
    OutlinedButton(onClick = { confirm = true }) { Text("Guardar reglas de autonomía") }
    if(confirm) Confirm("Autorizar estas reglas", "${if(enabled) "Se habilitarán" else "Se deshabilitarán"} los cambios automáticos dentro de los límites indicados. Los cambios mayores siguen requiriendo tu aprobación.", { confirm = false }) {
        confirm = false; vm.updateAutonomy(enabled, shift.toIntOrNull() ?: 0, moves.toIntOrNull() ?: -1)
    }
    HorizontalDivider()
    Text("Vincular con ChatGPT", style = MaterialTheme.typography.titleMedium)
    Text("Se necesita OAuth configurado en el servidor. El código vence en cinco minutos y se usa en la página de vinculación autenticada, nunca en una conversación. No se fusionan espacios con datos.")
    OutlinedButton(onClick = { vm.startAccountLink() }) { Text("Crear código de vinculación") }
    TextButton(onClick = { vm.loadAccountLinks() }) { Text("Ver conexiones vinculadas") }
    accounts.forEach { id -> TextButton(onClick = { unlink = id }) { Text("Desvincular conexión ${id.take(8)}") } }
    unlink?.let { id -> Confirm("Desvincular conexión", "Esta identidad OAuth dejará de acceder al espacio del teléfono. Los datos se conservan.", { unlink = null }) { vm.unlinkAccount(id); unlink = null } }
    if(code.isNotBlank()) {
        SelectionContainer { Text(code) }
        if(serverUrl.startsWith("https://")) TextButton(onClick = { uriHandler.openUri(serverUrl.trimEnd('/') + "/connect") }) { Text("Abrir vinculación segura") }
    }
}
