package com.roadpulse.auto.stops

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Evaluates a subset of the OSM `opening_hours` tag syntax: `24/7`, day
 * ranges/lists, comma-separated time ranges (including overnight wraparound),
 * and `;`-separated rule blocks where a later block overrides earlier ones for
 * the days it covers. Anything outside that subset (public holidays, seasonal
 * comments, etc.) makes the whole tag unknown rather than guessed, matching
 * the rest of RoadPulse's policy of never guessing missing data.
 */
object OpeningHoursEvaluator {
    /** Returns null when [openingHours] cannot be evaluated with confidence. */
    fun isOpenAt(
        openingHours: String,
        at: ZonedDateTime,
    ): Boolean? {
        val rules =
            openingHours
                .trim()
                .split(';')
                .map(String::trim)
                .filter(String::isNotEmpty)
        if (rules.isEmpty()) return null

        var result: Boolean? = null
        rules.forEach { rule ->
            when (val outcome = evaluateRule(rule, at)) {
                is RuleOutcome.Unparseable -> return null
                is RuleOutcome.DoesNotApply -> Unit
                is RuleOutcome.Applies -> result = outcome.open
            }
        }
        return result
    }

    private sealed interface RuleOutcome {
        data object DoesNotApply : RuleOutcome

        data object Unparseable : RuleOutcome

        data class Applies(
            val open: Boolean,
        ) : RuleOutcome
    }

    private fun evaluateRule(
        rule: String,
        at: ZonedDateTime,
    ): RuleOutcome {
        if (rule.equals("24/7", ignoreCase = true)) return RuleOutcome.Applies(true)

        val segments = rule.split(Regex("\\s+"), limit = 2)
        if (segments.size != 2) return RuleOutcome.Unparseable
        val days = parseDays(segments[0]) ?: return RuleOutcome.Unparseable
        val timeSpec = segments[1]

        val today = at.dayOfWeek
        val yesterday = today.minus(1)
        val todayInDays = today in days
        val yesterdayInDays = yesterday in days

        if (timeSpec.equals("off", ignoreCase = true) || timeSpec.equals("closed", ignoreCase = true)) {
            return if (todayInDays) RuleOutcome.Applies(false) else RuleOutcome.DoesNotApply
        }

        val ranges = parseTimeRanges(timeSpec) ?: return RuleOutcome.Unparseable
        // A rule only reaches into "yesterday" when one of its ranges actually
        // wraps past midnight; otherwise a day-set that excludes today never applies.
        val continuesFromYesterday = yesterdayInDays && ranges.any(TimeRange::wraps)
        if (!todayInDays && !continuesFromYesterday) return RuleOutcome.DoesNotApply

        val time = at.toLocalTime()
        val open = ranges.any { range -> range.containsAt(time, todayInDays, yesterdayInDays) }
        return RuleOutcome.Applies(open)
    }

    private fun parseDays(spec: String): Set<DayOfWeek>? {
        val groups = spec.split(',').map(String::trim).filter(String::isNotEmpty)
        if (groups.isEmpty()) return null
        val days = mutableSetOf<DayOfWeek>()
        groups.forEach { group ->
            val bounds = group.split('-')
            when (bounds.size) {
                1 -> days += DAY_CODES[bounds[0]] ?: return null
                2 -> {
                    val start = DAY_CODES[bounds[0]] ?: return null
                    val end = DAY_CODES[bounds[1]] ?: return null
                    days += daysBetween(start, end)
                }
                else -> return null
            }
        }
        return days
    }

    private fun daysBetween(
        start: DayOfWeek,
        end: DayOfWeek,
    ): Set<DayOfWeek> {
        val days = mutableSetOf<DayOfWeek>()
        var current = start
        while (true) {
            days += current
            if (current == end) break
            current = current.plus(1)
        }
        return days
    }

    private fun parseTimeRanges(spec: String): List<TimeRange>? {
        val tokens = spec.split(',').map(String::trim).filter(String::isNotEmpty)
        if (tokens.isEmpty()) return null
        return tokens.map { token ->
            val bounds = token.split('-')
            if (bounds.size != 2) return null
            val start = parseTime(bounds[0]) ?: return null
            val end = parseTime(bounds[1]) ?: return null
            TimeRange(start, end, wraps = end <= start)
        }
    }

    private fun parseTime(raw: String): LocalTime? {
        val match = TIME_PATTERN.matchEntire(raw.trim()) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        return when {
            hour in 0..23 && minute in 0..59 -> LocalTime.of(hour, minute)
            hour == 24 && minute == 0 -> LocalTime.MIDNIGHT
            else -> null
        }
    }

    private data class TimeRange(
        val start: LocalTime,
        val end: LocalTime,
        val wraps: Boolean,
    ) {
        fun containsAt(
            time: LocalTime,
            dayInDays: Boolean,
            previousDayInDays: Boolean,
        ): Boolean {
            if (!wraps) return dayInDays && time >= start && time < end
            return (dayInDays && time >= start) || (previousDayInDays && time < end)
        }
    }

    private val TIME_PATTERN = Regex("([0-9]{1,2}):([0-9]{2})")
    private val DAY_CODES =
        mapOf(
            "Mo" to DayOfWeek.MONDAY,
            "Tu" to DayOfWeek.TUESDAY,
            "We" to DayOfWeek.WEDNESDAY,
            "Th" to DayOfWeek.THURSDAY,
            "Fr" to DayOfWeek.FRIDAY,
            "Sa" to DayOfWeek.SATURDAY,
            "Su" to DayOfWeek.SUNDAY,
        )
}
