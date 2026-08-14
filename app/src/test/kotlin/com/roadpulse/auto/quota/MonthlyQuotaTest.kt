package com.roadpulse.auto.quota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonthlyQuotaTest {
    @Test
    fun `blocks before exceeding the monthly limit`() {
        val quota = MonthlyQuota("search", 2, FakeQuotaStore()) { "2026-08" }

        assertTrue(quota.tryConsume() is QuotaDecision.Allowed)
        assertTrue(quota.tryConsume() is QuotaDecision.Allowed)
        val blocked = quota.tryConsume() as QuotaDecision.Blocked

        assertEquals(2, blocked.snapshot.used)
        assertEquals(0, blocked.snapshot.remaining)
    }

    @Test
    fun `resets automatically when the UTC month changes`() {
        val store = FakeQuotaStore()
        var period = "2026-08"
        val quota = MonthlyQuota("search", 1, store) { period }
        quota.tryConsume()

        period = "2026-09"
        val snapshot = quota.snapshot()

        assertEquals("2026-09", snapshot.period)
        assertEquals(0, snapshot.used)
        assertEquals(1, snapshot.remaining)
    }

    @Test
    fun `multi-destination request cannot cross the limit`() {
        val quota = MonthlyQuota("navigation", 3, FakeQuotaStore()) { "2026-08" }
        quota.tryConsume(2)

        assertTrue(quota.tryConsume(2) is QuotaDecision.Blocked)
        assertEquals(2, quota.snapshot().used)
    }
}

private class FakeQuotaStore : QuotaStore {
    private val periods = mutableMapOf<String, String>()
    private val usage = mutableMapOf<String, Int>()

    override fun readPeriod(key: String): String? = periods[key]

    override fun readUsed(key: String): Int = usage[key] ?: 0

    override fun write(
        key: String,
        period: String,
        used: Int,
    ) {
        periods[key] = period
        usage[key] = used
    }
}
