package com.nexus.music.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Verifies a resolved audio URL with a one-byte ranged GET before it reaches Media3.
 * The address family selected here is carried with the stream for the playback data source.
 */
class StreamProbe(
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .build(),
    private val systemDns: Dns = Dns.SYSTEM
) {
    suspend fun probe(audio: ResolvedAudio): ResolvedAudio = withContext(Dispatchers.IO) {
        val url = audio.audioUrl.toHttpUrlOrNull()
            ?: throw IOException("The resolved audio URL is invalid.")
        val resolvedAddresses = systemDns.lookup(url.host)
        val family = resolvedAddresses.firstNotNullOfOrNull { address ->
            when (address) {
                is Inet4Address -> StreamIpFamily.IPV4
                is Inet6Address -> StreamIpFamily.IPV6
                else -> null
            }
        } ?: throw UnknownHostException("No IPv4 or IPv6 address is available for the audio host.")

        val familyDns = Dns { host ->
            val matching = systemDns.lookup(host).filter { address ->
                when (family) {
                    StreamIpFamily.IPV4 -> address is Inet4Address
                    StreamIpFamily.IPV6 -> address is Inet6Address
                }
            }
            if (matching.isEmpty()) {
                throw UnknownHostException("No $family address is available for $host.")
            }
            matching
        }
        val client = baseClient.newBuilder().dns(familyDns).build()
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .apply { audio.requestHeaders.forEach { (name, value) -> header(name, value) } }
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code != 200 && response.code != 206) {
                throw ProviderHttpException(response.code, "Stream probe")
            }
            if (response.code == 206 &&
                !response.header("Content-Range").orEmpty().startsWith("bytes 0-0/")
            ) {
                throw IOException("The stream probe returned an invalid byte range.")
            }
            val firstByte = response.body?.byteStream()?.use { it.read() } ?: -1
            if (firstByte < 0) throw IOException("The resolved audio stream returned no data.")
        }
        audio.copy(ipFamily = family)
    }
}
