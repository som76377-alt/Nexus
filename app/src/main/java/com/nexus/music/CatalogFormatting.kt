package com.nexus.music

internal object CatalogFormatting {
    fun duration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        val remainder = safe % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainder)
        else "%d:%02d".format(minutes, remainder)
    }

    fun views(count: Long): String = when {
        count >= 1_000_000_000 -> "${count / 1_000_000_000}B views"
        count >= 1_000_000 -> "${count / 1_000_000}M views"
        count >= 1_000 -> "${count / 1_000}K views"
        else -> "$count views"
    }
}
