package com.roadpulse.auto.engine

/**
 * Pure FTS4 query construction for [OfflineSearchEngine] - separated out so it can be
 * unit-tested without a real `android.database.sqlite.SQLiteDatabase` (this project has no
 * Robolectric; JVM unit tests can't touch Android framework classes directly, matching the same
 * split used for [com.roadpulse.auto.voice.VoiceGuidancePlanner]).
 *
 * Strips everything but Unicode letters/digits per token (keeping German umlauts etc.) before
 * appending a `*` prefix-match wildcard, so raw user input can never be interpreted as SQLite
 * FTS4 query syntax (quotes, `NEAR`/`AND`/`OR`/`NOT` keywords, column filters) - a query like
 * `"; DROP TABLE` becomes a harmless plain-word search, not a syntax error or an injection.
 */
object OfflineSearchQueryBuilder {
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** Returns a SQLite FTS4 MATCH expression, or null if [rawQuery] has no searchable terms. */
    fun buildFtsQuery(rawQuery: String): String? {
        val terms =
            rawQuery
                .split(NON_WORD)
                .filter(String::isNotBlank)
        if (terms.isEmpty()) return null
        return terms.joinToString(" ") { "$it*" }
    }
}
