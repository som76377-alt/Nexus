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
        val elapsedMs: Long,
        val detail: String? = null
    )

    data class ProviderHealthRow(
        val provider: String,
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
        appendLine("Config: ${configVersion.take(180)}")
        providerHealth.take(3).forEach { health ->
            appendLine(
                "HEALTH ${health.provider} failures=${health.consecutiveFailures.coerceAtLeast(0)} " +
                    "lastSuccess=${health.lastSuccessEpochMs.coerceAtLeast(0)} " +
                    "lastError=${health.lastError?.take(24) ?: "none"} " +
                    "cooldownMs=${health.cooldownRemainingMs.coerceAtLeast(0)}"
            )
        }
        val expectedCells = if (cells.any { it.provider == "yt-dlp" }) 5 else 15
        cells.take(expectedCells).forEach { cell ->
            val state = if (cell.ok) "OK" else "FAIL"
            val error = cell.error?.replace(Regex("[\\r\\n|]"), " ")?.take(900).orEmpty()
            val detail = cell.detail?.replace(Regex("[\\r\\n|]"), " ")?.take(70).orEmpty()
            appendLine(
                "$state ${cell.provider} ${cell.videoId} ${cell.networkMode} · " +
                    "detail=${detail.ifBlank { "n/a" }} · " +
                    "exception=${error.ifBlank { "none" }} · " +
                    "HTTP=${cell.httpCode?.toString() ?: "n/a"} · ${cell.elapsedMs}ms"
            )
        }
        if (cells.size < expectedCells) appendLine("Pending: ${expectedCells - cells.size} checks")
        if (cells.size > expectedCells) appendLine("Omitted: ${cells.size - expectedCells} additional checks")
    }.lineSequence().take(40).joinToString("\n")
}
