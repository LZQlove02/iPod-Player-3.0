package com.ipodplayer3.app.data.library

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.ipodplayer3.app.data.model.Album
import com.ipodplayer3.app.data.model.Artist
import com.ipodplayer3.app.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MusicRepository(private val context: Context) {

    @Volatile
    private var cache: List<Song> = emptyList()

    suspend fun loadAll(force: Boolean = false): List<Song> = withContext(Dispatchers.IO) {
        if (!force && cache.isNotEmpty()) return@withContext cache
        val songs = queryAudio()
        cache = songs
        songs
    }

    fun cachedSongs(): List<Song> = cache

    suspend fun albums(songs: List<Song> = emptyList()): List<Album> {
        val list = if (songs.isEmpty()) loadAll() else songs
        return list.groupBy { it.albumId to it.album }
            .map { (key, group) ->
                Album(
                    id = key.first,
                    title = key.second.ifBlank { "未知专辑" },
                    artist = group.firstOrNull()?.artist?.ifBlank { "未知艺术家" } ?: "未知艺术家",
                    songCount = group.size,
                    albumArtUri = group.firstOrNull()?.albumArtUri,
                )
            }
            .sortedBy { it.title.lowercase() }
    }

    suspend fun artists(songs: List<Song> = emptyList()): List<Artist> {
        val list = if (songs.isEmpty()) loadAll() else songs
        return list.groupBy { it.artist.ifBlank { "未知艺术家" } }
            .map { (name, group) ->
                Artist(
                    id = name.hashCode().toLong(),
                    name = name,
                    songCount = group.size,
                    albumCount = group.map { it.albumId }.distinct().size,
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    suspend fun songsByAlbum(albumId: Long): List<Song> =
        loadAll().filter { it.albumId == albumId }.sortedBy { it.track }

    suspend fun songsByArtist(artist: String): List<Song> =
        loadAll().filter { it.artist == artist }.sortedBy { it.album.lowercase() }

    suspend fun search(query: String): List<Song> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return loadAll().filter {
            it.title.contains(q, true) ||
                it.artist.contains(q, true) ||
                it.album.contains(q, true)
        }
    }

    private fun queryAudio(): List<Song> {
        val collection: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATA,
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC}!=0 AND ${MediaStore.Audio.Media.DURATION}>0"
        val songs = mutableListOf<Song>()

        context.contentResolver.query(
            collection,
            projection,
            selection,
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val albumId = cursor.getLong(albumIdCol)
                val albumArt = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    albumId
                )
                songs += Song(
                    id = id,
                    title = cursor.getString(titleCol) ?: "未知歌曲",
                    artist = normalizeArtist(cursor.getString(artistCol)),
                    album = cursor.getString(albumCol) ?: "未知专辑",
                    albumId = albumId,
                    durationMs = cursor.getLong(durationCol),
                    track = cursor.getInt(trackCol) % 1000,
                    uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    ),
                    albumArtUri = albumArt,
                    dateAdded = cursor.getLong(dateCol),
                    size = cursor.getLong(sizeCol),
                    mimeType = cursor.getString(mimeCol) ?: "audio/*",
                    path = cursor.getString(dataCol) ?: "",
                )
            }
        }
        return songs
    }

    private fun normalizeArtist(raw: String?): String {
        val value = raw?.trim().orEmpty()
        return if (value.isEmpty() || value.equals("<unknown>", true)) "未知艺术家" else value
    }
}
