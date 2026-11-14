package com.nexus.music.diagnostics

import kotlin.math.ceil

/** Pure E1 report formatter. Failed and empty outcomes never enter latency percentiles. */
internal object ProviderLabSurveyReportFormatter {
    data class Record(
        val operation: String,
        val timestampEpochMs: Long,
        val transport: String,
        val coldWarm: String,
        val pageIndex: Int,
        val elapsedMs: Long,
        val outcome: String,
        val failureClass: String?,
        val query: String
    )

    fun format(
        records: List<Record>,
        appVersion: String = "unknown",
        deviceModel: String = "unknown",
        apiLevel: Int = 0
    ): String = buildString {
        appendLine("Nexus Provider Lab · E1 latency survey")
        appendLine("App: ${appVersion.take(40)}")
        appendLine("Device: ${deviceModel.take(60)} · API $apiLevel")
        appendLine("Provider: NewPipe")
        appendLine("Samples: ${records.size}")
        listOf("SEARCH", "RESOLVE+PROBE").forEach { operation ->
            val samples = records.filter { it.operation == operation }
            val healthy = samples.filter { it.outcome == "OK" }.map { it.elapsedMs.coerceAtLeast(0L) }.sorted()
            val failures = samples.count { it.outcome == "FAIL" }
            val empty = samples.count { it.outcome == "EMPTY" }
            appendLine(
                "$operation · n=${samples.size} · success=${healthy.size} · failures=$failures · empty=$empty · " +
                    "p50=${percentile(healthy, 0.50)}ms · p90=${percentile(healthy, 0.90)}ms · " +
                    "p95=${percentile(healthy, 0.95)}ms"
            )
        }
        appendLine("Columns: epochMs | operation | transport | cold/warm | page | elapsedMs | outcome | failureClass | query")
        records.forEach { sample ->
            val safeQuery = sample.query.replace(Regex("[\\r\\n|]"), " ").take(70)
            appendLine(
                "${sample.timestampEpochMs} | ${sample.operation} | ${sample.transport.take(70)} | " +
                    "${sample.coldWarm} | ${sample.pageIndex} | ${sample.elapsedMs.coerceAtLeast(0L)} | " +
                    "${sample.outcome} | ${sample.failureClass?.take(50) ?: "none"} | $safeQuery"
            )
        }
    }.trimEnd()

    private fun percentile(sorted: List<Long>, fraction: Double): Long? {
        if (sorted.isEmpty()) return null
        val rank = ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }
}
