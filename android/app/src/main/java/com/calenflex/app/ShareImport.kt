package com.calenflex.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/** Explicit Android shares only. Never fetch links or read file:// paths. */
data class SharedFile(val file: File, val mime: String)
data class SharedContent(val text: String, val files: List<SharedFile> = emptyList())
fun readSharedIntent(context: Context, intent: Intent): SharedContent? {
    if(intent.action !in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return null
    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
    require(text.length <= 20000) { "El texto compartido supera 20000 caracteres" }
    @Suppress("DEPRECATION")
    val uris: List<Uri> = if(intent.action == Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
    require(uris.size <= 5) { "Compartí hasta cinco documentos por vez" }
    val files = mutableListOf<SharedFile>()
    try {
        uris.forEach { uri ->
            require(uri.scheme == "content") { "Compartí el documento mediante una aplicación compatible" }
            val mime = context.contentResolver.getType(uri) ?: intent.type.orEmpty()
            require(mime in listOf("text/plain", "text/markdown", "application/json", "application/pdf")) { "Se admiten textos UTF-8 y PDF" }
            val temporary = File.createTempFile("shared-", ".bin", context.cacheDir)
            files.add(SharedFile(temporary, mime))
            context.contentResolver.openInputStream(uri)?.use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(8192); var total = 0; var count = input.read(buffer)
                while(count >= 0) { total += count; require(total <= 2_000_000) { "Cada documento debe tener hasta 2 MB" }; output.write(buffer, 0, count); count = input.read(buffer) }
            } } ?: error("No se pudo leer el documento compartido")
        }
        require(text.isNotBlank() || files.isNotEmpty()) { "El contenido compartido está vacío" }
        return SharedContent(text, files)
    } catch(error: Exception) { files.forEach { it.file.delete() }; throw error }
}

@Composable fun ImportReview(incoming: SharedContent?, vm: AppViewModel, dismiss: () -> Unit, review: (JsonObject) -> Unit) {
    var text by remember(incoming) { mutableStateOf(incoming?.text.orEmpty()) }
    var proposals by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    FullDialog("Revisar contenido compartido", dismiss) {
        Text("Nada se guarda ni se envía a la IA hasta que lo solicites. No accedemos a conversaciones privadas de ChatGPT ni NotebookLM.")
        Field("Texto o enlace compartido", text) { text = it }
        incoming?.files?.forEachIndexed { index, file -> Text("Documento ${index + 1}: ${if(file.mime == "application/pdf") "PDF" else "texto"} · ${file.file.length() / 1024} KB") }
        Text("Al analizar se enviarán este contenido y títulos de objetivos al proveedor configurado. Un enlace se trata como referencia: su contenido no se descarga.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { vm.task {
            val api = vm.repository.api(); val result = mutableListOf<JsonObject>()
            fun updateProposals() {
                proposals = result.mapNotNull { response -> (response["proposal"] as? JsonObject)?.let { JsonObject(it.toMutableMap().apply { put("_sourceType", response["sourceType"] ?: JsonPrimitive("text")); response["contentDigest"]?.let { put("_sourceDigest", it) } }) } }
            }
            if(text.isNotBlank()) { result.add(api.analyzeImport(buildJsonObject { put("content", text); put("sourceType", if(text.trim().startsWith("https://")) "link" else "text") })); updateProposals() }
            incoming?.files?.forEachIndexed { index, file ->
                val extension = if(file.mime == "application/pdf") "pdf" else "txt"
                result.add(api.analyzeDocument(MultipartBody.Part.createFormData("file", "compartido-$index.$extension", file.file.asRequestBody(file.mime.toMediaType())))); updateProposals()
            }
            require(result.isNotEmpty()) { "Pegá texto o compartí un documento" }
            proposals = result.mapNotNull { response -> (response["proposal"] as? JsonObject)?.let { JsonObject(it.toMutableMap().apply { put("_sourceType", response["sourceType"] ?: JsonPrimitive("text")); response["contentDigest"]?.let { put("_sourceDigest", it) } }) } }
        } }) { Text("Analizar contenido") }
        proposals.forEach { proposal -> OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text(proposal.text("summary")); (proposal["questions"] as? JsonArray)?.forEach { Text(it.jsonPrimitive.content) }
            TextButton(onClick = { review(proposal) }) { Text("Revisar y elegir cómo guardarlo") }
        } } }
    }
}
