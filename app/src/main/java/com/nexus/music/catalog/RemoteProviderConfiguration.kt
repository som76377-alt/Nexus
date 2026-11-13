package com.nexus.music.catalog

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class ProviderSetting(val id: StreamProvider, val enabled: Boolean)

internal data class ProviderConfiguration(
    val version: Int,
    val providers: List<ProviderSetting>
) {
    val enabledOrder: List<StreamProvider>
        get() = providers.filter(ProviderSetting::enabled).map(ProviderSetting::id)
}

internal object ProviderConfigurationParser {
    fun parse(json: String): ProviderConfiguration {
        val root = JSONObject(json)
        val version = root.opt("version")
        require(version is Int && version > 0) { "Provider config version must be a positive integer." }
        val entries = root.opt("providers")
        require(entries is JSONArray && entries.length() in 1..3) {
            "Provider config must contain one to three providers."
        }

        val providers = buildList {
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index)
                    ?: throw IllegalArgumentException("Provider entries must be objects.")
                val idValue = entry.opt("id")
                val enabledValue = entry.opt("enabled")
                require(idValue is String && enabledValue is Boolean) {
                    "Each provider needs a string id and boolean enabled value."
                }
                val id = StreamProvider.entries.firstOrNull { it.key == idValue }
                    ?: throw IllegalArgumentException("Unknown provider id.")
                add(ProviderSetting(id, enabledValue))
            }
        }
        require(providers.map(ProviderSetting::id).distinct().size == providers.size) {
            "Provider config contains duplicate provider ids."
        }
        require(providers.any(ProviderSetting::enabled)) { "At least one provider must be enabled." }
        return ProviderConfiguration(version, providers)
    }
}

internal sealed interface ProviderConfigResponse {
    data class Updated(val body: String, val etag: String?) : ProviderConfigResponse
    data object NotModified : ProviderConfigResponse
}

internal class RemoteProviderConfigFetcher(
    private val client: OkHttpClient = defaultClient(),
    private val endpoint: String = CONFIG_URL
) {
    fun fetch(etag: String?): ProviderConfigResponse {
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .header("User-Agent", "Nexus/0.1 (Android; provider configuration)")
            .apply { if (!etag.isNullOrBlank()) header("If-None-Match", etag) }
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 304) return ProviderConfigResponse.NotModified
            if (!response.isSuccessful) throw IOException("Provider config returned HTTP ${response.code}.")
            val body = response.body ?: throw IOException("Provider config response was empty.")
            if (body.contentLength() > MAX_CONFIG_BYTES) throw IOException("Provider config exceeded its size limit.")
            val source = body.source()
            if (source.request(MAX_CONFIG_BYTES + 1L)) throw IOException("Provider config exceeded its size limit.")
            val content = source.readUtf8()
            return ProviderConfigResponse.Updated(content, response.header("ETag"))
        }
    }

    private companion object {
        const val CONFIG_URL =
            "https://raw.githubusercontent.com/som76377-alt/Nexus/main/config/providers.json"
        const val MAX_CONFIG_BYTES = 16_384L

        fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }
}

/** Last-good remote data is preferred; the bundled allowlisted config is the offline fallback. */
internal class RemoteProviderConfiguration(
    context: Context,
    private val fetcher: RemoteProviderConfigFetcher = RemoteProviderConfigFetcher()
) {
    private val appContext = context.applicationContext
    private val preferences: SharedPreferences =
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun current(): ProviderConfiguration {
        val saved = preferences.getString(KEY_LAST_GOOD, null)
        if (!saved.isNullOrBlank()) {
            runCatching { ProviderConfigurationParser.parse(saved) }.getOrNull()?.let { return it }
        }
        val bundled = appContext.assets.open(BUNDLED_CONFIG).bufferedReader().use { it.readText() }
        return ProviderConfigurationParser.parse(bundled)
    }

    suspend fun refresh(): ProviderConfiguration = withContext(Dispatchers.IO) {
        try {
            when (val result = fetcher.fetch(preferences.getString(KEY_ETAG, null))) {
                ProviderConfigResponse.NotModified -> current()
                is ProviderConfigResponse.Updated -> {
                    val parsed = ProviderConfigurationParser.parse(result.body)
                    preferences.edit()
                        .putString(KEY_LAST_GOOD, result.body)
                        .putString(KEY_ETAG, result.etag)
                        .apply()
                    parsed
                }
            }
        } catch (_: Exception) {
            current()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "nexus_remote_provider_config"
        const val KEY_LAST_GOOD = "last_good_json"
        const val KEY_ETAG = "etag"
        const val BUNDLED_CONFIG = "providers.json"
    }
}
