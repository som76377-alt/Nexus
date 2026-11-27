package com.nexus.music.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderLabReportFormatterTest {
    @Test
    fun reportIncludesBuildNetworkCacheConfigHealthAndCellOutcome() {
        val report = ProviderLabReportFormatter.format(
            appVersion = "0.1.0-debug",
            flavor = "main",
            network = "Wi-Fi · IPv4=yes · IPv6=no",
            cacheBytes = 1234L,
            configVersion = "local-v0 · order=newpipe,piped,invidious",
            providerHealth = listOf(
                ProviderLabReportFormatter.ProviderHealthRow("newpipe", "resolve", 2, 1000L, "RATE_LIMITED", 30000L)
            ),
            cells = listOf(
                ProviderLabReportFormatter.Cell("newpipe", "dQw4w9WgXcQ", "default", true, null, 206, 1234),
                ProviderLabReportFormatter.Cell("piped", "jfKfPfyJRdk", "IPv6-only", false, "UnknownHostException", null, 45)
            )
        )
        assertTrue(report.contains("App: 0.1.0-debug"))
        assertTrue(report.contains("Flavor: main"))
        assertTrue(report.contains("Wi-Fi"))
        assertTrue(report.contains("Cache: 1234 bytes"))
        assertTrue(report.contains("Config: local-v0"))
        assertTrue(report.contains("HEALTH newpipe resolve failures=2"))
        assertTrue(report.contains("OK newpipe dQw4w9WgXcQ default"))
        assertTrue(report.contains("FAIL piped jfKfPfyJRdk IPv6-only"))
        assertTrue(report.contains("HTTP=206"))
        assertTrue(report.contains("UnknownHostException"))
    }

    @Test
    fun reportIsBoundedToFortyLinesAndFifteenCells() {
        val cells = (1..20).map {
            ProviderLabReportFormatter.Cell("newpipe", "dQw4w9WgXcQ", "default", false, "failure", null, it.toLong())
        }
        val lines = ProviderLabReportFormatter.format(
            "test", "main", "offline", 0L, "local-v0",
            listOf(
                ProviderLabReportFormatter.ProviderHealthRow("newpipe", "search", 0, 0L, null, 0L),
                ProviderLabReportFormatter.ProviderHealthRow("piped", "resolve", 0, 0L, null, 0L),
                ProviderLabReportFormatter.ProviderHealthRow("invidious", "search", 0, 0L, null, 0L)
            ),
            cells
        ).lines()
        assertTrue(lines.size <= 40)
        assertEquals(15, lines.count { it.startsWith("FAIL ") || it.startsWith("OK ") })
    }

    @Test
    fun reportSanitizesMultilineErrors() {
        val report = ProviderLabReportFormatter.format(
            "test", "main", "cellular", 0L, "local-v0", emptyList(),
            listOf(ProviderLabReportFormatter.Cell("newpipe", "dQw4w9WgXcQ", "IPv4-only", false, "Bad\nHeader|value", 403, 2))
        )
        assertTrue(report.contains("Bad Header value"))
    }
}
