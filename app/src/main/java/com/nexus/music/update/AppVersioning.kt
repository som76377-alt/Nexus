package com.nexus.music.update

/**
 * Nexus release tags use plain semantic versions: vMAJOR.MINOR.PATCH.
 * The numeric code leaves room for 999 minor and patch releases per major version.
 */
internal object AppVersioning {
    private val versionPattern = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$")

    fun versionCodeFromTag(tag: String): Int? {
        val match = versionPattern.matchEntire(tag.trim()) ?: return null
        val major = match.groupValues[1].toLongOrNull() ?: return null
        val minor = match.groupValues[2].toLongOrNull() ?: return null
        val patch = match.groupValues[3].toLongOrNull() ?: return null
        if (minor > 999L || patch > 999L) return null

        val code = major * 1_000_000L + minor * 1_000L + patch
        return code.takeIf { it in 1L..2_100_000_000L }?.toInt()
    }

    fun isNewer(candidate: String, installed: String): Boolean {
        val candidateCode = versionCodeFromTag(candidate) ?: return false
        val installedCode = versionCodeFromTag(installed) ?: return false
        return candidateCode > installedCode
    }

    fun normalize(tag: String): String? {
        val code = versionCodeFromTag(tag) ?: return null
        val normalized = tag.trim().removePrefix("v")
        return normalized.takeIf { versionCodeFromTag(it) == code }
    }
}
