package app.personalspace.vault.data.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.personalspace.vault.R
import app.personalspace.vault.core.common.IdGenerator
import app.personalspace.vault.core.common.TimeProvider
import app.personalspace.vault.core.security.VaultLockState
import app.personalspace.vault.core.security.VaultSessionManager
import app.personalspace.vault.data.local.db.VaultDatabaseHolder
import app.personalspace.vault.data.local.entity.ReminderOccurrenceEntity
import app.personalspace.vault.data.local.entity.TimelineEventEntity
import app.personalspace.vault.data.local.preferences.UserPreferencesDataSource
import app.personalspace.vault.domain.model.RecurrenceType
import app.personalspace.vault.domain.model.ReminderItem
import app.personalspace.vault.domain.model.ReminderPriority
import app.personalspace.vault.domain.model.ReminderStatus
import app.personalspace.vault.domain.model.TimelineEventType
import app.personalspace.vault.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure recurrence calculator handling calendar edge cases (e.g. Jan 31 -> Feb 28/29, DST shifts).
 */
object ReminderRecurrenceCalculator {
    fun computeNextOccurrenceMillis(
        currentScheduledMillis: Long,
        recurrence: RecurrenceType,
        nowMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long? {
        if (recurrence == RecurrenceType.ONCE) return null
        var cursor: ZonedDateTime = Instant.ofEpochMilli(currentScheduledMillis).atZone(zoneId)
        var safety = 0
        do {
            cursor = when (recurrence) {
                RecurrenceType.ONCE -> return null
                RecurrenceType.DAILY -> cursor.plusDays(1)
                RecurrenceType.WEEKLY -> cursor.plusWeeks(1)
                RecurrenceType.MONTHLY -> cursor.plusMonths(1)
            }
            safety++
        } while (cursor.toInstant().toEpochMilli() <= nowMillis && safety < 1000)
        return cursor.toInstant().toEpochMilli()
    }
}

/**
 * Minimal encrypted-metadata schedule mirror so AlarmManager can reschedule pending alarms
 * after a device reboot even before the user unlocks the SQLCipher vault, WITHOUT storing
 * sensitive record content or notes in plaintext! Only reminderId + triggerEpochMillis + priority
 * are cached in internal device storage.
 */
@Serializable
data class PendingAlarmMirrorEntry(
    val reminderId: String,
    val triggerAtMillis: Long,
    val priority: String
)

@Singleton
class AlarmReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val timeProvider: TimeProvider
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mirrorFile: File
        get() = File(context.filesDir, "vault_keystore/alarm_schedule_mirror.json")

    private val alarmManager: AlarmManager?
        get() = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    fun canScheduleExactAlarms(): Boolean {
        val am = alarmManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else {
            true
        }
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    /**
     * Schedules an alarm via AlarmManager. Uses exact alarms when permitted, and falls back
     * honestly to setAndAllowWhileIdle when exact alarm permission is denied.
     * Returns true if scheduled as an exact alarm, false if scheduled via best-effort fallback.
     */
    fun scheduleReminder(reminder: ReminderItem): Boolean {
        if (reminder.status == ReminderStatus.COMPLETED || reminder.status == ReminderStatus.MISSED) {
            cancelReminder(reminder.id)
            return false
        }
        val triggerAt = reminder.effectiveTriggerMillis
        val am = alarmManager ?: return false
        val pendingIntent = buildAlarmPendingIntent(
            reminderId = reminder.id,
            titleHint = reminder.title,
            priority = reminder.priority.name
        )

        val isExact = canScheduleExactAlarms()
        try {
            if (isExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
            upsertMirrorEntry(
                PendingAlarmMirrorEntry(
                    reminderId = reminder.id,
                    triggerAtMillis = triggerAt,
                    priority = reminder.priority.name
                )
            )
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            return false
        }
        return isExact
    }

    fun cancelReminder(reminderId: String) {
        val am = alarmManager ?: return
        val pi = buildAlarmPendingIntent(reminderId, titleHint = null, priority = "MEDIUM")
        am.cancel(pi)
        removeMirrorEntry(reminderId)
    }

    fun rescheduleFromMirrorOnBoot() {
        val am = alarmManager ?: return
        val now = timeProvider.nowMillis()
        val entries = readMirrorEntries()
        val exactAllowed = canScheduleExactAlarms()
        entries.filter { it.triggerAtMillis > now - 60_000L }.forEach { entry ->
            val pi = buildAlarmPendingIntent(entry.reminderId, titleHint = null, priority = entry.priority)
            runCatching {
                if (exactAllowed) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, entry.triggerAtMillis, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, entry.triggerAtMillis, pi)
                }
            }
        }
    }

    private fun buildAlarmPendingIntent(
        reminderId: String,
        titleHint: String?,
        priority: String
    ): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmReceiver.ACTION_FIRE_REMINDER
            putExtra(ReminderAlarmReceiver.EXTRA_REMINDER_ID, reminderId)
            putExtra(ReminderAlarmReceiver.EXTRA_PRIORITY, priority)
            // Do not embed sensitive notes; title is only shown if vault is unlocked at fire time
            if (titleHint != null) {
                putExtra(ReminderAlarmReceiver.EXTRA_TITLE_FALLBACK, "Personal Space Reminder")
            }
        }
        return PendingIntent.getBroadcast(
            context,
            reminderId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun readMirrorEntries(): List<PendingAlarmMirrorEntry> {
        if (!mirrorFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<PendingAlarmMirrorEntry>>(mirrorFile.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    private fun upsertMirrorEntry(entry: PendingAlarmMirrorEntry) {
        mirrorFile.parentFile?.mkdirs()
        val updated = readMirrorEntries().filterNot { it.reminderId == entry.reminderId } + entry
        mirrorFile.writeText(json.encodeToString(updated), Charsets.UTF_8)
    }

    private fun removeMirrorEntry(reminderId: String) {
        if (!mirrorFile.exists()) return
        val updated = readMirrorEntries().filterNot { it.reminderId == reminderId }
        mirrorFile.writeText(json.encodeToString(updated), Charsets.UTF_8)
    }
}

@Singleton
class ReminderNotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_ID = "personal_space_reminders_v1"
    }

    fun ensureChannelCreated() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.reminder_channel_description)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun postReminderNotification(
        reminderId: String,
        displayTitle: String,
        subtitle: String,
        priority: ReminderPriority
    ) {
        ensureChannelCreated()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_reminder_id", reminderId)
        }
        val contentPi = PendingIntent.getActivity(
            context,
            reminderId.hashCode(),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val completeIntent = Intent(context, ReminderActionReceiver::class.java).apply {
            action = ReminderActionReceiver.ACTION_COMPLETE
            putExtra(ReminderAlarmReceiver.EXTRA_REMINDER_ID, reminderId)
        }
        val completePi = PendingIntent.getBroadcast(
            context,
            (reminderId + "_complete").hashCode(),
            completeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeIntent = Intent(context, ReminderActionReceiver::class.java).apply {
            action = ReminderActionReceiver.ACTION_SNOOZE
            putExtra(ReminderAlarmReceiver.EXTRA_REMINDER_ID, reminderId)
        }
        val snoozePi = PendingIntent.getBroadcast(
            context,
            (reminderId + "_snooze").hashCode(),
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notifPriority = when (priority) {
            ReminderPriority.HIGH -> NotificationCompat.PRIORITY_HIGH
            ReminderPriority.MEDIUM -> NotificationCompat.PRIORITY_DEFAULT
            ReminderPriority.LOW -> NotificationCompat.PRIORITY_LOW
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(displayTitle)
            .setContentText(subtitle)
            .setPriority(notifPriority)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .addAction(0, context.getString(R.string.action_complete), completePi)
            .addAction(0, context.getString(R.string.action_snooze), snoozePi)
            .build()

        NotificationManagerCompat.from(context).notify(reminderId.hashCode(), notification)
    }

    fun dismissNotification(reminderId: String) {
        NotificationManagerCompat.from(context).cancel(reminderId.hashCode())
    }
}

@AndroidEntryPoint
class ReminderAlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_FIRE_REMINDER = "app.personalspace.vault.ACTION_FIRE_REMINDER"
        const val EXTRA_REMINDER_ID = "extra_reminder_id"
        const val EXTRA_PRIORITY = "extra_priority"
        const val EXTRA_TITLE_FALLBACK = "extra_title_fallback"
    }

    @Inject lateinit var sessionManager: VaultSessionManager
    @Inject lateinit var dbHolder: VaultDatabaseHolder
    @Inject lateinit var notificationHelper: ReminderNotificationHelper

    override fun onReceive(context: Context, intent: Intent?) {
        val reminderId = intent?.getStringExtra(EXTRA_REMINDER_ID) ?: return
        val priorityStr = intent.getStringExtra(EXTRA_PRIORITY) ?: "MEDIUM"
        val priority = ReminderPriority.entries.firstOrNull { it.name == priorityStr } ?: ReminderPriority.MEDIUM

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (sessionManager.lockState.value == VaultLockState.UNLOCKED) {
                    val entity = dbHolder.getDatabase().reminderDao().getReminderById(reminderId)
                    if (entity != null && !entity.isDeleted && entity.status != ReminderStatus.COMPLETED.name) {
                        notificationHelper.postReminderNotification(
                            reminderId = reminderId,
                            displayTitle = entity.title,
                            subtitle = "Scheduled reminder • Tap to view",
                            priority = priority
                        )
                    }
                } else {
                    // Vault is locked: protect private title while still alerting user reliably
                    notificationHelper.postReminderNotification(
                        reminderId = reminderId,
                        displayTitle = "Personal Space Reminder",
                        subtitle = "Unlock your vault to view reminder details",
                        priority = priority
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

@AndroidEntryPoint
class ReminderActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_COMPLETE = "app.personalspace.vault.ACTION_COMPLETE_REMINDER"
        const val ACTION_SNOOZE = "app.personalspace.vault.ACTION_SNOOZE_REMINDER"
    }

    @Inject lateinit var sessionManager: VaultSessionManager
    @Inject lateinit var dbHolder: VaultDatabaseHolder
    @Inject lateinit var scheduler: AlarmReminderScheduler
    @Inject lateinit var notificationHelper: ReminderNotificationHelper
    @Inject lateinit var prefs: UserPreferencesDataSource
    @Inject lateinit var timeProvider: TimeProvider

    override fun onReceive(context: Context, intent: Intent?) {
        val reminderId = intent?.getStringExtra(ReminderAlarmReceiver.EXTRA_REMINDER_ID) ?: return
        val action = intent.action ?: return
        notificationHelper.dismissNotification(reminderId)

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (sessionManager.lockState.value != VaultLockState.UNLOCKED) {
                    // If vault is locked and user taps Snooze, reschedule alarm 10 minutes out via mirror
                    if (action == ACTION_SNOOZE) {
                        val snoozeMin = prefs.settingsFlow.first().defaultSnoozeMinutes
                        val trigger = timeProvider.nowMillis() + snoozeMin * 60_000L
                        scheduler.scheduleReminder(
                            ReminderItem(
                                id = reminderId,
                                title = "Personal Space Reminder",
                                scheduledAtMillis = trigger,
                                status = ReminderStatus.SNOOZED,
                                snoozedUntilMillis = trigger,
                                createdAt = trigger,
                                modifiedAt = trigger
                            )
                        )
                    }
                    return@launch
                }

                val db = dbHolder.getDatabase()
                val dao = db.reminderDao()
                val entity = dao.getReminderById(reminderId) ?: return@launch
                val now = timeProvider.nowMillis()

                if (action == ACTION_COMPLETE) {
                    dao.insertOccurrence(
                        ReminderOccurrenceEntity(
                            id = IdGenerator.newUuid(),
                            reminderId = entity.id,
                            reminderTitle = entity.title,
                            occurrenceScheduledMillis = entity.scheduledAtMillis,
                            actualHandledMillis = now,
                            outcome = ReminderStatus.COMPLETED.name
                        )
                    )
                    val recurrence = RecurrenceType.entries.firstOrNull { it.name == entity.recurrenceType }
                        ?: RecurrenceType.ONCE
                    val nextMillis = ReminderRecurrenceCalculator.computeNextOccurrenceMillis(
                        currentScheduledMillis = entity.scheduledAtMillis,
                        recurrence = recurrence,
                        nowMillis = now,
                        zoneId = timeProvider.zoneId()
                    )
                    val updated = if (nextMillis != null) {
                        entity.copy(
                            scheduledAtMillis = nextMillis,
                            status = ReminderStatus.PENDING.name,
                            snoozedUntilMillis = null,
                            modifiedAt = now,
                            syncVersion = entity.syncVersion + 1
                        )
                    } else {
                        entity.copy(
                            status = ReminderStatus.COMPLETED.name,
                            snoozedUntilMillis = null,
                            modifiedAt = now,
                            syncVersion = entity.syncVersion + 1
                        )
                    }
                    dao.upsert(updated)
                    db.timelineDao().insert(
                        TimelineEventEntity(
                            id = IdGenerator.newUuid(),
                            eventType = TimelineEventType.REMINDER_COMPLETED.name,
                            entityType = "REMINDER",
                            entityId = entity.id,
                            displayTitle = entity.title,
                            accentColor = "BLUE",
                            timestampMillis = now
                        )
                    )
                    if (nextMillis != null) {
                        scheduler.scheduleReminder(
                            ReminderItem(
                                id = updated.id,
                                title = updated.title,
                                scheduledAtMillis = nextMillis,
                                recurrence = recurrence,
                                priority = ReminderPriority.valueOf(updated.priority),
                                status = ReminderStatus.PENDING,
                                createdAt = updated.createdAt,
                                modifiedAt = now
                            )
                        )
                    } else {
                        scheduler.cancelReminder(updated.id)
                    }
                } else if (action == ACTION_SNOOZE) {
                    val snoozeMin = prefs.settingsFlow.first().defaultSnoozeMinutes
                    val snoozeUntil = now + snoozeMin * 60_000L
                    val updated = entity.copy(
                        status = ReminderStatus.SNOOZED.name,
                        snoozedUntilMillis = snoozeUntil,
                        modifiedAt = now,
                        syncVersion = entity.syncVersion + 1
                    )
                    dao.upsert(updated)
                    dao.insertOccurrence(
                        ReminderOccurrenceEntity(
                            id = IdGenerator.newUuid(),
                            reminderId = entity.id,
                            reminderTitle = entity.title,
                            occurrenceScheduledMillis = entity.scheduledAtMillis,
                            actualHandledMillis = now,
                            outcome = ReminderStatus.SNOOZED.name
                        )
                    )
                    scheduler.scheduleReminder(
                        ReminderItem(
                            id = updated.id,
                            title = updated.title,
                            scheduledAtMillis = updated.scheduledAtMillis,
                            snoozedUntilMillis = snoozeUntil,
                            status = ReminderStatus.SNOOZED,
                            priority = ReminderPriority.valueOf(updated.priority),
                            createdAt = updated.createdAt,
                            modifiedAt = now
                        )
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

@AndroidEntryPoint
class BootAndTimeChangeReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: AlarmReminderScheduler
    @Inject lateinit var sessionManager: VaultSessionManager
    @Inject lateinit var dbHolder: VaultDatabaseHolder

    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (sessionManager.lockState.value == VaultLockState.UNLOCKED) {
                    val reminders = dbHolder.getDatabase().reminderDao().getAllActiveReminders()
                    reminders.filter {
                        it.status == ReminderStatus.PENDING.name || it.status == ReminderStatus.SNOOZED.name
                    }.forEach { entity ->
                        scheduler.scheduleReminder(
                            ReminderItem(
                                id = entity.id,
                                title = entity.title,
                                notes = entity.notes,
                                scheduledAtMillis = entity.scheduledAtMillis,
                                recurrence = RecurrenceType.entries.firstOrNull { it.name == entity.recurrenceType } ?: RecurrenceType.ONCE,
                                priority = ReminderPriority.entries.firstOrNull { it.name == entity.priority } ?: ReminderPriority.MEDIUM,
                                status = ReminderStatus.entries.firstOrNull { it.name == entity.status } ?: ReminderStatus.PENDING,
                                snoozedUntilMillis = entity.snoozedUntilMillis,
                                createdAt = entity.createdAt,
                                modifiedAt = entity.modifiedAt
                            )
                        )
                    }
                } else {
                    scheduler.rescheduleFromMirrorOnBoot()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
