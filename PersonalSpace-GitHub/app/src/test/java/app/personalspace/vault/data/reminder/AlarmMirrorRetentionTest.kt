package app.personalspace.vault.data.reminder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.personalspace.vault.MutableTestClock
import app.personalspace.vault.domain.model.RecurrenceType
import app.personalspace.vault.domain.model.ReminderItem
import app.personalspace.vault.domain.model.ReminderPriority
import app.personalspace.vault.domain.model.ReminderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmMirrorRetentionTest {

    private lateinit var context: Context
    private lateinit var clock: MutableTestClock
    private lateinit var scheduler: AlarmReminderScheduler
    private val istZone = ZoneId.of("Asia/Kolkata")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "vault_keystore/alarm_schedule_mirror.json").delete()
        clock = MutableTestClock()
        scheduler = AlarmReminderScheduler(context, clock)
    }

    private fun millis(year: Int, month: Int, day: Int): Long =
        ZonedDateTime.of(year, month, day, 9, 5, 0, 0, istZone).toInstant().toEpochMilli()

    @Test
    fun `mirror preserves the original monthly anchor until the user explicitly edits the schedule`() {
        val jan31 = millis(2026, 1, 31)
        val original = ReminderItem(
            id = "monthly-anchor",
            title = "Rent",
            scheduledAtMillis = jan31,
            recurrence = RecurrenceType.MONTHLY,
            priority = ReminderPriority.HIGH,
            status = ReminderStatus.PENDING,
            createdAt = jan31,
            modifiedAt = jan31
        )
        scheduler.scheduleReminder(original, resetRecurrenceAnchor = true)
        assertEquals(31, scheduler.mirroredMonthlyAnchorDay("monthly-anchor")!!)

        // Feb 28 is a clamped trigger; preserving 31 prevents Mar 28 drift.
        val feb28 = millis(2026, 2, 28)
        scheduler.scheduleReminder(original.copy(scheduledAtMillis = feb28, modifiedAt = feb28))
        assertEquals(31, scheduler.mirroredMonthlyAnchorDay("monthly-anchor")!!)

        val mar15 = millis(2026, 3, 15)
        scheduler.scheduleReminder(
            original.copy(scheduledAtMillis = mar15, modifiedAt = mar15),
            resetRecurrenceAnchor = true
        )
        assertEquals(15, scheduler.mirroredMonthlyAnchorDay("monthly-anchor")!!)
    }

    @Test
    fun `boot rescheduling removes stale mirror entries instead of silently filtering them forever`() {
        val staleTrigger = clock.nowMillis() - 120_000L
        val stale = ReminderItem(
            id = "stale-alarm",
            title = "Missed while powered off",
            scheduledAtMillis = staleTrigger,
            status = ReminderStatus.SNOOZED,
            snoozedUntilMillis = staleTrigger,
            priority = ReminderPriority.MEDIUM,
            createdAt = staleTrigger,
            modifiedAt = staleTrigger
        )
        scheduler.scheduleReminder(stale, resetRecurrenceAnchor = true)
        assertNotNull(scheduler.mirroredMonthlyAnchorDay("stale-alarm"))

        scheduler.rescheduleFromMirrorOnBoot()
        assertNull(scheduler.mirroredMonthlyAnchorDay("stale-alarm"))
    }
}
