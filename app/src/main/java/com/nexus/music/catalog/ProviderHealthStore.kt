package com.nexus.music.catalog

import android.content.Context
import android.content.SharedPreferences

data class ProviderHealth(
    val consecutiveFailures: Int = 0,
    val lastSuccessEpochMs: Long = 0L,
    val cooldownUntilEpochMs: Long = 0L,
    val lastErrorClass: StreamErrorClass? = null
)

interface ProviderHealthStore {
    fun get(provider: StreamProvider): ProviderHealth
    fun recordSuccess(provider: StreamProvider, atEpochMs: Long)
    fun recordFailure(provider: StreamProvider, errorClass: StreamErrorClass, atEpochMs: Long, cooldownUntilEpochMs: Long)
    fun clear(provider: StreamProvider)
}

/** Isolated health state for diagnostics; Provider Lab must never mutate production circuits. */
class InMemoryProviderHealthStore : ProviderHealthStore {
    private val values = mutableMapOf<StreamProvider, ProviderHealth>()

    @Synchronized
    override fun get(provider: StreamProvider): ProviderHealth = values[provider] ?: ProviderHealth()

    @Synchronized
    override fun recordSuccess(provider: StreamProvider, atEpochMs: Long) {
        values[provider] = ProviderHealth(lastSuccessEpochMs = atEpochMs)
    }

    @Synchronized
    override fun recordFailure(
        provider: StreamProvider,
        errorClass: StreamErrorClass,
        atEpochMs: Long,
        cooldownUntilEpochMs: Long
    ) {
        val current = get(provider)
        values[provider] = current.copy(
            consecutiveFailures = current.consecutiveFailures + 1,
            cooldownUntilEpochMs = cooldownUntilEpochMs.coerceAtLeast(atEpochMs),
            lastErrorClass = errorClass
        )
    }

    @Synchronized
    override fun clear(provider: StreamProvider) {
        values.remove(provider)
    }
}

/** SharedPreferences-backed health history survives process death and app restarts. */
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
            lastErrorClass = errorName?.let { runCatching { StreamErrorClass.valueOf(it) }.getOrNull() }
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
        preferences.edit().remove("$key.failures").remove("$key.success")
            .remove("$key.cooldown").remove("$key.error").apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "nexus_stream_provider_health"
    }
}
