package com.roadpulse.auto.quota

data class QuotaSnapshot(
    val used: Int,
    val limit: Int,
    val period: String,
) {
    val remaining: Int = (limit - used).coerceAtLeast(0)
    val isExhausted: Boolean = used >= limit
}

sealed interface QuotaDecision {
    data class Allowed(
        val snapshot: QuotaSnapshot,
    ) : QuotaDecision

    data class Blocked(
        val snapshot: QuotaSnapshot,
    ) : QuotaDecision
}

interface QuotaStore {
    fun readPeriod(key: String): String?

    fun readUsed(key: String): Int

    fun write(
        key: String,
        period: String,
        used: Int,
    )
}

/**
 * A local hard stop for metered API operations.
 *
 * The period provider is injected so the rollover behaviour can be tested without
 * changing the device clock. Cloud-side quotas remain the second line of defence.
 */
class MonthlyQuota(
    private val key: String,
    private val limit: Int,
    private val store: QuotaStore,
    private val periodProvider: () -> String,
) {
    init {
        require(key.isNotBlank()) { "Quota key cannot be blank" }
        require(limit > 0) { "Quota limit must be positive" }
    }

    @Synchronized
    fun snapshot(): QuotaSnapshot {
        val period = periodProvider()
        val used =
            if (store.readPeriod(key) == period) {
                store.readUsed(key).coerceIn(0, limit)
            } else {
                store.write(key, period, 0)
                0
            }
        return QuotaSnapshot(used = used, limit = limit, period = period)
    }

    @Synchronized
    fun tryConsume(units: Int = 1): QuotaDecision {
        require(units > 0) { "Consumed units must be positive" }
        val current = snapshot()
        if (current.used + units > limit) {
            return QuotaDecision.Blocked(current)
        }

        val updated = current.copy(used = current.used + units)
        store.write(key, updated.period, updated.used)
        return QuotaDecision.Allowed(updated)
    }
}
