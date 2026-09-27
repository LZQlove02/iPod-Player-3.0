package com.ipodplayer3.app.ui

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ipodplayer3.app.IPodApplication
import com.ipodplayer3.app.data.model.Album
import com.ipodplayer3.app.data.model.Lyrics
import com.ipodplayer3.app.data.model.Playlist
import com.ipodplayer3.app.data.model.Song
import com.ipodplayer3.app.data.lyrics.LyricParser
import com.ipodplayer3.app.data.player.RepeatMode
import com.ipodplayer3.app.ui.settings.AppLanguage
import com.ipodplayer3.app.ui.settings.EqPreset
import com.ipodplayer3.app.ui.settings.SettingsRepository
import com.ipodplayer3.app.ui.settings.Strings
import com.ipodplayer3.app.ui.theme.BlackTheme
import com.ipodplayer3.app.ui.theme.IpodTheme
import com.ipodplayer3.app.ui.theme.SilverTheme
import com.ipodplayer3.app.ui.theme.U2Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object MainMenu : Screen
    data object MusicMenu : Screen
    data object Songs : Screen
    data object Albums : Screen
    data object Artists : Screen
    data object CoverFlow : Screen
    data object Playlists : Screen
    data object PlaylistDetail : Screen
    data object Search : Screen
    data object NowPlaying : Screen
    data object Lyrics : Screen
    data object Settings : Screen
    data object Eq : Screen
    data object About : Screen
    data class AlbumDetail(val albumId: Long, val title: String) : Screen
    data class ArtistDetail(val artist: String) : Screen
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IPodApplication
    private val musicRepo = app.musicRepository
    private val playlistStore = app.playlistStore
    private val player = app.playerController
    val settings = SettingsRepository(application)

    var screen by mutableStateOf<Screen>(Screen.MainMenu)
    var stack by mutableStateOf(listOf<Screen>(Screen.MainMenu))
        private set

    var selectedIndex by mutableStateOf(0)
        private set

    val songs: StateFlow<List<Song>> get() = _songs
    private val _songs = MutableStateFlow<List<Song>>(emptyList())

    val albums: StateFlow<List<Album>> get() = _albums
    private val _albums = MutableStateFlow<List<Album>>(emptyList())

    val artists: StateFlow<List<com.ipodplayer3.app.data.model.Artist>> get() = _artists
    private val _artists = MutableStateFlow<List<com.ipodplayer3.app.data.model.Artist>>(emptyList())

    val playlists: StateFlow<List<Playlist>> get() = playlistStore.playlists

    val isPlaying = player.isPlaying
    val currentSong = player.currentSong
    val positionMs = player.positionMs
    val durationMs = player.durationMs
    val shuffle = player.shuffle
    val repeat = player.repeat

    var language by mutableStateOf(AppLanguage.ZH)
    var theme by mutableStateOf<IpodTheme>(SilverTheme)
    var clickSound by mutableStateOf(true)
    var vibrate by mutableStateOf(true)
    var eqEnabled by mutableStateOf(false)
    var eqPreset by mutableStateOf(EqPreset.FLAT)
    var bass by mutableStateOf(0)
    var treble by mutableStateOf(0)

    var lyrics by mutableStateOf<Lyrics?>(null)
    var searchQuery by mutableStateOf("")

    var coverIndex by mutableStateOf(0)
    var coverFlipped by mutableStateOf(false)
    var currentPlaylistId by mutableStateOf(0L)
    var detailSongs by mutableStateOf<List<Song>>(emptyList())
    var detailTitle by mutableStateOf("")

    val strings: Strings.Bundle get() = Strings.of(language)

    fun onInit() {
        player.connect()
        viewModelScope.launch {
            settings.language.collect { language = it }
        }
        viewModelScope.launch {
            settings.themeName.collect { name ->
                theme = when (name) {
                    "black" -> BlackTheme
                    "u2" -> U2Theme
                    else -> SilverTheme
                }
            }
        }
        viewModelScope.launch {
            settings.clickSound.collect { clickSound = it }
        }
        viewModelScope.launch {
            settings.vibrate.collect { vibrate = it }
        }
        viewModelScope.launch {
            settings.eqEnabled.collect { eqEnabled = it }
        }
        viewModelScope.launch {
            settings.eqPreset.collect { eqPreset = it }
        }
        viewModelScope.launch {
            settings.bass.collect { bass = it }
        }
        viewModelScope.launch {
            settings.treble.collect { treble = it }
        }
        viewModelScope.launch {
            playlistStore.load()
        }
        viewModelScope.launch {
            while (true) {
                player.pollPosition()
                kotlinx.coroutines.delay(400)
            }
        }
    }

    fun loadLibrary() {
        viewModelScope.launch {
            val list = musicRepo.loadAll()
            _songs.value = list
            _albums.value = musicRepo.albums(list)
            _artists.value = musicRepo.artists(list)
        }
    }

    // ---- navigation ----
    fun push(screen: Screen) {
        stack = stack + screen
        this.screen = screen
        selectedIndex = 0
        coverFlipped = false
    }

    fun pop() {
        if (stack.size <= 1) return
        stack = stack.dropLast(1)
        screen = stack.last()
        selectedIndex = 0
        coverFlipped = false
    }

    fun popToMain() {
        stack = listOf(Screen.MainMenu)
        screen = Screen.MainMenu
        selectedIndex = 0
    }

    fun select(delta: Int, itemCount: Int) {
        if (itemCount <= 0) return
        selectedIndex = (selectedIndex + delta).coerceIn(0, itemCount - 1)
    }

    fun setSelected(index: Int) {
        selectedIndex = index.coerceAtLeast(0)
    }

    // ---- library actions ----
    fun playSongs(list: List<Song>, index: Int = 0) {
        if (list.isEmpty()) return
        player.playQueue(list, index)
        loadLyricsFor(list.getOrNull(index))
    }

    fun onQueueIndexChanged() {
        loadLyricsFor(player.currentSong.value)
    }

    private fun loadLyricsFor(song: Song?) {
        viewModelScope.launch {
            lyrics = withContext(Dispatchers.IO) {
                song?.let { LyricParser.loadForAudio(it.path) }
            }
        }
    }

    fun openAlbum(album: Album) {
        viewModelScope.launch {
            detailSongs = musicRepo.songsByAlbum(album.id)
            detailTitle = album.title
            push(Screen.AlbumDetail(album.id, album.title))
        }
    }

    fun openArtist(artist: String) {
        viewModelScope.launch {
            detailSongs = musicRepo.songsByArtist(artist)
            detailTitle = artist
            push(Screen.ArtistDetail(artist))
        }
    }

    fun openPlaylist(id: Long) {
        currentPlaylistId = id
        val playlist = playlists.value.firstOrNull { it.id == id } ?: return
        detailTitle = playlist.name
        detailSongs = _songs.value.filter { it.id in playlist.songIds }
            .sortedBy { playlist.songIds.indexOf(it.id) }
        push(Screen.PlaylistDetail)
    }

    fun runSearch() {
        viewModelScope.launch {
            detailSongs = musicRepo.search(searchQuery)
            detailTitle = strings.search
        }
    }

    // ---- playlists ----
    fun createPlaylist(name: String) {
        viewModelScope.launch {
            val p = playlistStore.create(name)
            openPlaylist(p.id)
        }
    }

    fun addCurrentSongToPlaylist(playlistId: Long) {
        val id = player.currentSong.value?.id ?: return
        viewModelScope.launch { playlistStore.addSong(playlistId, id) }
    }

    fun addSongToPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch { playlistStore.addSong(playlistId, songId) }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch {
            playlistStore.delete(id)
            pop()
        }
    }

    // ---- player controls from wheel ----
    fun wheelScroll(delta: Int) {
        when (screen) {
            is Screen.CoverFlow -> {
                val list = albums.value
                if (list.isEmpty()) return
                coverIndex = (coverIndex + delta).coerceIn(0, list.lastIndex)
                coverFlipped = false
            }
            is Screen.NowPlaying -> {
                player.seekBy(delta * 2_000L)
            }
            is Screen.Lyrics -> {
                player.seekBy(delta * 2_000L)
            }
            else -> {
                val count = currentMenuCount()
                select(delta, count)
            }
        }
    }

    fun currentMenuCount(): Int = when (screen) {
        Screen.MainMenu -> 7
        Screen.MusicMenu -> 5
        Screen.Songs -> songs.value.size
        Screen.Albums -> albums.value.size
        Screen.Artists -> artists.value.size
        Screen.CoverFlow -> albums.value.size
        Screen.Playlists -> playlists.value.size
        Screen.PlaylistDetail,
        is Screen.AlbumDetail,
        is Screen.ArtistDetail -> detailSongs.size
        Screen.Search -> 1
        Screen.NowPlaying -> 0
        Screen.Lyrics -> 0
        Screen.Settings -> 7
        Screen.Eq -> 4
        Screen.About -> 0
    }

    fun activate() {
        when (screen) {
            Screen.MainMenu -> when (selectedIndex) {
                0 -> push(Screen.MusicMenu)
                1 -> push(Screen.CoverFlow)
                2 -> push(Screen.Playlists)
                3 -> push(Screen.Search)
                4 -> push(Screen.NowPlaying)
                5 -> push(Screen.Settings)
                else -> push(Screen.About)
            }
            Screen.MusicMenu -> when (selectedIndex) {
                0 -> push(Screen.Songs)
                1 -> push(Screen.Albums)
                2 -> push(Screen.Artists)
                3 -> push(Screen.CoverFlow)
                else -> push(Screen.Playlists)
            }
            Screen.Songs -> {
                val list = songs.value
                val idx = selectedIndex
                playSongs(list, idx)
                push(Screen.NowPlaying)
            }
            Screen.Albums -> {
                albums.value.getOrNull(selectedIndex)?.let { openAlbum(it) }
            }
            Screen.Artists -> {
                artists.value.getOrNull(selectedIndex)?.let { openArtist(it.name) }
            }
            is Screen.CoverFlow -> {
                if (coverFlipped) {
                    albums.value.getOrNull(coverIndex)?.let { openAlbum(it) }
                } else {
                    coverFlipped = true
                }
            }
            Screen.Playlists -> {
                playlists.value.getOrNull(selectedIndex)?.let { openPlaylist(it.id) }
            }
            Screen.PlaylistDetail,
            is Screen.AlbumDetail,
            is Screen.ArtistDetail -> {
                val list = detailSongs
                playSongs(list, selectedIndex)
                push(Screen.NowPlaying)
            }
            Screen.Search -> {
                runSearch()
                if (detailSongs.isNotEmpty()) {
                    playSongs(detailSongs, 0)
                    push(Screen.NowPlaying)
                }
            }
            Screen.NowPlaying -> push(Screen.Lyrics)
            Screen.Settings -> when (selectedIndex) {
                0 -> push(Screen.Eq)
                1 -> viewModelScope.launch {
                    settings.setLanguage(if (language == AppLanguage.ZH) AppLanguage.EN else AppLanguage.ZH)
                }
                2 -> viewModelScope.launch {
                    val next = when (theme.name) {
                        "silver" -> "black"
                        "black" -> "u2"
                        else -> "silver"
                    }
                    settings.setTheme(next)
                }
                3 -> viewModelScope.launch { settings.setClickSound(!clickSound) }
                4 -> viewModelScope.launch { settings.setVibrate(!vibrate) }
                5 -> player.setShuffle(!shuffle.value)
                else -> player.cycleRepeat()
            }
            Screen.Eq -> when (selectedIndex) {
                0 -> viewModelScope.launch { settings.setEqEnabled(!eqEnabled) }
                1 -> viewModelScope.launch {
                    val order = EqPreset.entries
                    val next = order[(order.indexOf(eqPreset) + 1) % order.size]
                    settings.setEqPreset(next)
                }
                2 -> viewModelScope.launch { settings.setBass(bass + 1) }
                3 -> viewModelScope.launch { settings.setTreble(treble + 1) }
            }
            Screen.About -> Unit
            Screen.Lyrics -> Unit
        }
    }

    fun togglePlayPause() = player.togglePlayPause()
    fun next() {
        player.next()
        loadLyricsFor(player.currentSong.value)
    }

    fun previous() {
        player.previous()
        loadLyricsFor(player.currentSong.value)
    }

    fun setShuffle(v: Boolean) = player.setShuffle(v)
    fun cycleRepeat() = player.cycleRepeat()

    fun applyEq() {
        player.setEqualizer(eqEnabled, bass, treble)
    }

    fun buzz() {
        val ctx = getApplication<Application>()
        if (!vibrate) return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(12)
        }
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
