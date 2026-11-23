package app.personalspace.vault.core.common

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface TimeProvider {
    fun nowMillis(): Long
    fun zoneId(): ZoneId
    fun today(): LocalDate = Instant.ofEpochMilli(nowMillis()).atZone(zoneId()).toLocalDate()
}

@Singleton
class SystemTimeProvider @Inject constructor() : TimeProvider {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun zoneId(): ZoneId = ZoneId.systemDefault()
}

object IdGenerator {
    fun newUuid(): String = UUID.randomUUID().toString()
}

object DateFormatters {
    private val dateTimeFmt = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")
    private val shortDateTimeFmt = DateTimeFormatter.ofPattern("dd MMM, HH:mm")
    private val dateOnlyFmt = DateTimeFormatter.ofPattern("dd MMM yyyy")
    private val dayMonthFmt = DateTimeFormatter.ofPattern("EEE, dd MMM")
    private val time24Fmt = DateTimeFormatter.ofPattern("HH:mm")
    private val shortDayFmt = DateTimeFormatter.ofPattern("EEE")

    fun formatDateTime(epochMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
        if (epochMillis <= 0L) return "Never"
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zoneId)
        return dateTimeFmt.format(dt)
    }

    fun formatShortDateTime(epochMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
        if (epochMillis <= 0L) return "—"
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zoneId)
        return shortDateTimeFmt.format(dt)
    }

    fun formatDate(epochMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
        if (epochMillis <= 0L) return "—"
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zoneId)
        return dateOnlyFmt.format(dt)
    }

    fun formatTime24(epochMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zoneId)
        return time24Fmt.format(dt)
    }

    fun formatShortDay(date: LocalDate): String = shortDayFmt.format(date)

    fun formatDayMonth(date: LocalDate): String = dayMonthFmt.format(date)

    /**
     * Alarm-style remaining countdown for Upcoming Reminders:
     * - Under 24 hours: e.g. "2 hours 30 minutes", "45 minutes", "3 hours"
     * - 24 hours or more: e.g. "2 Days 5 hours", "1 Day 0 hours"
     */
    fun formatReminderRemaining(targetMillis: Long, nowMillis: Long): String {
        val diffMillis = targetMillis - nowMillis
        if (diffMillis <= 0L) return "Due now"

        val dayMillis = 24L * 3_600_000L
        return if (diffMillis < dayMillis) {
            val totalMinutes = ((diffMillis + 59_999L) / 60_000L).coerceAtLeast(1L)
            val hours = totalMinutes / 60L
            val minutes = totalMinutes % 60L
            when {
                hours > 0L && minutes > 0L -> {
                    val hLabel = if (hours == 1L) "hour" else "hours"
                    val mLabel = if (minutes == 1L) "minute" else "minutes"
                    "$hours $hLabel $minutes $mLabel"
                }
                hours > 0L -> {
                    val hLabel = if (hours == 1L) "hour" else "hours"
                    "$hours $hLabel"
                }
                else -> {
                    val mLabel = if (minutes == 1L) "minute" else "minutes"
                    "$minutes $mLabel"
                }
            }
        } else {
            val totalHours = diffMillis / 3_600_000L
            val days = totalHours / 24L
            val remHours = totalHours % 24L
            val dLabel = if (days == 1L) "Day" else "Days"
            val hLabel = if (remHours == 1L) "hour" else "hours"
            "$days $dLabel $remHours $hLabel"
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format("%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format("%.2f GB", gb)
    }

    /**
     * Truncates overly long note/folder/reminder titles so only the starting text + "..."
     * is shown in paths, alarms, dialogs, and tags.
     */
    fun ellipsizeTitle(text: String, maxChars: Int = 22): String {
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.length <= maxChars) return cleaned
        return cleaned.take(maxChars).trimEnd() + "..."
    }

    /**
     * Computes a default alarm timestamp equal to (current time + 1 minute), rounded to the minute.
     * When [targetDate] is provided, keeps [targetDate] and applies (current local time + 1 minute).
     */
    fun nextMinuteEpochMillis(
        nowMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        targetDate: LocalDate? = null
    ): Long {
        val plusOneMin = Instant.ofEpochMilli(nowMillis)
            .atZone(zoneId)
            .plusMinutes(1)
            .withSecond(0)
            .withNano(0)
        return if (targetDate != null) {
            targetDate.atTime(plusOneMin.toLocalTime()).atZone(zoneId).toInstant().toEpochMilli()
        } else {
            plusOneMin.toInstant().toEpochMilli()
        }
    }
}

sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Error(val message: String, val cause: Throwable? = null) : AppResult<Nothing>
}
