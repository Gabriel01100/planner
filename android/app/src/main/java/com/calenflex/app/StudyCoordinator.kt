package com.calenflex.app

import androidx.room.withTransaction
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId

/** Acquires the shared repository gate BEFORE opening a Room transaction.
 * Sync cannot hold a repository lock while waiting for a transaction that needs that lock.
 */
class StudyCoordinator(private val database: AppDatabase, private val agenda: Repository, private val learning: LearningRepository) {
    init { require(agenda.mutex === learning.mutex) { "Los repositorios deben compartir el bloqueo de almacenamiento" } }
    suspend fun register(body: JsonObject, activityId: String?) = agenda.mutex.withLock {
        database.withTransaction {
            learning.registerProgressLocked(body)
            if(activityId != null) agenda.editLocked("Sesión finalizada") { snapshot ->
                val day = Instant.parse(body.text("completedAt")).atZone(ZoneId.of(snapshot.preferences.timezone)).toLocalDate().toString()
                snapshot.withCompletedActivity(activityId, day)
            }
        }
    }
    suspend fun finishWithoutStudy(activityId: String) = agenda.mutex.withLock {
        database.withTransaction {
            agenda.editLocked("Actividad finalizada") { it.withCompletedActivity(activityId, today(it.preferences.timezone).toString()) }
            learning.clearActiveLocked()
        }
    }
}
