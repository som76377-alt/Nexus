package com.nexus.music.diagnostics

/** Stable, compact plain-text report intended for support and reproducible comparisons. */
internal object ProviderLabReportFormatter {
    data class Cell(
        val provider: String,
        val videoId: String,
        val networkMode: String,
        val ok: Boolean,
        val error: String?,
        val httpCode: Int?,
        val elapsedMs: Long
    )

    data class ProviderHealthRow(
        val provider: String,
        val operation: String,
        val consecutiveFailures: Int,
        val lastSuccessEpochMs: Long,
        val lastError: String?,
        val cooldownRemainingMs: Long
    )

    fun format(
        appVersion: String,
        flavor: String,
        network: String,
        cacheBytes: Long,
        configVersion: String,
        providerHealth: List<ProviderHealthRow>,
        cells: List<Cell>
    ): String = buildString {
        appendLine("Nexus Provider Lab")
        appendLine("App: ${appVersion.take(40)}")
        appendLine("Flavor: ${flavor.take(24)}")
        appendLine("Network: ${network.take(100)}")
        appendLine("Cache: ${cacheBytes.coerceAtLeast(0)} bytes")
        appendLine("Config: ${configVersion.take(100)}")
        providerHealth.take(6).forEach { health ->
            appendLine(
                "HEALTH ${health.provider} ${health.operation} failures=${health.consecutiveFailures.coerceAtLeast(0)} " +
                    "lastSuccess=${health.lastSuccessEpochMs.coerceAtLeast(0)} " +
                    "lastError=${health.lastError?.take(24) ?: "none"} " +
                    "cooldownMs=${health.cooldownRemainingMs.coerceAtLeast(0)}"
            )
        }
        cells.take(15).forEach { cell ->
            val state = if (cell.ok) "OK" else "FAIL"
            val error = cell.error?.replace(Regex("[\\r\\n|]"), " ")?.take(90).orEmpty()
            appendLine(
                "$state ${cell.provider} ${cell.videoId} ${cell.networkMode} · " +
                    "exception=${error.ifBlank { "none" }} · " +
                    "HTTP=${cell.httpCode?.toString() ?: "n/a"} · ${cell.elapsedMs}ms"
            )
        }
        if (cells.size < 15) appendLine("Pending: ${15 - cells.size} checks")
        if (cells.size > 15) appendLine("Omitted: ${cells.size - 15} additional checks")
    }.lineSequence().take(40).joinToString("\n")
}
