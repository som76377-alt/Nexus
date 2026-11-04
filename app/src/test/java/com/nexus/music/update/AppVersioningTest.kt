package com.nexus.music.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersioningTest {
    @Test
    fun mapsReleaseTagsToMonotonicAndroidVersionCodes() {
        assertEquals(1_001, AppVersioning.versionCodeFromTag("v0.1.1"))
        assertEquals(2_000, AppVersioning.versionCodeFromTag("0.2.0"))
        assertEquals(1_000_001, AppVersioning.versionCodeFromTag("v1.0.1"))
    }

    @Test
    fun recognizesOnlyStrictStableSemanticVersionTags() {
        assertNull(AppVersioning.versionCodeFromTag("latest"))
        assertNull(AppVersioning.versionCodeFromTag("v1.2"))
        assertNull(AppVersioning.versionCodeFromTag("v1.2.3-beta"))
        assertNull(AppVersioning.versionCodeFromTag("v0.1000.0"))
        assertNull(AppVersioning.versionCodeFromTag("v2101.0.0"))
        assertNull(AppVersioning.versionCodeFromTag("v999999999999999999.0.0"))
        assertNull(AppVersioning.versionCodeFromTag("v01.2.3"))
        assertNull(AppVersioning.versionCodeFromTag("v1.02.3"))
        assertNull(AppVersioning.versionCodeFromTag("v1.2.03"))
        assertEquals(2_100_000_000, AppVersioning.versionCodeFromTag("v2100.0.0"))
        assertNull(AppVersioning.versionCodeFromTag("v2100.0.1"))
    }

    @Test
    fun comparesInstalledAndAvailableVersions() {
        assertTrue(AppVersioning.isNewer("v0.1.2", "0.1.1"))
        assertFalse(AppVersioning.isNewer("v0.1.1", "0.1.1"))
        assertFalse(AppVersioning.isNewer("v0.1.0", "0.1.1"))
    }

    @Test
    fun normalizesTagWithoutChangingVersion() {
        assertEquals("0.1.2", AppVersioning.normalize("v0.1.2"))
        assertEquals("0.1.2", AppVersioning.normalize("0.1.2"))
        assertNull(AppVersioning.normalize("stable"))
    }
}
