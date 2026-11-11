package com.nexus.music.catalog

import android.content.Context
import com.nexus.music.BuildConfig

/**
 * Owns the process-wide catalogue instance used by UI search, normal playback and room playback.
 * Provider endpoints and ordering are build-configurable; no public instance is assumed permanent.
 */
object NexusCatalog {
    @Volatile
    private var sharedRepository: YoutubeCatalogRepository? = null

    fun repository(context: Context): YoutubeCatalogRepository =
        sharedRepository ?: synchronized(this) {
            sharedRepository ?: createRepository(context.applicationContext).also {
                sharedRepository = it
            }
        }

    private fun createRepository(context: Context): YoutubeCatalogRepository {
        val adapters = buildList<StreamProviderAdapter> {
            add(NewPipeStreamProviderAdapter())
            parseEndpoints(BuildConfig.PIPED_API_ENDPOINTS).takeIf(List<String>::isNotEmpty)?.let {
                add(PipedStreamProviderAdapter(it))
            }
            parseEndpoints(BuildConfig.INVIDIOUS_API_ENDPOINTS).takeIf(List<String>::isNotEmpty)?.let {
                add(InvidiousStreamProviderAdapter(it))
            }
        }
        val order = StreamProvider.parseOrder(BuildConfig.STREAM_PROVIDER_ORDER)
        val healthStore = SharedPreferencesProviderHealthStore(context)
        val resolver = ProviderResolverChain(
            adapters = adapters,
            healthStore = healthStore,
            providerOrder = order
        )
        return YoutubeCatalogRepository(adapter = ResolverChainStreamProviderAdapter(resolver))
    }

    private fun parseEndpoints(value: String): List<String> =
        value.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
}
