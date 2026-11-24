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
