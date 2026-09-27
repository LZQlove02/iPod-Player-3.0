package com.ipodplayer3.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.core.content.ContextCompat
import com.ipodplayer3.app.data.model.Album
import com.ipodplayer3.app.data.model.Song
import com.ipodplayer3.app.ui.MainViewModel
import com.ipodplayer3.app.ui.Screen
import com.ipodplayer3.app.ui.components.CoverFlow
import com.ipodplayer3.app.ui.components.MenuRow
import com.ipodplayer3.app.ui.components.SelectableList
import com.ipodplayer3.app.ui.components.SplitPreviewPanel
import com.ipodplayer3.app.ui.device.ClickWheel
import com.ipodplayer3.app.ui.device.DeviceChrome
import com.ipodplayer3.app.ui.device.StatusBar
import com.ipodplayer3.app.ui.device.formatTime
import com.ipodplayer3.app.ui.settings.AppLanguage
import com.ipodplayer3.app.ui.settings.EqPreset

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) vm.loadLibrary()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.onInit()
        ensurePermission()

        setContent {
            MaterialTheme(
                colorScheme = if (vm.theme.isDarkScreen) darkColorScheme() else lightColorScheme()
            ) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF1C1C1E)) {
                    IpodApp(vm)
                }
            }
        }
    }

    private fun ensurePermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            vm.loadLibrary()
        } else {
            permissionLauncher.launch(permission)
        }
    }
}

@Composable
fun IpodApp(vm: MainViewModel) {
    val theme = vm.theme
    val strings = vm.strings
    val songs by vm.songs.collectAsState()
    val albums by vm.albums.collectAsState()
    val artists by vm.artists.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val current by vm.currentSong.collectAsState()
    val position by vm.positionMs.collectAsState()
    val duration by vm.durationMs.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(current?.id) {
        vm.onQueueIndexChanged()
    }

    Box(
        Modifier
            .fillMaxSize()
            .padding(vertical = 12.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        DeviceChrome(
            theme = theme,
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            screenContent = {
                Column(Modifier.fillMaxSize()) {
                    StatusBar(
                        theme = theme,
                        playing = isPlaying,
                        title = current?.title ?: strings.appName
                    )
                    IpodScreen(
                        vm = vm,
                        songs = songs,
                        albums = albums,
                        artists = artists,
                        playlists = playlists,
                        current = current,
                        position = position,
                        duration = duration,
                        isPlaying = isPlaying,
                        strings = strings
                    )
                }
            },
            wheelContent = {
                ClickWheel(
                    theme = theme,
                    diameter = 196.dp,
                    soundEnabled = vm.clickSound,
                    vibrateEnabled = vm.vibrate,
                    onScroll = { vm.wheelScroll(it) },
                    onMenu = {
                        vm.buzz()
                        if (vm.screen is Screen.MainMenu) Unit else {
                            // long-press-like: wheel code handles tap; MENU = back
                            vm.pop()
                        }
                    },
                    onPlayPause = {
                        vm.buzz()
                        vm.togglePlayPause()
                    },
                    onNext = {
                        vm.buzz()
                        if (vm.screen is Screen.NowPlaying || vm.screen is Screen.Lyrics) vm.next()
                        else vm.wheelScroll(1)
                    },
                    onPrevious = {
                        vm.buzz()
                        if (vm.screen is Screen.NowPlaying || vm.screen is Screen.Lyrics) vm.previous()
                        else vm.wheelScroll(-1)
                    },
                    onSelect = {
                        vm.buzz()
                        vm.activate()
                    }
                )
            }
        )
    }
}

@Composable
fun IpodScreen(
    vm: MainViewModel,
    songs: List<Song>,
    albums: List<Album>,
    artists: List<com.ipodplayer3.app.data.model.Artist>,
    playlists: List<com.ipodplayer3.app.data.model.Playlist>,
    current: Song?,
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    strings: com.ipodplayer3.app.ui.settings.Strings.Bundle,
) {
    val theme = vm.theme
    val dark = theme.isDarkScreen

    when (val screen = vm.screen) {
        Screen.MainMenu -> MenuWithPreview(
            theme = theme,
            strings = strings,
            rows = listOf(
                MenuRow("music", strings.music, iconText = "♪"),
                MenuRow("cover", strings.coverFlow, iconText = "CF"),
                MenuRow("playlist", strings.playlists, iconText = "≡"),
                MenuRow("search", strings.search, iconText = "⌕"),
                MenuRow("now", strings.nowPlaying, iconText = "▶"),
                MenuRow("settings", strings.settings, iconText = "⚙"),
                MenuRow("about", strings.about, iconText = "i"),
            ),
            selectedId = listOf("music", "cover", "playlist", "search", "now", "settings", "about")
                .getOrNull(vm.selectedIndex) ?: "music",
            previewTitle = current?.title ?: strings.appName,
            previewSubtitle = current?.artist,
            previewArt = current?.albumArtUri
        )

        Screen.MusicMenu -> MenuWithPreview(
            theme = theme,
            strings = strings,
            rows = listOf(
                MenuRow("songs", strings.songs, iconText = "♫"),
                MenuRow("albums", strings.albums, iconText = "▣"),
                MenuRow("artists", strings.artists, iconText = "☺"),
                MenuRow("cover", strings.coverFlow, iconText = "CF"),
                MenuRow("pl", strings.playlists, iconText = "≡"),
            ),
            selectedId = listOf("songs", "albums", "artists", "cover", "pl")
                .getOrNull(vm.selectedIndex) ?: "songs",
            previewTitle = strings.music,
            previewSubtitle = "${songs.size} ${strings.trackCount}",
            previewArt = current?.albumArtUri
        )

        Screen.Songs -> SongListScreen(vm, songs, strings)

        Screen.Albums -> MenuWithPreview(
            theme = theme,
            strings = strings,
            rows = albums.map {
                MenuRow(it.id.toString(), it.title, it.artist, artworkUri = it.albumArtUri)
            },
            selectedId = albums.getOrNull(vm.selectedIndex)?.id?.toString() ?: "",
            previewTitle = albums.getOrNull(vm.selectedIndex)?.title ?: "",
            previewSubtitle = albums.getOrNull(vm.selectedIndex)?.artist,
            previewArt = albums.getOrNull(vm.selectedIndex)?.albumArtUri
        )

        Screen.Artists -> MenuWithPreview(
            theme = theme,
            strings = strings,
            rows = artists.map {
                MenuRow(it.id.toString(), it.name, "${it.songCount} ${strings.trackCount}", iconText = "☺")
            },
            selectedId = artists.getOrNull(vm.selectedIndex)?.id?.toString() ?: "",
            previewTitle = artists.getOrNull(vm.selectedIndex)?.name ?: "",
            previewSubtitle = strings.artists,
            previewArt = current?.albumArtUri
        )

        is Screen.AlbumDetail, is Screen.ArtistDetail -> {
            SplitLayout(
                theme = theme,
                title = vm.detailTitle,
                subtitle = "${vm.detailSongs.size} ${strings.trackCount}",
                art = vm.detailSongs.firstOrNull()?.albumArtUri,
                list = {
                    SelectableList(
                        theme = theme,
                        rows = vm.detailSongs.mapIndexed { i, s ->
                            MenuRow(s.id.toString(), s.title, s.artist)
                        },
                        selectedId = vm.detailSongs.getOrNull(vm.selectedIndex)?.id?.toString() ?: "",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            )
        }

        Screen.Playlists -> MenuWithPreview(
            theme = theme,
            strings = strings,
            rows = listOf(MenuRow("new", strings.createPlaylist, iconText = "+")) +
                playlists.map {
                    MenuRow(it.id.toString(), it.name, "${it.songIds.size} ${strings.trackCount}", iconText = "≡")
                },
            selectedId = run {
                val all = listOf(MenuRow("new", strings.createPlaylist)) +
                    playlists.map { MenuRow(it.id.toString(), it.name) }
                all.getOrNull(vm.selectedIndex)?.id ?: "new"
            },
            previewTitle = strings.playlists,
            previewSubtitle = "${playlists.size}",
            previewArt = current?.albumArtUri
        )

        Screen.PlaylistDetail -> SplitLayout(
            theme = theme,
            title = vm.detailTitle,
            subtitle = "${vm.detailSongs.size} ${strings.trackCount}",
            art = vm.detailSongs.firstOrNull()?.albumArtUri,
            list = {
                SelectableList(
                    theme = theme,
                    rows = vm.detailSongs.map { MenuRow(it.id.toString(), it.title, it.artist) },
                    selectedId = vm.detailSongs.getOrNull(vm.selectedIndex)?.id?.toString() ?: "",
                    modifier = Modifier.fillMaxSize()
                )
            }
        )

        Screen.Search -> {
            Column(Modifier.fillMaxSize()) {
                Text(
                    strings.searchHint,
                    color = if (dark) Color(0xFF98989D) else Color(0xFF6E6E73),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(10.dp)
                )
                // simple wheel-driven query via selected char presets is overkill;
                // use searchable rows of recent + type-from-system later
                SelectableList(
                    theme = theme,
                    rows = if (vm.searchQuery.isBlank()) {
                        listOf(MenuRow("hint", strings.searchHint))
                    } else {
                        vm.detailSongs.map { MenuRow(it.id.toString(), it.title, it.artist) }
                    },
                    selectedId = vm.detailSongs.getOrNull(vm.selectedIndex)?.id?.toString() ?: "hint",
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Screen.CoverFlow -> CoverFlow(
            theme = theme,
            albums = albums,
            selectedIndex = vm.coverIndex,
            flipped = vm.coverFlipped,
            onIndexChange = { vm.coverIndex = it },
            onFlip = { vm.coverFlipped = !vm.coverFlipped },
            onOpenAlbum = { vm.openAlbum(it) },
            onScrollDelta = { vm.wheelScroll(it) },
            tracks = emptyList()
        )

        Screen.NowPlaying -> NowPlayingScreen(
            vm = vm,
            song = current,
            position = position,
            duration = duration,
            strings = strings,
            isPlaying = isPlaying
        )

        Screen.Lyrics -> LyricsScreen(vm, current, position, strings)

        Screen.Settings -> MenuWithPreview(
            theme = theme,
            strings = strings,
            rows = listOf(
                MenuRow("eq", strings.equalizer, if (vm.eqEnabled) strings.on else strings.off, iconText = "EQ"),
                MenuRow("lang", strings.language, if (vm.language == AppLanguage.ZH) strings.zh else strings.en, iconText = "文"),
                MenuRow("theme", strings.theme, when (vm.theme.name) {
                    "black" -> strings.black
                    "u2" -> strings.u2
                    else -> strings.silver
                }, iconText = "◐"),
                MenuRow("sound", strings.clickSound, if (vm.clickSound) strings.on else strings.off, iconText = "♪"),
                MenuRow("vib", strings.vibrate, if (vm.vibrate) strings.on else strings.off, iconText = "≈"),
                MenuRow("sh", strings.shuffle, if (vm.shuffle.collectAsState().value) strings.on else strings.off, iconText = "🔀"),
                MenuRow("rep", strings.repeat, when (vm.repeat.collectAsState().value) {
                    com.ipodplayer3.app.data.player.RepeatMode.ONE -> strings.one
                    com.ipodplayer3.app.data.player.RepeatMode.ALL -> strings.all
                    else -> strings.none
                }, iconText = "🔁"),
            ),
            selectedId = listOf("eq", "lang", "theme", "sound", "vib", "sh", "rep")
                .getOrNull(vm.selectedIndex) ?: "eq",
            previewTitle = strings.settings,
            previewSubtitle = null,
            previewArt = null
        )

        Screen.Eq -> MenuWithPreview(
            theme = theme,
            strings = strings,
            rows = listOf(
                MenuRow("en", strings.equalizer, if (vm.eqEnabled) strings.on else strings.off, iconText = "EQ"),
                MenuRow("preset", strings.pop, when (vm.eqPreset) {
                    EqPreset.FLAT -> strings.flat
                    EqPreset.POP -> strings.pop
                    EqPreset.ROCK -> strings.rock
                    EqPreset.CLASSICAL -> strings.classical
                    EqPreset.JAZZ -> strings.jazz
                    EqPreset.BASS -> strings.bassBoost
                }, iconText = "♪"),
                MenuRow("bass", strings.bass, "${vm.bass}", iconText = "B"),
                MenuRow("treble", strings.treble, "${vm.treble}", iconText = "T"),
            ),
            selectedId = listOf("en", "preset", "bass", "treble").getOrNull(vm.selectedIndex) ?: "en",
            previewTitle = strings.equalizer,
            previewSubtitle = null,
            previewArt = null
        )

        Screen.About -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(strings.appName, color = if (dark) Color.White else Color.Black, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                Text("v3.0.0", color = if (dark) Color.Gray else Color.DarkGray, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Kotlin · Compose · Media3",
                    color = if (dark) Color.Gray else Color.DarkGray,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun MenuWithPreview(
    theme: com.ipodplayer3.app.ui.theme.IpodTheme,
    strings: com.ipodplayer3.app.ui.settings.Strings.Bundle,
    rows: List<MenuRow>,
    selectedId: String,
    previewTitle: String,
    previewSubtitle: String?,
    previewArt: Any?,
) {
    SplitLayout(
        theme = theme,
        title = previewTitle,
        subtitle = previewSubtitle,
        art = previewArt,
        list = {
            SelectableList(
                theme = theme,
                rows = rows,
                selectedId = selectedId,
                modifier = Modifier.fillMaxSize()
            )
        }
    )
}

@Composable
private fun SplitLayout(
    theme: com.ipodplayer3.app.ui.theme.IpodTheme,
    title: String,
    subtitle: String?,
    art: Any?,
    list: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            list()
        }
        SplitPreviewPanel(theme = theme, title = title, subtitle = subtitle, artworkUri = art)
    }
}

@Composable
fun SongListScreen(
    vm: MainViewModel,
    songs: List<Song>,
    strings: com.ipodplayer3.app.ui.settings.Strings.Bundle,
) {
    if (songs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                strings.emptyLibrary,
                color = if (vm.theme.isDarkScreen) Color(0xFF98989D) else Color(0xFF6E6E73),
                fontSize = 12.sp
            )
        }
        return
    }
    SplitLayout(
        theme = vm.theme,
        title = strings.songs,
        subtitle = "${songs.size} ${strings.trackCount}",
        art = songs.getOrNull(vm.selectedIndex)?.albumArtUri,
        list = {
            SelectableList(
                theme = vm.theme,
                rows = songs.map { MenuRow(it.id.toString(), it.title, it.artist, artworkUri = it.albumArtUri) },
                selectedId = songs.getOrNull(vm.selectedIndex)?.id?.toString() ?: "",
                modifier = Modifier.fillMaxSize()
            )
        }
    )
}

@Composable
fun NowPlayingScreen(
    vm: MainViewModel,
    song: Song?,
    position: Long,
    duration: Long,
    strings: com.ipodplayer3.app.ui.settings.Strings.Bundle,
    isPlaying: Boolean,
) {
    val dark = vm.theme.isDarkScreen
    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(4.dp))
        coil.compose.AsyncImage(
            model = song?.albumArtUri,
            contentDescription = null,
            modifier = Modifier
                .height(110.dp)
                .width(110.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            song?.title ?: "—",
            color = if (dark) Color.White else Color.Black,
            fontSize = 13.sp,
            maxLines = 1
        )
        Text(
            song?.artist ?: "",
            color = if (dark) Color(0xFF98989D) else Color(0xFF6E6E73),
            fontSize = 11.sp,
            maxLines = 1
        )
        Text(
            song?.album ?: "",
            color = if (dark) Color(0xFF98989D) else Color(0xFF6E6E73),
            fontSize = 10.sp,
            maxLines = 1
        )
        Spacer(Modifier.height(12.dp))
        // progress
        val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
        ) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                drawRect(color = Color.Gray.copy(alpha = 0.35f))
                drawRect(color = vm.theme.accent, size = size.copy(width = size.width * progress))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
            Text(formatTime(position), color = if (dark) Color.LightGray else Color.DarkGray, fontSize = 10.sp)
            Text(formatTime(duration), color = if (dark) Color.LightGray else Color.DarkGray, fontSize = 10.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (isPlaying) "▶" else "❙❙",
            color = if (dark) Color.White else Color.Black,
            fontSize = 16.sp
        )
    }
}

@Composable
fun LyricsScreen(
    vm: MainViewModel,
    song: Song?,
    position: Long,
    strings: com.ipodplayer3.app.ui.settings.Strings.Bundle,
) {
    val dark = vm.theme.isDarkScreen
    val lyrics = vm.lyrics
    Column(
        Modifier
            .fillMaxSize()
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            song?.title ?: strings.nowPlaying,
            color = if (dark) Color.White else Color.Black,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(10.dp))
        if (lyrics == null || lyrics.lines.isEmpty()) {
            Text(
                strings.noLyrics,
                color = if (dark) Color(0xFF98989D) else Color(0xFF6E6E73),
                fontSize = 12.sp
            )
        } else {
            val active = lyrics.activeIndex(position.toInt())
            val window = 5
            val start = (active - window).coerceAtLeast(0)
            val end = (active + window).coerceAtMost(lyrics.lines.lastIndex)
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                for (i in start..end) {
                    val line = lyrics.lines[i]
                    val isActive = i == active
                    Text(
                        line.text,
                        color = when {
                            isActive && dark -> vm.theme.accent
                            isActive -> vm.theme.accent
                            dark -> Color(0xFF8E8E93)
                            else -> Color(0xFF6E6E73)
                        },
                        fontSize = if (isActive) 14.sp else 12.sp,
                        modifier = Modifier.padding(vertical = 3.dp)
                    )
                }
            }
        }
    }
}
