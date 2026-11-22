package com.calenflex.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
fun today(timezone: String = "America/Argentina/Buenos_Aires"): LocalDate = LocalDate.now(ZoneId.of(timezone))
fun clock(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

@Serializable data class Window(val days: List<Int> = (0..6).toList(), val start: Int = 480, val end: Int = 1320)
@Serializable data class Activity(
    val id: String = UUID.randomUUID().toString(), val title: String = "", val description: String = "",
    val category: String = "Personal", val startDate: String = today().toString(), val deadline: String? = null,
    val duration: Int = 60, val priority: Int = 2, val status: String = "Pendiente", val frequency: String = "Única",
    val interval: Int = 1, val days: List<Int> = (0..6).toList(), val until: String? = null,
    val skippedDates: List<String> = emptyList(), val windows: List<Window> = listOf(Window()),
    val excluded: List<Window> = emptyList(), val preferredStart: Int = 540, val fixedStart: Int? = null,
    val leadMinutes: Int = 0, val createdAt: String = "", val updatedAt: String = ""
)
@Serializable data class Slot(val activityId: String, val date: String, val start: Int, val end: Int, val completed: Boolean = false)
@Serializable data class Preferences(
    val name: String = "", val timezone: String = "America/Argentina/Buenos_Aires", val theme: String = "Sistema",
    val transition: Int = 0, val reminderMinutes: Int = 15, val notifications: Boolean = true,
    val notificationKinds: List<String> = listOf("before", "start")
)
@Serializable data class Event(val at: String, val text: String)
@Serializable data class State(
    val schemaVersion: Int = 1, val activities: List<Activity> = emptyList(), val availability: List<Window> = listOf(Window()),
    val blocked: List<Slot> = emptyList(), val schedule: List<Slot> = emptyList(), val preferences: Preferences = Preferences(),
    val history: List<Event> = emptyList(), val pending: List<Pending> = emptyList()
)
@Serializable data class SyncEnvelope(val revision: Int, val state: State)
@Serializable data class PlanRequest(val state: State, val start: String, val days: Int = 7)
@Serializable data class Pending(val activityId: String, val date: String, val reason: String)
@Serializable data class PlanResult(val schedule: List<Slot>, val pending: List<Pending>, val conflicts: List<String>, val changes: List<String>)
@Serializable data class AIRequest(val message: String, val state: State)
@Serializable data class AIResponse(val answer: String, val proposedActivity: Activity? = null)

fun validateState(state: State) {
    require(state.schemaVersion == 1) { "Versión de copia incompatible" }
    ZoneId.of(state.preferences.timezone)
    require(state.activities.size <= 2000 && state.schedule.size <= 10000 && state.history.size <= 10000 && state.pending.size <= 10000)
    require(state.preferences.notificationKinds.all { it in listOf("before", "start", "overdue", "daily", "weekly", "pending") })
    require(state.activities.map { it.id }.distinct().size == state.activities.size) { "Actividades duplicadas" }
    require(state.schedule.map { it.activityId to it.date }.distinct().size == state.schedule.size) { "Ocurrencias duplicadas" }
    require(state.preferences.transition in 0..180 && state.preferences.reminderMinutes in 0..1440)
    val windows = state.availability + state.activities.flatMap { it.windows + it.excluded }
    windows.forEach { require(it.start in 0..1439 && it.end in 1..1440 && it.start < it.end && it.days.all { d -> d in 0..6 }) { "Horario inválido" } }
    state.activities.forEach {
        UUID.fromString(it.id)
        require(it.title.isNotBlank() && it.title.length <= 200 && it.description.length <= 4000 && it.category.length <= 80)
        require(it.duration in 5..1440 && it.priority in 1..4 && it.interval in 1..365 && it.leadMinutes in 0..525600)
        require(it.status in listOf("Pendiente", "Programada", "En curso", "Completada", "Cancelada", "Vencida"))
        require(it.frequency in listOf("Única", "Diaria", "Semanal", "Mensual", "Personalizada"))
        require(it.days.all { day -> day in 0..6 } && it.preferredStart in 0..1439 && (it.fixedStart == null || it.fixedStart in 0..1439))
        val start = LocalDate.parse(it.startDate)
        require(it.deadline == null || LocalDate.parse(it.deadline) >= start) { "Fecha límite anterior al inicio" }
        require(it.until == null || LocalDate.parse(it.until) >= start)
        it.skippedDates.forEach { d -> LocalDate.parse(d) }
    }
    (state.schedule + state.blocked).forEach {
        LocalDate.parse(it.date)
        require(it.start in 0..1439 && it.end in 1..1440 && it.start < it.end)
    }
    require(state.schedule.all { s -> state.activities.any { it.id == s.activityId } })
}

fun freeWindows(state: State, day: LocalDate): List<Pair<Int, Int>> {
    val available = state.availability.filter { day.dayOfWeek.value - 1 in it.days }.map { it.start to it.end }.sortedBy { it.first }
    val merged = mutableListOf<Pair<Int, Int>>()
    available.forEach { interval ->
        if(merged.isNotEmpty() && merged.last().second >= interval.first) {
            val old = merged.removeAt(merged.lastIndex); merged.add(old.first to maxOf(old.second, interval.second))
        } else merged.add(interval)
    }
    var free = merged.toList()
    (state.schedule + state.blocked).filter { it.date == day.toString() }.forEach { occupied ->
        free = free.flatMap { (start, end) ->
            if(occupied.end <= start || occupied.start >= end) listOf(start to end)
            else listOf(start to minOf(end, occupied.start), maxOf(start, occupied.end) to end).filter { it.first < it.second }
        }
    }
    return free
}
