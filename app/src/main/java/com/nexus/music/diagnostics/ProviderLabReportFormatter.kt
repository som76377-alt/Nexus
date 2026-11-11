package com.nexus.music.diagnostics

/** Stable, compact plain-text report intended for support and reproducible comparisons. */
internal object ProviderLabReportFormatter {
    data class Cell(
        val videoId: String,
        val networkMode: String,
        val ok: Boolean,
        val error: String?,
        val httpCode: Int?,
        val elapsedMs: Long
    )

    fun format(appVersion: String, network: String, cells: List<Cell>): String = buildString {
        appendLine("Nexus Provider Lab")
        appendLine("App: ${appVersion.take(40)}")
        appendLine("Network: ${network.take(100)}")
        appendLine("Provider: NewPipe Extractor")
        cells.take(15).forEach { cell ->
            val state = if (cell.ok) "OK" else "FAIL"
            val error = cell.error?.replace(Regex("[\\r\\n|]"), " ")?.take(90).orEmpty()
            appendLine(
                "$state ${cell.videoId} ${cell.networkMode} · " +
                    "exception=${error.ifBlank { "none" }} · " +
                    "HTTP=${cell.httpCode?.toString() ?: "n/a"} · ${cell.elapsedMs}ms"
            )
        }
        if (cells.size < 15) appendLine("Pending: ${15 - cells.size} checks")
    }.lineSequence().take(40).joinToString("\n")
}
