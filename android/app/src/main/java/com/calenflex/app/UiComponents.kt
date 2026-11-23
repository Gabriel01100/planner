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
@Composable fun SlotCard(a: Activity, slot: Slot, open: () -> Unit, complete: () -> Unit) {
    OutlinedCard(onClick = open, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("${clock(slot.start)} — ${clock(slot.end)}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge); Text(a.title, fontWeight = FontWeight.Bold); Text(a.category, style = MaterialTheme.typography.bodySmall) }
        IconButton(onClick = complete, enabled = !slot.completed) { Icon(if (slot.completed) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, if (slot.completed) "Completada" else "Completar ${a.title}") }
    } }
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
