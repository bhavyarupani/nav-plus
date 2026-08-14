package com.roadpulse.auto.stops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class OpeningHoursEvaluatorTest {
    @Test
    fun `treats 24-7 as always open`() {
        assertEquals(true, OpeningHoursEvaluator.isOpenAt("24/7", at(dayOfMonth = 3, hour = 3)))
    }

    @Test
    fun `evaluates a simple weekday range`() {
        val hours = "Mo-Fr 08:00-20:00"
        // 2026-08-10 is a Monday.
        assertEquals(true, OpeningHoursEvaluator.isOpenAt(hours, at(10, hour = 9)))
        assertEquals(false, OpeningHoursEvaluator.isOpenAt(hours, at(10, hour = 21)))
        // 2026-08-15 is a Saturday; a Mo-Fr-only tag says nothing about it, so it's unknown.
        assertEquals(null, OpeningHoursEvaluator.isOpenAt(hours, at(15, hour = 9)))
    }

    @Test
    fun `supports comma separated day groups and split shifts`() {
        val hours = "Mo-Fr 08:00-12:00,13:00-18:00; Sa 08:00-16:00"
        assertEquals(true, OpeningHoursEvaluator.isOpenAt(hours, at(10, hour = 14)))
        assertEquals(false, OpeningHoursEvaluator.isOpenAt(hours, at(10, hour = 12, minute = 30)))
        assertEquals(true, OpeningHoursEvaluator.isOpenAt(hours, at(15, hour = 10)))
        // Sunday is not covered by either rule block.
        assertEquals(null, OpeningHoursEvaluator.isOpenAt(hours, at(16, hour = 10)))
    }

    @Test
    fun `later rule blocks override earlier ones for the days they cover`() {
        val hours = "Mo-Su 08:00-20:00; Su off"
        assertEquals(true, OpeningHoursEvaluator.isOpenAt(hours, at(10, hour = 10)))
        assertEquals(false, OpeningHoursEvaluator.isOpenAt(hours, at(16, hour = 10)))
    }

    @Test
    fun `handles an overnight wraparound range`() {
        val hours = "Mo-Su 22:00-02:00"
        assertEquals(true, OpeningHoursEvaluator.isOpenAt(hours, at(10, hour = 23)))
        // Just after midnight on Tuesday still belongs to Monday night's shift.
        assertEquals(true, OpeningHoursEvaluator.isOpenAt(hours, at(11, hour = 1)))
        assertEquals(false, OpeningHoursEvaluator.isOpenAt(hours, at(11, hour = 5)))
    }

    @Test
    fun `refuses to guess syntax it does not understand`() {
        assertNull(OpeningHoursEvaluator.isOpenAt("Mo-Fr 08:00-20:00; PH off", at(10, hour = 10)))
        assertNull(OpeningHoursEvaluator.isOpenAt("", at(10, hour = 10)))
        assertNull(OpeningHoursEvaluator.isOpenAt("sunrise-sunset", at(10, hour = 10)))
    }

    private fun at(
        dayOfMonth: Int,
        hour: Int,
        minute: Int = 0,
    ): ZonedDateTime = ZonedDateTime.of(2026, 8, dayOfMonth, hour, minute, 0, 0, ZoneOffset.UTC)
}
