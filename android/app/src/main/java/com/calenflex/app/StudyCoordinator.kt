package com.calenflex.app

import androidx.room.withTransaction
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/** Shared gate is acquired before Room; both documents commit or roll back together. */
class StudyCoordinator(private val database: AppDatabase, private val agenda: Repository, private val learning: LearningRepository) {
    init { require(agenda.mutex === learning.mutex) { "Los repositorios deben compartir el bloqueo de almacenamiento" } }
    suspend fun register(body: JsonObject, activityId: String?) = agenda.mutex.withLock {
        database.withTransaction {
            require(body.text("activityId").ifBlank { null } == activityId) { "La actividad no coincide con el registro" }
            // Resolve before modifying either document. Legacy recurring sessions remain recoverable.
            if(activityId != null) agenda.editLocked("Sesión finalizada") { snapshot ->
                snapshot.withCompletedStudyOccurrence(activityId, body.text("occurrenceDate").ifBlank { null })
            }
            learning.registerProgressLocked(body)
        }
    }
    suspend fun finishWithoutStudy(activityId: String) = agenda.mutex.withLock {
        database.withTransaction {
            val active = learning.current().active
            require(active?.activityId == activityId) { "Revisá la sesión en curso" }
            agenda.editLocked("Actividad finalizada") { it.withCompletedStudyOccurrence(activityId, active?.occurrenceDate) }
            learning.clearActiveLocked()
        }
    }
}

/** Agenda identity is (activityId, date); schedule validation forbids duplicates. */
fun State.withCompletedStudyOccurrence(activityId: String, occurrenceDate: String?): State {
    val activity = activities.singleOrNull { it.id == activityId } ?: error("Actividad no encontrada")
    require(activity.status != "Cancelada") { "Revisá la actividad cancelada" }
    val slots = schedule.filter { it.activityId == activityId }
    val legacyDate = if(activity.frequency == "Única") slots.singleOrNull()?.date ?: activity.startDate.takeIf { slots.isEmpty() } else null
    val date = occurrenceDate ?: legacyDate
    require(date != null) { "La sesión antigua no identifica la ocurrencia. Detenela sin completar y revisá la agenda." }
    java.time.LocalDate.parse(date)
    require(slots.count { it.date == date } == 1 || (activity.frequency == "Única" && slots.isEmpty() && date == activity.startDate)) {
        "La ocurrencia seleccionada ya no está en la agenda; revisala antes de continuar"
    }
    return withCompletedActivity(activityId, date)
}

internal fun validateStudySelection(active: ActiveStudy?, body: JsonObject) {
    if(active != null && body.text("activityId").isNotBlank()) {
        require(active.activityId == body.text("activityId") && active.occurrenceDate == body.text("occurrenceDate").ifBlank { null }) {
            "La sesión corresponde a otra ocurrencia. Conservamos la sesión en curso."
        }
    }
}
