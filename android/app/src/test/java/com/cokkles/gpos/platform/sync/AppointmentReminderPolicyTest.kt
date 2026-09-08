package com.cokkles.gpos.platform.sync

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppointmentReminderPolicyTest {
    private val now = 1_000_000L

    @Test fun computesSixtyAndFifteenMinuteReminderTimes() {
        val start = now + TimeUnit.HOURS.toMillis(2)
        assertTrue(
            AppointmentReminderPolicy.triggerAt(start, 60) ==
                start - TimeUnit.MINUTES.toMillis(60),
        )
        assertTrue(
            AppointmentReminderPolicy.triggerAt(start, 15) ==
                start - TimeUnit.MINUTES.toMillis(15),
        )
    }

    @Test fun schedulesOnlyFutureTriggersWithinSevenDays() {
        val inTwoHours = now + TimeUnit.HOURS.toMillis(2)
        assertTrue(
            AppointmentReminderPolicy.shouldSchedule(
                inTwoHours,
                AppointmentReminderPolicy.triggerAt(inTwoHours, 60),
                now,
            ),
        )
        assertFalse(AppointmentReminderPolicy.shouldSchedule(now - 1, now - 2, now))
        assertFalse(
            AppointmentReminderPolicy.shouldSchedule(
                now + AppointmentReminderPolicy.LOOKAHEAD_MS + 1,
                now + TimeUnit.HOURS.toMillis(1),
                now,
            ),
        )
        assertFalse(AppointmentReminderPolicy.shouldSchedule(inTwoHours, now + 30_000L, now))
    }
}
