package app.personalspace.vault.data.reminder

import app.personalspace.vault.core.common.DateFormatters
import app.personalspace.vault.domain.model.RecurrenceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderRecurrenceAndDateFormatTest {

    private val istZone: ZoneId = ZoneId.of("Asia/Kolkata")

    @Test
    fun `ONCE recurrence returns null next occurrence while DAILY WEEKLY and MONTHLY advance accurately`() {
        val jan31 = ZonedDateTime.of(2026, 1, 31, 9, 5, 0, 0, istZone)
        val jan31Millis = jan31.toInstant().toEpochMilli()

        assertNull(
            ReminderRecurrenceCalculator.computeNextOccurrenceMillis(
                currentScheduledMillis = jan31Millis,
                recurrence = RecurrenceType.ONCE,
                nowMillis = jan31Millis,
                zoneId = istZone
            )
        )

        // Daily: Jan 31 09:05 -> Feb 1 09:05
        val nextDaily = ReminderRecurrenceCalculator.computeNextOccurrenceMillis(
            currentScheduledMillis = jan31Millis,
            recurrence = RecurrenceType.DAILY,
            nowMillis = jan31Millis,
            zoneId = istZone
        )!!
        val dailyZdt = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nextDaily), istZone)
        assertEquals(2, dailyZdt.monthValue)
        assertEquals(1, dailyZdt.dayOfMonth)
        assertEquals(9, dailyZdt.hour)
        assertEquals(5, dailyZdt.minute)

        // Weekly: Jan 31 -> Feb 7
        val nextWeekly = ReminderRecurrenceCalculator.computeNextOccurrenceMillis(
            currentScheduledMillis = jan31Millis,
            recurrence = RecurrenceType.WEEKLY,
            nowMillis = jan31Millis,
            zoneId = istZone
        )!!
        val weeklyZdt = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nextWeekly), istZone)
        assertEquals(2, weeklyZdt.monthValue)
        assertEquals(7, weeklyZdt.dayOfMonth)

        // Monthly calendar clamping: Jan 31 2026 (non-leap year) -> Feb 28 2026 09:05
        val nextMonthly = ReminderRecurrenceCalculator.computeNextOccurrenceMillis(
            currentScheduledMillis = jan31Millis,
            recurrence = RecurrenceType.MONTHLY,
            nowMillis = jan31Millis,
            zoneId = istZone
        )!!
        val monthlyZdt = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nextMonthly), istZone)
        assertEquals(2026, monthlyZdt.year)
        assertEquals(2, monthlyZdt.monthValue)
        assertEquals(28, monthlyZdt.dayOfMonth)
        assertEquals(9, monthlyZdt.hour)
        assertEquals(5, monthlyZdt.minute)
    }

    @Test
    fun `overdue DAILY reminder jumps past a 1000-day gap instead of returning a past timestamp`() {
        val start = ZonedDateTime.of(2023, 1, 1, 9, 5, 0, 0, istZone)
        val startMillis = start.toInstant().toEpochMilli()
        // Old loop stopped at 1000 iterations and still returned a trigger in the past.
        val nowMillis = start.plusDays(1_200L).toInstant().toEpochMilli()

        val next = ReminderRecurrenceCalculator.computeNextOccurrenceMillis(
            currentScheduledMillis = startMillis,
            recurrence = RecurrenceType.DAILY,
            nowMillis = nowMillis,
            zoneId = istZone
        )!!

        assertTrue("next occurrence must be in the future", next > nowMillis)
        val nextZdt = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), istZone)
        assertEquals(start.plusDays(1_201L).toLocalDate(), nextZdt.toLocalDate())
        assertEquals(9, nextZdt.hour)
        assertEquals(5, nextZdt.minute)
    }

    @Test
    fun `MONTHLY recurrence preserves the original day after a clamped February`() {
        val jan31 = ZonedDateTime.of(2026, 1, 31, 9, 5, 0, 0, istZone)
        val jan31Millis = jan31.toInstant().toEpochMilli()

        val feb28 = ReminderRecurrenceCalculator.computeNextOccurrenceMillis(
            currentScheduledMillis = jan31Millis,
            recurrence = RecurrenceType.MONTHLY,
            nowMillis = jan31Millis,
            zoneId = istZone,
            monthlyAnchorDay = 31
        )!!
        val feb28Zdt = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(feb28), istZone)
        assertEquals(2, feb28Zdt.monthValue)
        assertEquals(28, feb28Zdt.dayOfMonth)

        // Re-applying plusMonths to the clamped date used to drift to Mar 28; preserve Mar 31.
        val mar31 = ReminderRecurrenceCalculator.computeNextOccurrenceMillis(
            currentScheduledMillis = feb28,
            recurrence = RecurrenceType.MONTHLY,
            nowMillis = feb28,
            zoneId = istZone,
            monthlyAnchorDay = 31
        )!!
        val mar31Zdt = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(mar31), istZone)
        assertEquals(3, mar31Zdt.monthValue)
        assertEquals(31, mar31Zdt.dayOfMonth)

        val apr30 = ReminderRecurrenceCalculator.computeNextOccurrenceMillis(
            currentScheduledMillis = mar31,
            recurrence = RecurrenceType.MONTHLY,
            nowMillis = mar31,
            zoneId = istZone,
            monthlyAnchorDay = 31
        )!!
        val apr30Zdt = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(apr30), istZone)
        assertEquals(4, apr30Zdt.monthValue)
        assertEquals(30, apr30Zdt.dayOfMonth)
        assertEquals(9, apr30Zdt.hour)
        assertEquals(5, apr30Zdt.minute)
    }

    @Test
    fun `24-hour clock formatters always render zero-padded HH_mm without AM or PM`() {
        val morning = ZonedDateTime.of(2026, 11, 15, 9, 5, 0, 0, istZone).toInstant().toEpochMilli()
        val afternoon = ZonedDateTime.of(2026, 11, 15, 14, 30, 0, 0, istZone).toInstant().toEpochMilli()
        val midnight = ZonedDateTime.of(2026, 11, 15, 0, 0, 0, 0, istZone).toInstant().toEpochMilli()

        val fmtMorning = DateFormatters.formatTime24(morning, istZone)
        val fmtAfternoon = DateFormatters.formatTime24(afternoon, istZone)
        val fmtMidnight = DateFormatters.formatTime24(midnight, istZone)

        assertEquals("09:05", fmtMorning)
        assertEquals("14:30", fmtAfternoon)
        assertEquals("00:00", fmtMidnight)
        assertFalse(fmtAfternoon.contains("PM", ignoreCase = true))
        assertTrue(DateFormatters.formatDateTime(afternoon, istZone).contains("14:30"))
    }
}
