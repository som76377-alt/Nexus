package com.nexus.music.diagnostics

import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderLabSurveyReportFormatterTest {
    @Test
    fun reportsPercentilesAndKeepsFailuresOutOfLatencyDistribution() {
        val records = listOf(10L, 20L, 30L, 40L, 50L).mapIndexed { index, elapsed ->
            ProviderLabSurveyReportFormatter.Record(
                operation = "SEARCH",
                timestampEpochMs = index.toLong(),
                transport = "Wi-Fi",
                coldWarm = if (index == 0) "first-in-process" else "warm",
                pageIndex = 1,
                elapsedMs = elapsed,
                outcome = "OK",
                failureClass = null,
                query = "ambient $index"
            )
        } + ProviderLabSurveyReportFormatter.Record(
            operation = "SEARCH",
            timestampEpochMs = 6L,
            transport = "Wi-Fi",
            coldWarm = "warm",
            pageIndex = 1,
            elapsedMs = 9_999L,
            outcome = "FAIL",
            failureClass = "IOException",
            query = "ambient failure"
        )

        val report = ProviderLabSurveyReportFormatter.format(records)

        assertTrue(report.contains("SEARCH · n=6 · success=5 · failures=1"))
        assertTrue(report.contains("p50=30ms · p90=50ms · p95=50ms"))
        assertTrue(report.contains("IOException"))
        assertTrue(report.contains("first-in-process"))
        assertTrue(report.contains("page | elapsedMs"))
    }

    @Test
    fun emptySearchIsNotCountedAsProviderFailureOrLatencySample() {
        val report = ProviderLabSurveyReportFormatter.format(
            listOf(
                ProviderLabSurveyReportFormatter.Record(
                    "SEARCH", 1L, "cellular", "first-in-process", 1, 25L,
                    "EMPTY", null, "unusual query"
                )
            )
        )
        assertTrue(report.contains("success=0 · failures=0 · empty=1"))
        assertTrue(report.contains("p50=nullms"))
    }
}
