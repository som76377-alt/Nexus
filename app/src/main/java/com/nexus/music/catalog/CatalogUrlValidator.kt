package com.nexus.music.catalog

import java.net.URI

/** Central validation for canonical source URLs, thumbnails, and resolved media URLs. */
internal object CatalogUrlValidator {
    fun isValidHttpsUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    }
}
