package com.nexus.music.catalog

import android.content.Context
import android.util.Base64

internal data class HomeShelfSpec(
    val id: String,
    val title: String,
    val query: String,
    val scope: YoutubeSearchScope = YoutubeSearchScope.MUSIC_SONGS
)

internal data class HomeShelfSnapshot(
    val fetchedAtEpochMs: Long,
    val pool: List<CatalogTrack>,
    val displayedTracks: List<CatalogTrack>,
    /** URLs ordered oldest-shown first and newest-shown last. */
    val history: List<String>
)

internal class HomeDiscoveryCacheStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "home_discovery_cache_v1",
        Context.MODE_PRIVATE
    )

    fun read(shelfId: String): HomeShelfSnapshot? =
        preferences.getString(shelfId, null)?.let(HomeDiscoveryCacheCodec::decode)

    fun write(shelfId: String, snapshot: HomeShelfSnapshot) {
        preferences.edit().putString(shelfId, HomeDiscoveryCacheCodec.encode(snapshot)).apply()
    }
}

internal object HomeDiscoveryCacheCodec {
    private const val NULL = "~"
    private const val FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    fun encode(snapshot: HomeShelfSnapshot): String = listOf(
        snapshot.fetchedAtEpochMs.toString(),
        encodeTracks(snapshot.pool),
        encodeTracks(snapshot.displayedTracks),
        snapshot.history.joinToString(";") { encodeValue(it) }
    ).joinToString("\n")

    fun decode(value: String): HomeShelfSnapshot? = runCatching {
        val parts = value.split("\n", limit = 4)
        require(parts.size == 4)
        HomeShelfSnapshot(
            fetchedAtEpochMs = parts[0].toLong(),
            pool = decodeTracks(parts[1]),
            displayedTracks = decodeTracks(parts[2]),
            history = if (parts[3].isEmpty()) emptyList() else parts[3].split(";").map(::decodeValue).filterNotNull()
        )
    }.getOrNull()

    private fun encodeTracks(tracks: List<CatalogTrack>): String =
        tracks.joinToString(";") { track ->
            listOf(
                encodeValue(track.url),
                encodeValue(track.title),
                encodeValue(track.artist),
                encodeValue(track.thumbnailUrl),
                encodeValue(track.durationSeconds?.toString()),
                encodeValue(track.viewCount?.toString())
            ).joinToString(".")
        }

    private fun decodeTracks(value: String): List<CatalogTrack> =
        if (value.isEmpty()) emptyList() else value.split(";").mapNotNull { row ->
            val fields = row.split(".")
            if (fields.size != 6) return@mapNotNull null
            val values = fields.map(::decodeValue)
            val url = values[0] ?: return@mapNotNull null
            val title = values[1] ?: return@mapNotNull null
            CatalogTrack(
                url = url,
                title = title,
                artist = values[2],
                thumbnailUrl = values[3],
                durationSeconds = values[4]?.toLongOrNull(),
                viewCount = values[5]?.toLongOrNull()
            )
        }

    private fun encodeValue(value: String?): String = value?.let {
        Base64.encodeToString(it.toByteArray(Charsets.UTF_8), FLAGS)
    } ?: NULL

    private fun decodeValue(value: String): String? =
        if (value == NULL) null else String(Base64.decode(value, FLAGS), Charsets.UTF_8)
}
