package com.cokkles.gpos.ui.daily

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarUsabilityPolicyTest {
    @Test
    fun weekAlwaysBeginsOnMonday() {
        val sunday = LocalDate.of(2026, 9, 20)
        val start = calendarWeekStart(sunday)
        assertEquals(DayOfWeek.MONDAY, start.dayOfWeek)
        assertEquals(LocalDate.of(2026, 9, 14), start)
    }

    @Test
    fun mondayIsStableWeekStart() {
        val monday = LocalDate.of(2026, 9, 21)
        assertEquals(monday, calendarWeekStart(monday))
    }
}
