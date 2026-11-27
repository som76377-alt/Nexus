package com.nexus.music.ui

import android.content.Context
import com.nexus.music.catalog.YoutubeSearchScope
import org.json.JSONArray
import org.json.JSONObject

data class HomeDiscoveryCategory(
    val id: String,
    val label: String,
    val query: String,
    val scope: YoutubeSearchScope
)

data class HomeDiscoveryGroup(
    val id: String,
    val title: String,
    val categories: List<HomeDiscoveryCategory>
)

/**
 * Reads the bundled Home category catalogue. Parsing is kept independent of Android views so
 * the catalogue contract can be covered by deterministic unit tests.
 */
object HomeDiscoveryCategoryCatalog {
    fun load(context: Context): List<HomeDiscoveryGroup> = runCatching {
        context.assets.open("genre-buckets.json").bufferedReader().use { reader ->
            parse(reader.readText())
        }
    }.getOrElse { fallback() }

    fun parse(json: String): List<HomeDiscoveryGroup> {
        val root = JSONObject(json)
        val shelves = root.optJSONArray("shelves")
            ?: throw IllegalArgumentException("Category catalogue must contain shelves.")
        require(shelves.length() > 0) { "Category catalogue cannot be empty." }

        val groupIds = mutableSetOf<String>()
        val categoryIds = mutableSetOf<String>()
        val groups = buildList {
            for (index in 0 until shelves.length()) {
                val shelf = shelves.optJSONObject(index)
                    ?: throw IllegalArgumentException("Category shelf must be an object.")
                val id = shelf.optString("id").trim()
                val title = shelf.optString("title").trim()
                require(id.isNotEmpty() && title.isNotEmpty()) { "Category shelf id and title are required." }
                require(groupIds.add(id)) { "Duplicate category shelf id: $id" }
                val entries = shelf.optJSONArray("buckets")
                    ?: throw IllegalArgumentException("Category shelf $id must contain buckets.")
                val categories = buildList {
                    for (bucketIndex in 0 until entries.length()) {
                        val bucket = entries.optJSONArray(bucketIndex)
                            ?: throw IllegalArgumentException("Category bucket must be an array.")
                        require(bucket.length() == 4) { "Category bucket must contain id, label, query and scope." }
                        val categoryId = bucket.optString(0).trim()
                        val label = bucket.optString(1).trim()
                        val query = bucket.optString(2).trim()
                        val scopeName = bucket.optString(3).trim()
                        require(categoryId.isNotEmpty() && label.isNotEmpty() && query.isNotEmpty()) {
                            "Category id, label and query are required."
                        }
                        require(categoryIds.add(categoryId)) { "Duplicate category id: $categoryId" }
                        val scope = runCatching { YoutubeSearchScope.valueOf(scopeName) }
                            .getOrElse { throw IllegalArgumentException("Unsupported search scope: $scopeName") }
                        add(HomeDiscoveryCategory(categoryId, label, query, scope))
                    }
                }
                require(categories.isNotEmpty()) { "Category shelf $id cannot be empty." }
                add(HomeDiscoveryGroup(id, title, categories))
            }
        }
        require(groups.isNotEmpty()) { "Category catalogue cannot be empty." }
        return groups
    }

    private fun fallback(): List<HomeDiscoveryGroup> = listOf(
        HomeDiscoveryGroup(
            id = "quick-picks",
            title = "Quick picks",
            categories = listOf(
                HomeDiscoveryCategory("ghazal", "Ghazal", "ghazal classics", YoutubeSearchScope.MUSIC_SONGS),
                HomeDiscoveryCategory("indian-indie", "Indian indie", "Indian independent music", YoutubeSearchScope.MUSIC_SONGS),
                HomeDiscoveryCategory("classical", "Classical", "Indian classical music", YoutubeSearchScope.MUSIC_SONGS),
                HomeDiscoveryCategory("instrumental", "Instrumental", "instrumental music", YoutubeSearchScope.MUSIC_SONGS),
                HomeDiscoveryCategory("new-voices", "New voices", "new Indian music", YoutubeSearchScope.MUSIC_SONGS),
                HomeDiscoveryCategory("evergreen", "Evergreen", "evergreen Hindi songs", YoutubeSearchScope.MUSIC_SONGS)
            )
        )
    )
}
