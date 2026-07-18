package com.fu1fan.smartpot

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.fu1fan.smartpot.protocol.ScheduleItem
import java.time.Instant

private const val ScheduleChannelId = "schedule_reminders"
private const val ScheduleAction = "com.fu1fan.smartpot.SCHEDULE_REMINDER"
private const val SchedulePreferencesName = "schedule_reminder_alarms"
private const val ScheduledIdsKey = "scheduled_ids"
private const val NotifiedIdsKey = "notified_ids"

class ScheduleReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("schedule_id") ?: return
        val title = intent.getStringExtra("schedule_title").orEmpty()
        val potName = intent.getStringExtra("pot_name").orEmpty().ifBlank { "小麦智能盆栽" }
        val preferences = context.getSharedPreferences(SchedulePreferencesName, Context.MODE_PRIVATE)
        val notified = preferences.getStringSet(NotifiedIdsKey, emptySet()).orEmpty() + id
        preferences.edit().putStringSet(NotifiedIdsKey, notified).apply()
        createScheduleNotificationChannel(context)
        val openApp = PendingIntent.getActivity(
            context,
            id.hashCode(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        context.getSystemService(NotificationManager::class.java).notify(
            id.hashCode(),
            NotificationCompat.Builder(context, ScheduleChannelId)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("$potName 日程提醒")
                .setContentText(title)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build(),
        )
    }
}

object ScheduleReminderScheduler {
    fun sync(context: Context, items: List<ScheduleItem>, potName: String) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val preferences = context.getSharedPreferences(SchedulePreferencesName, Context.MODE_PRIVATE)
        val previous = preferences.getStringSet(ScheduledIdsKey, emptySet()).orEmpty()
        val notified = preferences.getStringSet(NotifiedIdsKey, emptySet()).orEmpty()
        val now = System.currentTimeMillis()
        val active = items.filter { !it.completed && it.id !in notified }.mapNotNull { item ->
            val dueMillis = item.dueAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: return@mapNotNull null
            item to dueMillis
        }
        val activeIds = active.mapTo(mutableSetOf()) { it.first.id }

        (previous - activeIds).forEach { id -> alarms.cancel(pendingIntent(context, id, "", potName)) }
        active.forEach { (item, dueMillis) ->
            val triggerAt = dueMillis.coerceAtLeast(now + 250)
            alarms.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pendingIntent(context, item.id, item.title, potName),
            )
        }
        val retainedNotified = notified.intersect(items.mapTo(mutableSetOf()) { it.id })
        preferences.edit()
            .putStringSet(ScheduledIdsKey, activeIds)
            .putStringSet(NotifiedIdsKey, retainedNotified)
            .apply()
    }

    private fun pendingIntent(context: Context, id: String, title: String, potName: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            Intent(context, ScheduleReminderReceiver::class.java).apply {
                action = ScheduleAction
                putExtra("schedule_id", id)
                putExtra("schedule_title", title)
                putExtra("pot_name", potName)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

fun createScheduleNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= 26) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ScheduleChannelId, "日程提醒", NotificationManager.IMPORTANCE_HIGH),
        )
    }
}
