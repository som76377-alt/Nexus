package com.nexus.music.catalog

import android.content.Context
import android.content.SharedPreferences

/** Persisted provider history used to avoid repeatedly selecting a recently failing endpoint. */
data class ProviderHealth(
    val consecutiveFailures: Int = 0,
    val lastSuccessEpochMs: Long = 0L,
    val cooldownUntilEpochMs: Long = 0L,
    val lastErrorClass: StreamErrorClass? = null
)

interface ProviderHealthStore {
    fun get(provider: StreamProvider): ProviderHealth
    fun recordSuccess(provider: StreamProvider, atEpochMs: Long)
    fun recordFailure(
        provider: StreamProvider,
        errorClass: StreamErrorClass,
        atEpochMs: Long,
        cooldownUntilEpochMs: Long
    )
    fun clear(provider: StreamProvider)
}

/** SharedPreferences-backed process-persistent health store. Calls are serialized per instance. */
class SharedPreferencesProviderHealthStore(context: Context) : ProviderHealthStore {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun get(provider: StreamProvider): ProviderHealth {
        val key = provider.key
        val errorName = preferences.getString("$key.error", null)
        return ProviderHealth(
            consecutiveFailures = preferences.getInt("$key.failures", 0).coerceAtLeast(0),
            lastSuccessEpochMs = preferences.getLong("$key.success", 0L).coerceAtLeast(0L),
            cooldownUntilEpochMs = preferences.getLong("$key.cooldown", 0L).coerceAtLeast(0L),
            lastErrorClass = errorName?.let { name ->
                runCatching { StreamErrorClass.valueOf(name) }.getOrNull()
            }
        )
    }

    @Synchronized
    override fun recordSuccess(provider: StreamProvider, atEpochMs: Long) {
        preferences.edit()
            .putInt("${provider.key}.failures", 0)
            .putLong("${provider.key}.success", atEpochMs.coerceAtLeast(0L))
            .putLong("${provider.key}.cooldown", 0L)
            .remove("${provider.key}.error")
            .apply()
    }

    @Synchronized
    override fun recordFailure(
        provider: StreamProvider,
        errorClass: StreamErrorClass,
        atEpochMs: Long,
        cooldownUntilEpochMs: Long
    ) {
        val key = provider.key
        val failures = (preferences.getInt("$key.failures", 0) + 1).coerceAtLeast(1)
        preferences.edit()
            .putInt("$key.failures", failures)
            .putLong("$key.cooldown", cooldownUntilEpochMs.coerceAtLeast(atEpochMs))
            .putString("$key.error", errorClass.name)
            .apply()
    }

    @Synchronized
    override fun clear(provider: StreamProvider) {
        val key = provider.key
        preferences.edit()
            .remove("$key.failures")
            .remove("$key.success")
            .remove("$key.cooldown")
            .remove("$key.error")
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "nexus_stream_provider_health"
    }
}
