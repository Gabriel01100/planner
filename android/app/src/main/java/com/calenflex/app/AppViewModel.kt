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
import kotlinx.serialization.json.*
import java.time.Instant

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val repository = (application as CalenflexApplication).repository
    val learningRepository = (application as CalenflexApplication).learningRepository
    val learning = learningRepository.snapshots.stateIn(viewModelScope, SharingStarted.Eagerly, LearningSnapshot())
    val state = repository.state.stateIn(viewModelScope, SharingStarted.Eagerly, State())
    val dirty = repository.stored.map { it?.dirty ?: false }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow("")
    val preview = MutableStateFlow<PlanResult?>(null)
    val learningConversation = MutableStateFlow<List<kotlinx.serialization.json.JsonObject>>(emptyList())
    val latestLearningProposal = MutableStateFlow<kotlinx.serialization.json.JsonObject?>(null)
    val assistant = MutableStateFlow<AIResponse?>(null)
    private var planBase = ""
    private val taskMutex = Mutex()
    fun task(onSuccess: () -> Unit = {}, block: suspend () -> Unit) {
        viewModelScope.launch {
          taskMutex.withLock {
            busy.value = true
            try { block(); onSuccess() } catch (e: Exception) {
                if(e is kotlinx.coroutines.CancellationException) throw e
                message.value = when (e) {
                    is retrofit2.HttpException -> when(e.code()) {
                        409 -> "Conflicto de sincronización. Exportá una copia y elegí una versión en Perfil."
                        401 -> "Credencial del servidor inválida"
                        403 -> "Esta conexión no tiene permiso para la acción. Revisá sus permisos."
                        413 -> "El contenido es demasiado grande. Compartí un archivo o texto más breve."
                        422 -> "Revisá los datos, fechas y duración antes de guardar."
                        429 -> "Alcanzaste el límite de consultas. Probá más tarde."
                        503 -> "Esta función no está configurada en el servidor. Podés seguir trabajando localmente."
                        else -> "El servicio no pudo completar la operación. Los datos locales se conservan."
                    }
                    is java.io.IOException -> "Sin conexión. Los datos y registros ya guardados siguen en el teléfono."
                    else -> e.message ?: "No se pudo completar la operación"
                }
            } finally { busy.value = false }
          }
        }
    }
    fun edit(text: String, onSuccess: () -> Unit = {}, change: (State) -> State) = task(onSuccess) {
        repository.edit(text, change)
        withContext(Dispatchers.IO) { scheduleReminders(getApplication(), repository.state.first()) }
        message.value = "Guardado en el teléfono"
    }
    fun save(activity: Activity, onSuccess: () -> Unit = {}) = edit("Actividad guardada: ${activity.title}", onSuccess) { s ->
        val now = Instant.now().toString()
        val next = activity.copy(updatedAt = now, createdAt = activity.createdAt.ifBlank { now })
        val old = s.activities.find { it.id == next.id }
        val timingUnchanged = old != null && next.copy(title = old.title, description = old.description, category = old.category,
            status = old.status, createdAt = old.createdAt, updatedAt = old.updatedAt) == old
        s.copy(activities = s.activities.filterNot { it.id == next.id } + next, pending = s.pending.filterNot { it.activityId == next.id },
            schedule = s.schedule.filter { it.activityId != next.id || it.completed || (timingUnchanged && next.status != "Cancelada") }.map { if(it.activityId == next.id && next.status == "Completada") it.copy(completed = true) else it })
    }
    fun exception(originalId: String, day: String, replacement: Activity, onSuccess: () -> Unit = {}) = edit("Ocurrencia modificada: $day", onSuccess) { s ->
        require(s.schedule.none { it.activityId == originalId && it.date == day && it.completed }) { "Una ocurrencia completada conserva su horario histórico" }
        s.copy(activities = s.activities.map { if(it.id == originalId) it.copy(skippedDates = (it.skippedDates + day).distinct()) else it } + replacement.copy(createdAt = Instant.now().toString(), updatedAt = Instant.now().toString()),
            schedule = s.schedule.filterNot { it.activityId == originalId && it.date == day })
    }
    fun delete(activity: Activity) = edit("Actividad eliminada: ${activity.title}") { s ->
        require(s.activities.none { activity.id in it.dependencies }) { "Quitá primero esta actividad de las dependencias de otras actividades" }
        s.copy(activities = s.activities.filterNot { it.id == activity.id }, schedule = s.schedule.filterNot { it.activityId == activity.id }, pending = s.pending.filterNot { it.activityId == activity.id })
    }
    fun complete(activity: Activity, date: String? = null) = edit("Actividad completada: ${activity.title}") { s ->
        s.withCompletedActivity(activity.id, date)
    }
    fun finishWithoutStudy(activity: Activity, onSuccess: () -> Unit) = task(onSuccess) {
        (getApplication<Application>() as CalenflexApplication).studyCoordinator.finishWithoutStudy(activity.id)
        withContext(Dispatchers.IO) { scheduleReminders(getApplication(), repository.state.first()) }
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
    fun saveGoal(goal: LearningGoalDto, onSuccess: () -> Unit) = task(onSuccess) {
        if(learningRepository.current().goals().any { it.id == goal.id }) learningRepository.correctGoal(goal) else learningRepository.createGoal(goal)
        message.value = "Objetivo guardado"
    }
    fun recordProgress(body: JsonObject, activity: Activity?, onSuccess: () -> Unit) = task(onSuccess) {
        (getApplication<Application>() as CalenflexApplication).studyCoordinator.register(body, activity?.id)
        withContext(Dispatchers.IO) { scheduleReminders(getApplication(), repository.state.first()) }
        message.value = "Sesión guardada; podés sincronizarla cuando tengas conexión"
    }
    fun analyzeLearning(text: String, goal: String?, includeProgress: Boolean, onSuccess: () -> Unit) = task(onSuccess) {
        val response = repository.api().analyzeImport(buildJsonObject { put("content", text); put("sourceType", "manual"); put("history", JsonArray(learningConversation.value)); put("includeProgress", includeProgress); goal?.let { put("goalId", it) } })
        latestLearningProposal.value = response
        val summary = response["proposal"]?.jsonObject?.text("summary").orEmpty()
        learningConversation.value = (learningConversation.value + buildJsonObject { put("role","user");put("text",text.take(4000)) } + buildJsonObject { put("role","assistant");put("text",summary.take(4000)) }).takeLast(8)
    }
    fun updateAutonomy(enabled: Boolean, shift: Int, moves: Int) = task {
        require(shift in 5..60 && moves in 0..5)
        learningRepository.sync()
        val current = learningRepository.current().envelope
        val previous = current.memory["preferences"]?.jsonObject ?: buildJsonObject {}
        val preferences = JsonObject(previous.toMutableMap().apply { put("automaticEnabled",JsonPrimitive(enabled)); put("autonomy",JsonPrimitive(if(enabled) "bounded" else "suggestions"));put("maxShiftMinutes",JsonPrimitive(shift));put("maxAutomaticMoves",JsonPrimitive(moves)) })
        repository.api().learningPreferences(buildJsonObject { put("revision", current.revision);put("preferences",preferences);put("confirmed",true) })
        learningRepository.sync(); message.value = "Reglas de autonomía actualizadas"
    }
    val linkedAccounts = MutableStateFlow<List<String>>(emptyList())
    fun loadAccountLinks() = task { linkedAccounts.value = repository.api().accountLinks()["items"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList() }
    fun unlinkAccount(id: String) = task { repository.api().revokeAccountLink(id, buildJsonObject { put("confirmed",true) }); linkedAccounts.value = linkedAccounts.value - id; message.value = "Vinculación revocada; tus datos se conservaron" }
    val linkCode = MutableStateFlow("")
    fun configureConnection(url: String, token: String) = task { repository.configureConnection(url, token); message.value = "Conexión e identidad verificadas" }
    fun startAccountLink() = task { linkCode.value = repository.api().startLink().text("code") }
    fun sync() = task { repository.sync(); learningRepository.sync(); withContext(Dispatchers.IO) { scheduleReminders(getApplication(), repository.state.first()) }; message.value = "Sincronización completada" }
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
