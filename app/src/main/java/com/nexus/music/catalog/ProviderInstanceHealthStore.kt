package com.nexus.music.catalog

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

data class ProviderInstanceHealth(
    val consecutiveFailures: Int = 0,
    val cooldownUntilEpochMs: Long = 0L
)

interface ProviderInstanceHealthStore {
    fun get(provider: StreamProvider, endpoint: String): ProviderInstanceHealth
    fun recordSuccess(provider: StreamProvider, endpoint: String)
    fun recordFailure(provider: StreamProvider, endpoint: String, atEpochMs: Long, cooldownUntilEpochMs: Long)
}

/** In-memory fallback for isolated adapters and tests. Production injects the persistent store. */
class InMemoryProviderInstanceHealthStore : ProviderInstanceHealthStore {
    private val values = mutableMapOf<String, ProviderInstanceHealth>()

    @Synchronized
    override fun get(provider: StreamProvider, endpoint: String): ProviderInstanceHealth =
        values[key(provider, endpoint)] ?: ProviderInstanceHealth()

    @Synchronized
    override fun recordSuccess(provider: StreamProvider, endpoint: String) {
        values.remove(key(provider, endpoint))
    }

    @Synchronized
    override fun recordFailure(provider: StreamProvider, endpoint: String, atEpochMs: Long, cooldownUntilEpochMs: Long) {
        val key = key(provider, endpoint)
        val current = values[key] ?: ProviderInstanceHealth()
        values[key] = ProviderInstanceHealth(
            consecutiveFailures = current.consecutiveFailures + 1,
            cooldownUntilEpochMs = cooldownUntilEpochMs.coerceAtLeast(atEpochMs)
        )
    }

    private fun key(provider: StreamProvider, endpoint: String) = "${provider.key}:$endpoint"
}

/** Persists per-instance circuit state without storing raw URLs in preference keys. */
class SharedPreferencesProviderInstanceHealthStore(context: Context) : ProviderInstanceHealthStore {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun get(provider: StreamProvider, endpoint: String): ProviderInstanceHealth {
        val key = key(provider, endpoint)
        return ProviderInstanceHealth(
            consecutiveFailures = preferences.getInt("$key.failures", 0).coerceAtLeast(0),
            cooldownUntilEpochMs = preferences.getLong("$key.cooldown", 0L).coerceAtLeast(0L)
        )
    }

    @Synchronized
    override fun recordSuccess(provider: StreamProvider, endpoint: String) {
        val key = key(provider, endpoint)
        preferences.edit().remove("$key.failures").remove("$key.cooldown").apply()
    }

    @Synchronized
    override fun recordFailure(provider: StreamProvider, endpoint: String, atEpochMs: Long, cooldownUntilEpochMs: Long) {
        val key = key(provider, endpoint)
        val failures = (preferences.getInt("$key.failures", 0) + 1).coerceAtLeast(1)
        preferences.edit()
            .putInt("$key.failures", failures)
            .putLong("$key.cooldown", cooldownUntilEpochMs.coerceAtLeast(atEpochMs))
            .apply()
    }

    private fun key(provider: StreamProvider, endpoint: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(endpoint.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
        return "${provider.key}.$digest"
    }

    private companion object {
        const val PREFERENCES_NAME = "nexus_provider_instance_health"
    }
}
