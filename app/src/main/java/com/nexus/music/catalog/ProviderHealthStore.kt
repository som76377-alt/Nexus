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
    fun get(
        provider: StreamProvider,
        operation: ProviderOperation = ProviderOperation.RESOLVE
    ): ProviderHealth

    fun recordSuccess(
        provider: StreamProvider,
        atEpochMs: Long,
        operation: ProviderOperation = ProviderOperation.RESOLVE
    )

    fun recordFailure(
        provider: StreamProvider,
        errorClass: StreamErrorClass,
        atEpochMs: Long,
        cooldownUntilEpochMs: Long,
        operation: ProviderOperation = ProviderOperation.RESOLVE
    )

    fun clear(
        provider: StreamProvider,
        operation: ProviderOperation = ProviderOperation.RESOLVE
    )
}

/** In-memory health state for diagnostics and isolated tests; never persists into production prefs. */
class InMemoryProviderHealthStore : ProviderHealthStore {
    private val values = mutableMapOf<Pair<StreamProvider, ProviderOperation>, ProviderHealth>()

    @Synchronized
    override fun get(provider: StreamProvider, operation: ProviderOperation): ProviderHealth =
        values[provider to operation] ?: ProviderHealth()

    @Synchronized
    override fun recordSuccess(provider: StreamProvider, atEpochMs: Long, operation: ProviderOperation) {
        values[provider to operation] = ProviderHealth(lastSuccessEpochMs = atEpochMs.coerceAtLeast(0L))
    }

    @Synchronized
    override fun recordFailure(
        provider: StreamProvider,
        errorClass: StreamErrorClass,
        atEpochMs: Long,
        cooldownUntilEpochMs: Long,
        operation: ProviderOperation
    ) {
        val key = provider to operation
        val failures = (get(provider, operation).consecutiveFailures + 1).coerceAtLeast(1)
        values[key] = ProviderHealth(
            consecutiveFailures = failures,
            lastSuccessEpochMs = get(provider, operation).lastSuccessEpochMs,
            cooldownUntilEpochMs = cooldownUntilEpochMs.coerceAtLeast(atEpochMs),
            lastErrorClass = errorClass
        )
    }

    @Synchronized
    override fun clear(provider: StreamProvider, operation: ProviderOperation) {
        values.remove(provider to operation)
    }
}

/** SharedPreferences-backed health history survives process death and app restarts. */
class SharedPreferencesProviderHealthStore(context: Context) : ProviderHealthStore {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun prefix(provider: StreamProvider, operation: ProviderOperation): String =
        "${provider.key}.${operation.key}"

    @Synchronized
    override fun get(provider: StreamProvider, operation: ProviderOperation): ProviderHealth {
        val key = prefix(provider, operation)
        val errorName = preferences.getString("$key.error", null)
        return ProviderHealth(
            consecutiveFailures = preferences.getInt("$key.failures", 0).coerceAtLeast(0),
            lastSuccessEpochMs = preferences.getLong("$key.success", 0L).coerceAtLeast(0L),
            cooldownUntilEpochMs = preferences.getLong("$key.cooldown", 0L).coerceAtLeast(0L),
            lastErrorClass = errorName?.let { runCatching { StreamErrorClass.valueOf(it) }.getOrNull() }
        )
    }

    @Synchronized
    override fun recordSuccess(provider: StreamProvider, atEpochMs: Long, operation: ProviderOperation) {
        val key = prefix(provider, operation)
        preferences.edit()
            .putInt("$key.failures", 0)
            .putLong("$key.success", atEpochMs.coerceAtLeast(0L))
            .putLong("$key.cooldown", 0L)
            .remove("$key.error")
            .apply()
    }

    @Synchronized
    override fun recordFailure(
        provider: StreamProvider,
        errorClass: StreamErrorClass,
        atEpochMs: Long,
        cooldownUntilEpochMs: Long,
        operation: ProviderOperation
    ) {
        val key = prefix(provider, operation)
        val failures = (preferences.getInt("$key.failures", 0) + 1).coerceAtLeast(1)
        preferences.edit()
            .putInt("$key.failures", failures)
            .putLong("$key.cooldown", cooldownUntilEpochMs.coerceAtLeast(atEpochMs))
            .putString("$key.error", errorClass.name)
            .apply()
    }

    @Synchronized
    override fun clear(provider: StreamProvider, operation: ProviderOperation) {
        val key = prefix(provider, operation)
        preferences.edit().remove("$key.failures").remove("$key.success")
            .remove("$key.cooldown").remove("$key.error").apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "nexus_stream_provider_health"
    }
}
