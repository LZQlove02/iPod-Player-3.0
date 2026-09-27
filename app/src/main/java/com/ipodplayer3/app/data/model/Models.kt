package com.ipodplayer3.app.data.model

import android.net.Uri

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val track: Int,
    val uri: Uri,
    val albumArtUri: Uri?,
    val dateAdded: Long = 0L,
    val size: Long = 0L,
    val mimeType: String = "audio/*",
    val path: String = "",
)

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val songCount: Int,
    val albumArtUri: Uri?,
)

data class Artist(
    val id: Long,
    val name: String,
    val songCount: Int,
    val albumCount: Int,
)

data class Playlist(
    val id: Long,
    val name: String,
    val songIds: List<Long> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

data class LyricLine(
    val timeMs: Long,
    val text: String,
)

data class Lyrics(
    val lines: List<LyricLine>,
    val offsetMs: Long = 0L,
) {
    fun textAt(positionMs: Long): String {
        if (lines.isEmpty()) return ""
        val t = positionMs - offsetMs
        var current = lines.first()
        for (line in lines) {
            if (line.timeMs <= t) current = line else break
        }
        return current.text
    }

    fun activeIndex(positionMs: Int): Int {
        if (lines.isEmpty()) return -1
        val t = positionMs - offsetMs
        var idx = 0
        for (i in lines.indices) {
            if (lines[i].timeMs <= t) idx = i else break
        }
        return idx
    }
}

enum class DeviceTheme { SILVER, BLACK }

enum class MenuItemId {
    MUSIC, COVER_FLOW, PLAYLISTS, SEARCH, NOW_PLAYING, SETTINGS, ABOUT,
    SONGS, ALBUMS, ARTISTS, GENRES, LYRICS, SHUFFLE, REPEAT,
}
