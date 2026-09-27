package com.ipodplayer3.app.data.playlist

import android.content.Context
import com.ipodplayer3.app.data.model.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class PlaylistDto(
    val id: Long,
    val name: String,
    val songIds: List<Long>,
    val createdAt: Long,
)

class PlaylistStore(context: Context) {
    private val file = File(context.filesDir, "playlists.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            _playlists.value = emptyList()
            return@withContext
        }
        runCatching {
            val dtos = json.decodeFromString<List<PlaylistDto>>(file.readText())
            _playlists.value = dtos.map {
                Playlist(it.id, it.name, it.songIds, it.createdAt)
            }
        }.onFailure {
            _playlists.value = emptyList()
        }
    }

    private suspend fun persist(list: List<Playlist>) = withContext(Dispatchers.IO) {
        val dtos = list.map { PlaylistDto(it.id, it.name, it.songIds, it.createdAt) }
        file.writeText(json.encodeToString(dtos))
        _playlists.value = list
    }

    suspend fun create(name: String): Playlist {
        val playlist = Playlist(
            id = System.currentTimeMillis(),
            name = name.ifBlank { "播放列表 ${_playlists.value.size + 1}" },
        )
        persist(_playlists.value + playlist)
        return playlist
    }

    suspend fun rename(id: Long, name: String) {
        persist(
            _playlists.value.map {
                if (it.id == id) it.copy(name = name) else it
            }
        )
    }

    suspend fun delete(id: Long) {
        persist(_playlists.value.filterNot { it.id == id })
    }

    suspend fun addSong(playlistId: Long, songId: Long) {
        persist(
            _playlists.value.map { p ->
                if (p.id == playlistId && songId !in p.songIds) {
                    p.copy(songIds = p.songIds + songId)
                } else p
            }
        )
    }

    suspend fun removeSong(playlistId: Long, songId: Long) {
        persist(
            _playlists.value.map { p ->
                if (p.id == playlistId) p.copy(songIds = p.songIds - songId) else p
            }
        )
    }

    suspend fun setSongs(playlistId: Long, songIds: List<Long>) {
        persist(
            _playlists.value.map { p ->
                if (p.id == playlistId) p.copy(songIds = songIds) else p
            }
        )
    }
}
