package com.nexus.music.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderLabReportFormatterTest {
    @Test
    fun reportIncludesNetworkAndPerCellOutcome() {
        val report = ProviderLabReportFormatter.format(
            "0.1.0-debug",
            "Wi-Fi · IPv4=yes · IPv6=no",
            listOf(
                ProviderLabReportFormatter.Cell("dQw4w9WgXcQ", "default", true, null, 206, 1234),
                ProviderLabReportFormatter.Cell("jfKfPfyJRdk", "IPv6-only", false, "UnknownHostException", null, 45)
            )
        )
        assertTrue(report.contains("App: 0.1.0-debug"))
        assertTrue(report.contains("Wi-Fi"))
        assertTrue(report.contains("OK dQw4w9WgXcQ default"))
        assertTrue(report.contains("FAIL jfKfPfyJRdk IPv6-only"))
        assertTrue(report.contains("HTTP=206"))
        assertTrue(report.contains("UnknownHostException"))
    }

    @Test
    fun reportIsBoundedToFortyLinesAndFifteenCells() {
        val cells = (1..20).map {
            ProviderLabReportFormatter.Cell("dQw4w9WgXcQ", "default", false, "failure", null, it.toLong())
        }
        val lines = ProviderLabReportFormatter.format("test", "offline", cells).lines()
        assertTrue(lines.size <= 40)
        assertEquals(15, lines.count { it.startsWith("FAIL ") || it.startsWith("OK ") })
    }

    @Test
    fun reportSanitizesMultilineErrors() {
        val report = ProviderLabReportFormatter.format(
            "test", "cellular",
            listOf(ProviderLabReportFormatter.Cell("dQw4w9WgXcQ", "IPv4-only", false, "Bad\nHeader|value", 403, 2))
        )
        assertTrue(report.contains("Bad Header value"))
    }
}
