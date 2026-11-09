package com.nexus.music.library

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.nexus.music.catalog.CatalogTrack

/** Small SQLite store with no ORM, KSP processor, or extra runtime dependency. */
class SqliteLibraryRepository(context: Context) : LibraryRepository {
    private val helper = LibraryDatabase(context.applicationContext)

    override fun setLiked(track: CatalogTrack, liked: Boolean): Boolean {
        val item = persistTrack(track) ?: return false
        val db = helper.writableDatabase
        if (liked) insertMembership(db, "liked", item.id) else db.delete("liked", "video_id = ?", arrayOf(item.id))
        return true
    }

    override fun setSaved(track: CatalogTrack, saved: Boolean): Boolean {
        val item = persistTrack(track) ?: return false
        val db = helper.writableDatabase
        if (saved) insertMembership(db, "saved", item.id) else db.delete("saved", "video_id = ?", arrayOf(item.id))
        return true
    }

    override fun isLiked(videoId: String): Boolean = contains("liked", videoId)
    override fun isSaved(videoId: String): Boolean = contains("saved", videoId)
    override fun likedTracks(): List<LibraryTrack> = membershipTracks("liked")
    override fun savedTracks(): List<LibraryTrack> = membershipTracks("saved")

    override fun createPlaylist(name: String): Long {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Playlist name cannot be empty." }
        require(clean.length <= 80) { "Playlist name must be 80 characters or fewer." }
        return helper.writableDatabase.insertOrThrow(
            "playlists", null, ContentValues().apply {
                put("name", clean)
                put("created_at", System.currentTimeMillis())
            }
        )
    }

    override fun renamePlaylist(id: Long, name: String): Boolean {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Playlist name cannot be empty." }
        require(clean.length <= 80) { "Playlist name must be 80 characters or fewer." }
        return helper.writableDatabase.update(
            "playlists", ContentValues().apply { put("name", clean) },
            "id = ?", arrayOf(id.toString())
        ) == 1
    }

    override fun deletePlaylist(id: Long): Boolean =
        helper.writableDatabase.delete("playlists", "id = ?", arrayOf(id.toString())) == 1

    override fun playlists(): List<PlaylistSummary> {
        val result = mutableListOf<PlaylistSummary>()
        helper.readableDatabase.rawQuery(
            """SELECT p.id, p.name, COUNT(i.video_id)
               FROM playlists p LEFT JOIN playlist_items i ON i.playlist_id = p.id
               GROUP BY p.id ORDER BY p.created_at DESC, p.id DESC""".trimIndent(), null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += PlaylistSummary(cursor.getLong(0), cursor.getString(1), cursor.getInt(2))
            }
        }
        return result
    }

    override fun addToPlaylist(playlistId: Long, track: CatalogTrack): Boolean {
        val item = persistTrack(track) ?: return false
        val db = helper.writableDatabase
        if (!playlistExists(db, playlistId)) return false
        db.insertWithOnConflict(
            "playlist_items", null, ContentValues().apply {
                put("playlist_id", playlistId)
                put("video_id", item.id)
                put("added_at", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_IGNORE
        )
        return true
    }

    override fun removeFromPlaylist(playlistId: Long, videoId: String): Boolean =
        helper.writableDatabase.delete(
            "playlist_items", "playlist_id = ? AND video_id = ?",
            arrayOf(playlistId.toString(), videoId)
        ) > 0

    override fun playlistTracks(playlistId: Long): List<LibraryTrack> =
        queryTracks(
            """SELECT t.video_id, t.url, t.title, t.artist, t.thumbnail_url, t.duration_seconds
               FROM tracks t INNER JOIN playlist_items i ON i.video_id = t.video_id
               WHERE i.playlist_id = ? ORDER BY i.added_at ASC""".trimIndent(),
            arrayOf(playlistId.toString())
        )

    override fun recordHistory(track: CatalogTrack, playedAtMillis: Long): Boolean {
        val item = persistTrack(track) ?: return false
        val db = helper.writableDatabase
        db.insert("history", null, ContentValues().apply {
            put("video_id", item.id)
            put("played_at", playedAtMillis.coerceAtLeast(0L))
        })
        return true
    }

    override fun history(limit: Int): List<PlayedTrack> {
        val safeLimit = limit.coerceIn(1, 500)
        val result = mutableListOf<PlayedTrack>()
        helper.readableDatabase.rawQuery(
            """SELECT t.video_id, t.url, t.title, t.artist, t.thumbnail_url, t.duration_seconds, h.played_at
               FROM history h INNER JOIN tracks t ON t.video_id = h.video_id
               ORDER BY h.played_at DESC, h.id DESC LIMIT ?""".trimIndent(),
            arrayOf(safeLimit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) result += PlayedTrack(cursor.trackAtZero(), cursor.getLong(6))
        }
        return result
    }

    fun close() = helper.close()

    private fun persistTrack(track: CatalogTrack): LibraryTrack? {
        val item = LibraryTrack.from(track) ?: return null
        val db = helper.writableDatabase
        val values = trackValues(item)
        val inserted = db.insertWithOnConflict("tracks", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        if (inserted == -1L) {
            db.update("tracks", values, "video_id = ?", arrayOf(item.id))
        }
        return item
    }

    private fun trackValues(track: LibraryTrack) = ContentValues().apply {
        put("video_id", track.id)
        put("url", track.url)
        put("title", track.title)
        put("artist", track.artist)
        put("thumbnail_url", track.thumbnailUrl)
        if (track.durationSeconds == null) putNull("duration_seconds") else put("duration_seconds", track.durationSeconds)
    }

    private fun insertMembership(db: SQLiteDatabase, table: String, id: String) {
        db.insertWithOnConflict(table, null, ContentValues().apply {
            put("video_id", id)
            put("added_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun contains(table: String, id: String): Boolean {
        helper.readableDatabase.rawQuery(
            "SELECT 1 FROM $table WHERE video_id = ? LIMIT 1", arrayOf(id)
        ).use { return it.moveToFirst() }
    }

    private fun membershipTracks(table: String): List<LibraryTrack> = queryTracks(
        """SELECT t.video_id, t.url, t.title, t.artist, t.thumbnail_url, t.duration_seconds
           FROM tracks t INNER JOIN $table m ON m.video_id = t.video_id
           ORDER BY m.added_at DESC""".trimIndent(), emptyArray()
    )

    private fun queryTracks(sql: String, args: Array<String>): List<LibraryTrack> {
        val result = mutableListOf<LibraryTrack>()
        helper.readableDatabase.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) result += cursor.trackAtZero()
        }
        return result
    }

    private fun android.database.Cursor.trackAtZero() = LibraryTrack(
        id = getString(0),
        url = getString(1),
        title = getString(2),
        artist = if (isNull(3)) null else getString(3),
        thumbnailUrl = if (isNull(4)) null else getString(4),
        durationSeconds = if (isNull(5)) null else getLong(5)
    )

    private fun playlistExists(db: SQLiteDatabase, id: Long): Boolean {
        db.rawQuery("SELECT 1 FROM playlists WHERE id = ?", arrayOf(id.toString())).use {
            return it.moveToFirst()
        }
    }

    private class LibraryDatabase(context: Context) : SQLiteOpenHelper(context, "nexus_library.db", null, 1) {
        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE tracks (
                video_id TEXT PRIMARY KEY NOT NULL,
                url TEXT NOT NULL,
                title TEXT NOT NULL,
                artist TEXT,
                thumbnail_url TEXT,
                duration_seconds INTEGER
            )""".trimIndent())
            db.execSQL("""CREATE TABLE liked (
                video_id TEXT PRIMARY KEY NOT NULL REFERENCES tracks(video_id) ON DELETE CASCADE,
                added_at INTEGER NOT NULL
            )""".trimIndent())
            db.execSQL("""CREATE TABLE saved (
                video_id TEXT PRIMARY KEY NOT NULL REFERENCES tracks(video_id) ON DELETE CASCADE,
                added_at INTEGER NOT NULL
            )""".trimIndent())
            db.execSQL("""CREATE TABLE playlists (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL COLLATE NOCASE UNIQUE,
                created_at INTEGER NOT NULL
            )""".trimIndent())
            db.execSQL("""CREATE TABLE playlist_items (
                playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
                video_id TEXT NOT NULL REFERENCES tracks(video_id) ON DELETE CASCADE,
                added_at INTEGER NOT NULL,
                PRIMARY KEY (playlist_id, video_id)
            )""".trimIndent())
            db.execSQL("""CREATE TABLE history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                video_id TEXT NOT NULL REFERENCES tracks(video_id) ON DELETE CASCADE,
                played_at INTEGER NOT NULL
            )""".trimIndent())
            db.execSQL("CREATE INDEX history_played_at_idx ON history(played_at DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Schema v1 is the first shipped local-library format; future migrations are additive.
        }
    }
}
