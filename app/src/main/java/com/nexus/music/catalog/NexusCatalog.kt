package com.nexus.music.catalog

import android.content.Context
import com.nexus.music.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns the process-wide catalogue instance used by UI search and playback.
 * Provider instance lists are loaded from persistent cache immediately and refreshed off-main.
 */
object NexusCatalog {
    @Volatile
    private var sharedRepository: YoutubeCatalogRepository? = null

    private val discoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun repository(context: Context): YoutubeCatalogRepository =
        sharedRepository ?: synchronized(this) {
            sharedRepository ?: createRepository(context.applicationContext).also {
                sharedRepository = it
            }
        }

    private fun createRepository(context: Context): YoutubeCatalogRepository {
        val discovery = ProviderInstanceDiscovery(context)
        val configuredPiped = parseEndpoints(BuildConfig.PIPED_API_ENDPOINTS)
        val configuredInvidious = parseEndpoints(BuildConfig.INVIDIOUS_API_ENDPOINTS)

        val piped = PipedStreamProviderAdapter(
            (configuredPiped + discovery.cachedEndpoints(StreamProvider.PIPED)).distinct()
        )
        val invidious = InvidiousStreamProviderAdapter(
            (configuredInvidious + discovery.cachedEndpoints(StreamProvider.INVIDIOUS)).distinct()
        )
        val adapters = listOf(
            NewPipeStreamProviderAdapter(),
            piped,
            invidious
        )
        val remoteConfig = RemoteProviderConfiguration(context)
        val initialOrder = remoteConfig.current().enabledOrder
        val order = initialOrder.ifEmpty { StreamProvider.parseOrder(BuildConfig.STREAM_PROVIDER_ORDER) }
        val resolver = ProviderResolverChain(
            adapters = adapters,
            healthStore = SharedPreferencesProviderHealthStore(context),
            providerOrder = order,
            validateResolvedAudio = StreamProbe()::validate
        )

        // Directory refresh never blocks activity startup or a playback-service call.
        discoveryScope.launch {
            remoteConfig.refresh().let { updated -> resolver.updateProviderOrder(updated.enabledOrder) }
            while (true) {
                kotlinx.coroutines.delay(REMOTE_CONFIG_REFRESH_INTERVAL_MS)
                remoteConfig.refresh().let { updated -> resolver.updateProviderOrder(updated.enabledOrder) }
            }
        }

        discoveryScope.launch {
            discovery.refreshIfStale { provider, discovered ->
                when (provider) {
                    StreamProvider.PIPED -> piped.updateEndpoints((configuredPiped + discovered).distinct())
                    StreamProvider.INVIDIOUS ->
                        invidious.updateEndpoints((configuredInvidious + discovered).distinct())
                    StreamProvider.NEWPIPE -> Unit
                }
            }
        }

        return YoutubeCatalogRepository(adapter = ResolverChainStreamProviderAdapter(resolver))
    }

    private const val REMOTE_CONFIG_REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private fun parseEndpoints(value: String): List<String> =
        value.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
}
