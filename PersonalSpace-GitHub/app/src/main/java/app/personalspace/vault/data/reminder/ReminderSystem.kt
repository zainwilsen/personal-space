package app.personalspace.vault.data.reminder

import android.Manifest
import android.app.AlarmManager
import android.util.Log
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
import app.personalspace.vault.data.local.db.PersonalSpaceDatabase
import app.personalspace.vault.data.local.db.VaultDatabaseHolder
import app.personalspace.vault.data.local.entity.ReminderEntity
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
import kotlinx.coroutines.CoroutineExceptionHandler
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
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Broadcast receivers must never crash the app because a DB read, enum value, or notification call
 * failed. `goAsync()` still finishes in the receivers' `finally` blocks.
 */
private val reminderReceiverErrorHandler = CoroutineExceptionHandler { _, error ->
    Log.e("PersonalSpaceReminders", "Reminder receiver work failed", error)
}

private fun parseReminderPriority(value: String?): ReminderPriority =
    ReminderPriority.entries.firstOrNull { it.name == value } ?: ReminderPriority.MEDIUM

private fun parseRecurrenceType(value: String?): RecurrenceType =
    RecurrenceType.entries.firstOrNull { it.name == value } ?: RecurrenceType.ONCE

/**
 * The original day-of-month is not stored in the Room entity. Prefer the alarm mirror (explicitly
 * reset when the user edits a reminder), then the first logged occurrence, then the current date.
 * This keeps "31st" monthly reminders from drifting after a clamped February without a DB migration.
 */
internal suspend fun resolveMonthlyAnchorDay(
    db: PersonalSpaceDatabase,
    scheduler: AlarmReminderScheduler,
    entity: ReminderEntity,
    zoneId: ZoneId
): Int = scheduler.mirroredMonthlyAnchorDay(entity.id)
    ?: db.reminderDao().getEarliestOccurrenceScheduledMillis(entity.id)?.let {
        Instant.ofEpochMilli(it).atZone(zoneId).dayOfMonth
    }
    ?: Instant.ofEpochMilli(entity.scheduledAtMillis).atZone(zoneId).dayOfMonth

/**
 * Pure recurrence calculator handling calendar edge cases (e.g. Jan 31 -> Feb 28/29, DST shifts).
 *
 * The old 1000-iteration loop returned a PAST timestamp for a daily reminder more than ~2.7 years
 * overdue, and repeatedly applying `plusMonths` to a clamped Feb 28 drifted a "31st" reminder to
 * the 28th forever. This version jumps arithmetically and preserves the caller-supplied original
 * day-of-month for monthly recurrences.
 */
object ReminderRecurrenceCalculator {
    fun computeNextOccurrenceMillis(
        currentScheduledMillis: Long,
        recurrence: RecurrenceType,
        nowMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        /** Original day-of-month for MONTHLY (1..31). Null = derive from currentScheduledMillis. */
        monthlyAnchorDay: Int? = null
    ): Long? {
        if (recurrence == RecurrenceType.ONCE) return null
        val start = Instant.ofEpochMilli(currentScheduledMillis).atZone(zoneId)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        val anchorDay = monthlyAnchorDay?.takeIf { it in 1..31 } ?: start.dayOfMonth

        // Jump close to `now` in one step instead of looping thousands of times. We always advance
        // at least one period: the caller has just handled the occurrence at currentScheduledMillis.
        var periods = when (recurrence) {
            RecurrenceType.ONCE -> return null
            RecurrenceType.DAILY -> ChronoUnit.DAYS.between(start.toLocalDate(), now.toLocalDate())
            RecurrenceType.WEEKLY -> ChronoUnit.WEEKS.between(start.toLocalDate(), now.toLocalDate())
            RecurrenceType.MONTHLY -> ChronoUnit.MONTHS.between(YearMonth.from(start), YearMonth.from(now))
        }.coerceAtLeast(1L)

        var candidate = occurrenceAt(periods, start, recurrence, anchorDay, zoneId)
        // The arithmetic estimate can be one period short because of time-of-day or month clamping.
        // A tiny correction loop is enough; NEVER return a past trigger (AlarmManager fires it
        // immediately and the receiver could then loop forever).
        var corrections = 0
        while (candidate.toInstant().toEpochMilli() <= nowMillis && corrections < 4) {
            periods++
            candidate = occurrenceAt(periods, start, recurrence, anchorDay, zoneId)
            corrections++
        }
        val candidateMillis = candidate.toInstant().toEpochMilli()
        if (candidateMillis <= nowMillis) {
            // Defensive fallback for pathological clock/date values: still keep the reminder alive.
            return nowMillis + 60_000L
        }
        return candidateMillis
    }

    private fun occurrenceAt(
        periods: Long,
        start: ZonedDateTime,
        recurrence: RecurrenceType,
        anchorDay: Int,
        zoneId: ZoneId
    ): ZonedDateTime = when (recurrence) {
        RecurrenceType.ONCE -> start
        RecurrenceType.DAILY -> start.plusDays(periods)
        RecurrenceType.WEEKLY -> start.plusWeeks(periods)
        RecurrenceType.MONTHLY -> {
            // Rebuild from the ORIGINAL day-of-month so a clamped Feb 28 still yields Mar 31.
            val targetMonth = YearMonth.from(start).plusMonths(periods)
            val day = anchorDay.coerceAtMost(targetMonth.lengthOfMonth())
            targetMonth.atDay(day)
                .atTime(start.toLocalTime())
                .atZone(zoneId)
        }
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
    val priority: String,
    /** Stored so MONTHLY reminders keep their original day (Jan 31 -> Feb 28 -> Mar 31). */
    val recurrenceType: String = "ONCE",
    val monthlyAnchorDay: Int = 0
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
    fun scheduleReminder(
        reminder: ReminderItem,
        /** Set when the user explicitly chooses a NEW schedule; preserves the monthly anchor otherwise. */
        resetRecurrenceAnchor: Boolean = false
    ): Boolean {
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

        // Persist the original day-of-month. `currentScheduledMillis` is clamped for short months,
        // so re-deriving the anchor from every new trigger would turn "31st" into "28th" forever.
        val existingEntry = readMirrorEntries().firstOrNull { it.reminderId == reminder.id }
        val anchorDay = if (resetRecurrenceAnchor) {
            Instant.ofEpochMilli(triggerAt).atZone(timeProvider.zoneId()).dayOfMonth
        } else {
            existingEntry?.monthlyAnchorDay?.takeIf { it in 1..31 }
                ?: Instant.ofEpochMilli(triggerAt).atZone(timeProvider.zoneId()).dayOfMonth
        }
        val mirrorEntry = PendingAlarmMirrorEntry(
            reminderId = reminder.id,
            triggerAtMillis = triggerAt,
            priority = reminder.priority.name,
            recurrenceType = reminder.recurrence.name,
            monthlyAnchorDay = anchorDay
        )

        var exactScheduled = false
        var scheduled = false
        try {
            if (canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                exactScheduled = true
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
            scheduled = true
        } catch (_: SecurityException) {
            // Exact-alarm permission can be revoked without the app being restarted. The fallback
            // MUST still update the mirror or this reminder disappears after the next reboot.
            runCatching {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                scheduled = true
            }
        }
        if (scheduled) upsertMirrorEntry(mirrorEntry)
        return exactScheduled
    }

    /** Returns the persisted monthly anchor, or null when this reminder has no valid mirror entry. */
    fun mirroredMonthlyAnchorDay(reminderId: String): Int? =
        readMirrorEntries().firstOrNull { it.reminderId == reminderId }
            ?.monthlyAnchorDay
            ?.takeIf { it in 1..31 }

    fun cancelReminder(reminderId: String) {
        removeMirrorEntries(setOf(reminderId))
        val am = alarmManager ?: return
        val pi = buildAlarmPendingIntent(reminderId, titleHint = null, priority = "MEDIUM")
        am.cancel(pi)
    }

    fun rescheduleFromMirrorOnBoot() {
        val now = timeProvider.nowMillis()
        val entries = readMirrorEntries()
        // A trigger older than the grace window has already been handled (or will be marked MISSED
        // by refreshMissedReminders once the vault is unlocked). Prune it even if AlarmManager is
        // unavailable, so the mirror file cannot grow forever.
        val staleIds = entries.filter { it.triggerAtMillis <= now - 60_000L }
            .mapTo(HashSet<String>()) { it.reminderId }
        removeMirrorEntries(staleIds)

        val am = alarmManager ?: return
        val exactAllowed = canScheduleExactAlarms()
        entries.filter { it.reminderId !in staleIds }.forEach { entry ->
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
        val updated = readMirrorEntries().filterNot { it.reminderId == entry.reminderId } + entry
        writeMirrorEntries(updated)
    }

    private fun removeMirrorEntries(reminderIds: Set<String>) {
        if (reminderIds.isEmpty() || !mirrorFile.exists()) return
        val updated = readMirrorEntries().filterNot { it.reminderId in reminderIds }
        writeMirrorEntries(updated)
    }

    private fun writeMirrorEntries(entries: List<PendingAlarmMirrorEntry>) {
        mirrorFile.parentFile?.mkdirs()
        // tmp + rename so a crash while writing cannot truncate every boot-time alarm entry.
        val tmp = File(mirrorFile.parentFile, "${mirrorFile.name}.tmp")
        tmp.writeText(json.encodeToString(entries), Charsets.UTF_8)
        if (!tmp.renameTo(mirrorFile)) {
            tmp.copyTo(mirrorFile, overwrite = true)
            tmp.delete()
        }
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
        priority: ReminderPriority,
        /** Locked vault cannot mutate the encrypted DB, so do not offer a no-op Complete action. */
        showCompleteAction: Boolean = true
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
            .apply {
                if (showCompleteAction) {
                    addAction(0, context.getString(R.string.action_complete), completePi)
                } else {
                    // Tapping either the notification or this action opens the unlock flow.
                    addAction(0, context.getString(R.string.action_unlock), contentPi)
                }
                addAction(0, context.getString(R.string.action_snooze), snoozePi)
            }
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
        val priority = parseReminderPriority(intent.getStringExtra(EXTRA_PRIORITY))

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO + reminderReceiverErrorHandler).launch {
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
                    // Vault is locked: protect private title, and DO NOT show a Complete action that
                    // would silently do nothing against the encrypted database.
                    notificationHelper.postReminderNotification(
                        reminderId = reminderId,
                        displayTitle = "Personal Space Reminder",
                        subtitle = context.getString(R.string.reminder_locked_complete_hint),
                        priority = priority,
                        showCompleteAction = false
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
        val actionPriority = parseReminderPriority(intent.getStringExtra(ReminderAlarmReceiver.EXTRA_PRIORITY))

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO + reminderReceiverErrorHandler).launch {
            try {
                if (sessionManager.lockState.value != VaultLockState.UNLOCKED) {
                    // Snooze works without the encrypted DB; completion does not. Do not silently
                    // swallow Complete: restore an unlock-only notification with a clear hint.
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
                    } else if (action == ACTION_COMPLETE) {
                        notificationHelper.postReminderNotification(
                            reminderId = reminderId,
                            displayTitle = "Personal Space Reminder",
                            subtitle = context.getString(R.string.reminder_locked_complete_hint),
                            priority = actionPriority,
                            showCompleteAction = false
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
                    val recurrence = parseRecurrenceType(entity.recurrenceType)
                    val anchorDay = resolveMonthlyAnchorDay(db, scheduler, entity, timeProvider.zoneId())
                    val nextMillis = ReminderRecurrenceCalculator.computeNextOccurrenceMillis(
                        currentScheduledMillis = entity.scheduledAtMillis,
                        recurrence = recurrence,
                        nowMillis = now,
                        zoneId = timeProvider.zoneId(),
                        monthlyAnchorDay = anchorDay
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
                                priority = parseReminderPriority(updated.priority),
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
                            priority = parseReminderPriority(updated.priority),
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
        CoroutineScope(SupervisorJob() + Dispatchers.IO + reminderReceiverErrorHandler).launch {
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
