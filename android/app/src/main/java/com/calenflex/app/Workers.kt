package com.calenflex.app

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.work.*
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class CalenflexApplication : Application() {
    val database by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "calenflex.db").build() }
    val repository by lazy { Repository(database.stateDao(), ConnectionStore(this)) }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("activities", "Actividades", NotificationManager.IMPORTANCE_DEFAULT))
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
}
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val repo = (applicationContext as CalenflexApplication).repository
        if (repo.connection.get().url.isBlank()) Result.success() else { repo.sync(); scheduleReminders(applicationContext, repo.state.first()); Result.success() }
    } catch (e: retrofit2.HttpException) { if (e.code() == 409 || e.code() == 401) Result.failure() else Result.retry() }
      catch (e: Exception) { Result.retry() }
}
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val state = (applicationContext as CalenflexApplication).repository.state.first()
        val id = inputData.getString("activityId") ?: return Result.failure()
        val day = inputData.getString("date") ?: return Result.failure()
        val slot = state.schedule.find { it.activityId == id && it.date == day } ?: return Result.success()
        val activity = state.activities.find { it.id == id } ?: return Result.success()
        val kind = inputData.getString("kind") ?: "before"
        if(kind !in state.preferences.notificationKinds) return Result.success()
        if (slot.completed || activity.status in listOf("Completada", "Cancelada") || !state.preferences.notifications) return Result.success()
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val intent = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(applicationContext, "activities")
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar).setContentTitle((if(kind == "overdue") "Actividad atrasada: " else "") + activity.title)
            .setContentText("${slot.date} · ${clock(slot.start)}").setContentIntent(intent).setAutoCancel(true).build()
        NotificationManagerCompat.from(applicationContext).notify((id + day).hashCode(), notification)
        return Result.success()
    }
}
suspend fun scheduleReminders(context: Context, state: State) {
    val manager = WorkManager.getInstance(context)
    manager.cancelAllWorkByTag("reminders").result.get()
    if (!state.preferences.notifications) return
    val zone = ZoneId.of(state.preferences.timezone)
    val now = ZonedDateTime.now(zone)
    state.schedule.filter { !it.completed }.forEach { slot ->
        val start = LocalDate.parse(slot.date).atStartOfDay(zone).plusMinutes(slot.start.toLong())
        listOf("before" to start.minusMinutes(state.preferences.reminderMinutes.toLong()), "start" to start,
            "overdue" to start.plusMinutes(15)).forEach { (kind, at) ->
            val delay = java.time.Duration.between(now, at).toMillis()
            if (delay > 0 && kind in state.preferences.notificationKinds) manager.enqueueUniqueWork("reminder-$kind-${slot.activityId}-${slot.date}", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReminderWorker>().addTag("reminders")
                    .setInputData(workDataOf("activityId" to slot.activityId, "date" to slot.date, "kind" to kind)).setInitialDelay(delay, TimeUnit.MILLISECONDS).build())
        }
    }
    listOf("daily" to 1L, "weekly" to 7L, "pending" to 1L).forEach { (kind, days) ->
        manager.enqueueUniquePeriodicWork("summary-$kind", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SummaryWorker>(days, TimeUnit.DAYS).setInitialDelay(1, TimeUnit.HOURS)
                .setInputData(workDataOf("kind" to kind)).build())
    }
}
class SummaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val state = (applicationContext as CalenflexApplication).repository.state.first()
        val kind = inputData.getString("kind") ?: return Result.failure()
        if(!state.preferences.notifications || kind !in state.preferences.notificationKinds) return Result.success()
        if(ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val day = today(state.preferences.timezone)
        val horizon = if(kind == "weekly") 7L else 1L
        val slots = state.schedule.filter { it.date >= day.toString() && it.date < day.plusDays(horizon).toString() }
        val text = if(kind == "pending") "${state.pending.size} ocurrencias sin asignación" else "${slots.size} actividades · ${slots.sumOf { it.end - it.start }} minutos planificados"
        val intent = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        NotificationManagerCompat.from(applicationContext).notify(kind.hashCode(), NotificationCompat.Builder(applicationContext, "activities")
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar).setContentTitle(if(kind == "weekly") "Tu semana en CalenFleX" else if(kind == "pending") "Revisá tus pendientes" else "Tu día en CalenFleX")
            .setContentText(text).setContentIntent(intent).setAutoCancel(true).build())
        return Result.success()
    }
}
