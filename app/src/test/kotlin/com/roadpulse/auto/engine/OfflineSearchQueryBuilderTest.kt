package com.roadpulse.auto.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineSearchQueryBuilderTest {
    @Test
    fun `a single word gets a prefix wildcard`() {
        assertEquals("Haupt*", OfflineSearchQueryBuilder.buildFtsQuery("Haupt"))
    }

    @Test
    fun `multiple words each get their own prefix wildcard`() {
        assertEquals("Bremen* Hauptbahnhof*", OfflineSearchQueryBuilder.buildFtsQuery("Bremen Hauptbahnhof"))
    }

    @Test
    fun `german umlauts are preserved`() {
        assertEquals("bäcker*", OfflineSearchQueryBuilder.buildFtsQuery("bäcker"))
    }

    @Test
    fun `blank query returns null`() {
        assertNull(OfflineSearchQueryBuilder.buildFtsQuery("   "))
        assertNull(OfflineSearchQueryBuilder.buildFtsQuery(""))
    }

    @Test
    fun `punctuation and fts syntax characters are stripped, not passed through`() {
        // A literal quote or FTS operator must never reach the query - it becomes a plain term.
        assertEquals("DROP* TABLE*", OfflineSearchQueryBuilder.buildFtsQuery("\"; DROP TABLE"))
        assertEquals("O* Brien*", OfflineSearchQueryBuilder.buildFtsQuery("O'Brien"))
    }

    @Test
    fun `extra whitespace between words collapses cleanly`() {
        assertEquals("Foo* Bar*", OfflineSearchQueryBuilder.buildFtsQuery("  Foo   Bar  "))
    }
}
