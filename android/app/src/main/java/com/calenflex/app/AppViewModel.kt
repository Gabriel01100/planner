package com.calenflex.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.time.Instant

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val repository = (application as CalenflexApplication).repository
    val state = repository.state.stateIn(viewModelScope, SharingStarted.Eagerly, State())
    val dirty = repository.stored.map { it?.dirty ?: false }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow("")
    val preview = MutableStateFlow<PlanResult?>(null)
    val assistant = MutableStateFlow<AIResponse?>(null)
    private var planBase = ""
    private val taskMutex = Mutex()
    fun task(block: suspend () -> Unit) {
        viewModelScope.launch {
          taskMutex.withLock {
            busy.value = true
            try { block() } catch (e: Exception) {
                message.value = when (e) {
                    is retrofit2.HttpException -> when(e.code()) {
                        409 -> "Conflicto de sincronización. Exportá una copia y elegí una versión en Perfil."
                        401 -> "Credencial del servidor inválida"
                        503 -> "Servicio no configurado; las actividades locales siguen disponibles"
                        else -> "El servidor respondió con error ${e.code()}"
                    }
                    is java.io.IOException -> "Sin conexión. Tus cambios quedaron guardados en el teléfono."
                    else -> e.message ?: "No se pudo completar la operación"
                }
            } finally { busy.value = false }
          }
        }
    }
    fun edit(text: String, change: (State) -> State) = task {
        repository.edit(text, change)
        withContext(Dispatchers.IO) { scheduleReminders(getApplication(), repository.state.first()) }
        message.value = "Guardado en el teléfono"
    }
    fun save(activity: Activity) = edit("Actividad guardada: ${activity.title}") { s ->
        val now = Instant.now().toString()
        val next = activity.copy(updatedAt = now, createdAt = activity.createdAt.ifBlank { now })
        val old = s.activities.find { it.id == next.id }
        val timingUnchanged = old != null && next.copy(title = old.title, description = old.description, category = old.category,
            status = old.status, createdAt = old.createdAt, updatedAt = old.updatedAt) == old
        s.copy(activities = s.activities.filterNot { it.id == next.id } + next, pending = s.pending.filterNot { it.activityId == next.id },
            schedule = s.schedule.filter { it.activityId != next.id || it.completed || (timingUnchanged && next.status != "Cancelada") }.map { if(it.activityId == next.id && next.status == "Completada") it.copy(completed = true) else it })
    }
    fun exception(originalId: String, day: String, replacement: Activity) = edit("Ocurrencia modificada: $day") { s ->
        require(s.schedule.none { it.activityId == originalId && it.date == day && it.completed }) { "Una ocurrencia completada conserva su horario histórico" }
        s.copy(activities = s.activities.map { if(it.id == originalId) it.copy(skippedDates = (it.skippedDates + day).distinct()) else it } + replacement.copy(createdAt = Instant.now().toString(), updatedAt = Instant.now().toString()),
            schedule = s.schedule.filterNot { it.activityId == originalId && it.date == day })
    }
    fun delete(activity: Activity) = edit("Actividad eliminada: ${activity.title}") { s ->
        s.copy(activities = s.activities.filterNot { it.id == activity.id }, schedule = s.schedule.filterNot { it.activityId == activity.id }, pending = s.pending.filterNot { it.activityId == activity.id })
    }
    fun complete(activity: Activity, date: String? = null) = edit("Actividad completada: ${activity.title}") { s ->
        s.copy(activities = s.activities.map { if (it.id == activity.id && (date == null || it.frequency == "Única")) it.copy(status = "Completada") else it },
            schedule = s.schedule.map { if (it.activityId == activity.id && (date == null || it.date == date)) it.copy(completed = true) else it })
    }
    fun generate(days: Int = 7) = task {
        planBase = repository.current().payload
        val snapshot = json.decodeFromString<State>(planBase)
        preview.value = repository.api().plan(PlanRequest(snapshot, today(snapshot.preferences.timezone).toString(), days))
    }
    fun acceptPlan() = task {
        val result = preview.value ?: return@task
        repository.applyPlan(planBase, result)
        preview.value = null
        withContext(Dispatchers.IO) { scheduleReminders(getApplication(), repository.state.first()) }
        message.value = "Planificación aplicada"
    }
    fun sync() = task { repository.sync(); withContext(Dispatchers.IO) { scheduleReminders(getApplication(), repository.state.first()) }; message.value = "Sincronización completada" }
    fun ask(text: String) = task { val response = repository.api().ai(AIRequest(text, state.value)); assistant.value = response.copy(proposedActivity = response.proposedActivity?.copy(id = java.util.UUID.randomUUID().toString(), createdAt = "", updatedAt = "")) }
    fun importState(text: String) = task {
        require(text.length <= 5_000_000) { "La copia supera 5 MB" }
        val imported = json.decodeFromString<State>(text)
        validateState(imported)
        repository.edit("Copia importada") { imported }
        withContext(Dispatchers.IO) { scheduleReminders(getApplication(), repository.state.first()) }
        message.value = "Copia validada e importada"
    }
}
