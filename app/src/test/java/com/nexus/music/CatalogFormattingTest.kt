package com.nexus.music

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogFormattingTest {
    @Test fun formatsShortAndLongDurations() {
        assertEquals("3:07", CatalogFormatting.duration(187))
        assertEquals("1:02:03", CatalogFormatting.duration(3723))
    }

    @Test fun formatsViewCountsCompactly() {
        assertEquals("842 views", CatalogFormatting.views(842))
        assertEquals("12K views", CatalogFormatting.views(12_900))
        assertEquals("3M views", CatalogFormatting.views(3_400_000))
    }
}
