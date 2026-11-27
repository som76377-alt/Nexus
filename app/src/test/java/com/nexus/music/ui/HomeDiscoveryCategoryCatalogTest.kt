package com.nexus.music.ui

import com.nexus.music.catalog.YoutubeSearchScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HomeDiscoveryCategoryCatalogTest {
    @Test
    fun parsesGroupsAndPreservesSearchScopes() {
        val groups = HomeDiscoveryCategoryCatalog.parse(
            """{"shelves":[{"id":"genres","title":"Genres","buckets":[["jazz","Jazz","jazz official audio","MUSIC_SONGS"]]},{"id":"longform","title":"Long form","buckets":[["ambient","Ambient","ambient long mix","EVERYTHING"]]}]}"""
        )

        assertEquals(listOf("Genres", "Long form"), groups.map { it.title })
        assertEquals(YoutubeSearchScope.MUSIC_SONGS, groups[0].categories.single().scope)
        assertEquals(YoutubeSearchScope.EVERYTHING, groups[1].categories.single().scope)
        assertEquals("ambient long mix", groups[1].categories.single().query)
    }

    @Test
    fun rejectsDuplicateCategoryIds() {
        assertThrows(IllegalArgumentException::class.java) {
            HomeDiscoveryCategoryCatalog.parse(
                """{"shelves":[{"id":"one","title":"One","buckets":[["same","First","first query","MUSIC_SONGS"]]},{"id":"two","title":"Two","buckets":[["same","Second","second query","MUSIC_SONGS"]]}]}"""
            )
        }
    }

    @Test
    fun rejectsUnknownSearchScopesAndEmptyQueries() {
        assertThrows(IllegalArgumentException::class.java) {
            HomeDiscoveryCategoryCatalog.parse(
                """{"shelves":[{"id":"one","title":"One","buckets":[["x","Example","query","UNSUPPORTED"]]}]}"""
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            HomeDiscoveryCategoryCatalog.parse(
                """{"shelves":[{"id":"one","title":"One","buckets":[["x","Example","","MUSIC_SONGS"]]}]}"""
            )
        }
    }
}
