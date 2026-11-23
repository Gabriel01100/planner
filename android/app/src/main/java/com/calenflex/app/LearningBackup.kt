package com.calenflex.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

@Composable fun LearningBackup(vm: AppViewModel, snapshot: LearningSnapshot) {
    val context = LocalContext.current
    var imported by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri != null) vm.task {
        val payload = json.encodeToString(vm.learningRepository.current())
        withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray()) } ?: error("No se pudo abrir el archivo") }
        vm.message.value = "Copia de aprendizaje exportada con los registros pendientes"
    } }
    val read = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri != null) vm.task {
        imported = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { stream ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var n = stream.read(buffer)
            while(n != -1) { output.write(buffer, 0, n); require(output.size() <= 5_000_000) { "La copia supera 5 MB" }; n = stream.read(buffer) }
            output.toString("UTF-8")
        } ?: error("No se pudo leer la copia") }
    } }
    Text("Copias de aprendizaje", style = MaterialTheme.typography.titleMedium)
    Text("Incluyen objetivos, sesiones y registros pendientes. La agenda se exporta por separado. Guardá ambas copias en un lugar privado.")
    OutlinedButton(onClick = { export.launch("calenflex-aprendizaje-${today()}.json") }) { Text("Exportar aprendizaje") }
    OutlinedButton(onClick = { read.launch(arrayOf("application/json", "text/plain")) }) { Text("Importar aprendizaje") }
    if(snapshot.restorePending) {
        Text("La copia se puede consultar localmente. Para sincronizar, elegí cómo continuar.")
        TextButton(onClick = { confirmation = "resume" }) { Text("Continuar con mi servidor actual") }
        TextButton(onClick = { confirmation = "restore" }) { Text("Restaurar si el servidor está vacío") }
    }
    imported?.let { text -> Confirm("Importar aprendizaje", "Se reemplazará la memoria local; no se sobrescribe el servidor automáticamente. Exportá primero una copia de los cambios locales.", { imported = null }) { vm.task { vm.learningRepository.importBackup(text); vm.message.value = "Copia importada; revisá cómo sincronizarla" }; imported = null } }
    if(confirmation.isNotBlank()) Confirm("Confirmar recuperación", if(confirmation == "resume") "Se comprobará que el servidor conserve los registros de la copia antes de enviar los pendientes." else "Solo se restaurará si el servidor está vacío. No se reemplazarán registros existentes.", { confirmation = "" }) {
        val action = confirmation; confirmation = ""
        vm.task { if(action == "resume") vm.learningRepository.resumeImportedBackup() else vm.learningRepository.restoreEmptyServer(); vm.learningRepository.sync(); vm.message.value = "Aprendizaje recuperado y sincronizado" }
    }
}
