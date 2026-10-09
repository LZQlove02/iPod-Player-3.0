package com.ipodplayer3.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.core.content.ContextCompat
import com.ipodplayer3.app.data.model.Album
import com.ipodplayer3.app.data.model.Song
import com.ipodplayer3.app.data.player.GraphicEqualizerProcessor
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
import kotlinx.coroutines.flow.first

/** 播放列表详情页顶部操作行的 id（顺序必须与 ViewModel.PLAYLIST_ACTIONS 一致）。 */
private const val ACTION_ADD = "action-add"
private const val ACTION_REMOVE = "action-remove"
private const val ACTION_RENAME = "action-rename"
private const val ACTION_DELETE = "action-delete"

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // results only contains what was requested this round. If audio was
            // already granted and only the notification was missing, audio is
            // absent from the map — so judge by the real grant state instead.
            loadLibraryIfPermitted()
        }

    /** Persisted SAF grant for the user-picked lyrics folder. */
    private val lyricsDirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                vm.setLyricsDir(uri.toString())
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.onInit()
        ensurePermission()

        setContent {
            val theme = vm.theme
            com.ipodplayer3.app.ui.theme.IpodThemeProvider(theme) {
                MaterialTheme(
                    colorScheme = if (theme.isDarkScreen) darkColorScheme() else lightColorScheme()
                ) {
                    Surface(
                        Modifier.fillMaxSize(),
                        color = com.ipodplayer3.app.ui.theme.IpodColors.AppBackdrop
                    ) {
                        IpodApp(vm, onPickLyricsDir = { lyricsDirLauncher.launch(null) })
                    }
                }
            }
        }
    }

    /**
     * 「去系统设置授权」返回 App 时不走权限回调、也不重建 Activity（原生 Android），
     * 只有 onResume。不重查的话列表会一直空着、文案停在「未授予」。
     */
    override fun onResume() {
        super.onResume()
        loadLibraryIfPermitted()
    }

    private fun ensurePermission() {
        val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val needed = mutableListOf(audioPermission)
        // Media3's MediaSessionService posts a media notification; without this
        // grant on API 33+ the playback controls are hidden from the shade.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        // Load as soon as audio is granted — the notification grant is
        // best-effort and must not gate the library.
        loadLibraryIfPermitted()
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun loadLibraryIfPermitted() {
        vm.refreshPermissionState()
        if (vm.hasAudioPermission) {
            vm.loadLibrary()
        }
    }
}

@Composable
fun IpodApp(vm: MainViewModel, onPickLyricsDir: () -> Unit = {}) {
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

    // 系统返回键 = MENU：栈里还有上一页就退回，否则交给系统退出。
    // 输入法弹出（Search / 重命名）时必须先收键盘，否则用户还没输完就被 pop 走了。
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val view = androidx.compose.ui.platform.LocalView.current
    BackHandler(enabled = vm.stack.size > 1) {
        val insets = view.rootWindowInsets
        val imeShowing = insets != null &&
            androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(insets)
                .isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
        if (imeShowing) focusManager.clearFocus() else vm.pop()
    }

    // 一次性提示（加入播放列表 / 保存失败）。
    val toast = vm.toast
    LaunchedEffect(toast) {
        if (toast != null) {
            android.widget.Toast.makeText(context, toast, android.widget.Toast.LENGTH_SHORT).show()
            vm.toastShown()
        }
    }

    // SAF lyrics-folder picker: launched on demand from Settings.
    val lyricsDirRequest = vm.lyricsDirPickerRequest
    var lastLyricsDirRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(lyricsDirRequest) {
        if (lyricsDirRequest != lastLyricsDirRequest && lyricsDirRequest > 0) {
            lastLyricsDirRequest = lyricsDirRequest
            onPickLyricsDir()
        }
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
                        // 走 songLabel：标题为空串时与其它页面一样显示「未知歌曲」，
                        // 而不是给状态栏留一个空白。
                        title = current?.let { strings.songLabel(it.title) } ?: strings.appName
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
                    diameter = 208.dp,
                    soundEnabled = vm.clickSound,
                    continuous = vm.isSeekScreen(),
                    strings = strings,
                    onScroll = { vm.wheelScroll(it) },
                    onScrub = { vm.wheelScrub(it) },
                    onScrubEnd = { vm.wheelScrubEnd() },
                    onMenu = {
                        if (vm.screen !is Screen.MainMenu) vm.pop()
                    },
                    onPlayPause = { vm.togglePlayPause() },
                    onNext = {
                        if (vm.isSeekScreen()) vm.next()
                        else vm.wheelScroll(1)
                    },
                    onPrevious = {
                        if (vm.isSeekScreen()) vm.previous()
                        else vm.wheelScroll(-1)
                    },
                    onSelect = { vm.activate(strings.defaultPlaylistName) }
                )
            }
        )
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
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

    // 选项过渡：A 淡入 / B 滑页（默认）/ C 容器形变
    androidx.compose.animation.AnimatedContent(
        targetState = vm.screen,
        transitionSpec = {
            when (vm.transitionMode) {
                "A" -> {
                    (androidx.compose.animation.fadeIn(tween(220)) +
                        androidx.compose.animation.slideInVertically { it / 12 })
                        .togetherWith(
                            androidx.compose.animation.fadeOut(tween(180)) +
                                androidx.compose.animation.slideOutVertically { -it / 12 }
                        )
                }
                "C" -> {
                    (androidx.compose.animation.fadeIn(tween(280)) +
                        androidx.compose.animation.scaleIn(initialScale = 0.92f, animationSpec = tween(320)))
                        .togetherWith(
                            androidx.compose.animation.fadeOut(tween(200)) +
                                androidx.compose.animation.scaleOut(targetScale = 1.04f, animationSpec = tween(240))
                        )
                }
                else -> {
                    // B 滑页
                    val forward = targetState !is Screen.MainMenu
                    val dx = if (forward) 40 else -40
                    (androidx.compose.animation.slideInHorizontally { dx } +
                        androidx.compose.animation.fadeIn(tween(240)))
                        .togetherWith(
                            androidx.compose.animation.slideOutHorizontally { -dx / 2 } +
                                androidx.compose.animation.fadeOut(tween(180))
                        )
                }
            }
        },
        label = "screenTransition"
    ) { screen ->
    when (screen) {
        Screen.MainMenu -> MenuWithPreview(
            theme = theme,
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
            rows = listOf(
                MenuRow("songs", strings.songs, iconText = "♫"),
                MenuRow("albums", strings.albums, iconText = "▣"),
                MenuRow("artists", strings.artists, iconText = "☺"),
                MenuRow("cover", strings.coverFlow, iconText = "CF"),
                MenuRow("pl", strings.playlists, iconText = "≡"),
                MenuRow("add", strings.addToPlaylist, iconText = "+"),
            ),
            selectedId = listOf("songs", "albums", "artists", "cover", "pl", "add")
                .getOrNull(vm.selectedIndex) ?: "songs",
            previewTitle = strings.music,
            previewSubtitle = strings.tracks(songs.size),
            previewArt = current?.albumArtUri
        )

        Screen.Songs -> SongListScreen(vm, songs, strings)

        // 「加入播放列表」第一步：挑歌（与歌曲列表同样的行）
        Screen.PickSongForPlaylist -> SongListScreen(
            vm = vm,
            songs = songs,
            strings = strings,
            title = strings.pickSong
        )

        // 详情页「添加歌曲」入口：留在本页可连续加多首
        is Screen.AddSongsToPlaylist -> SongListScreen(
            vm = vm,
            songs = songs,
            strings = strings,
            title = strings.addSongToPlaylist
        )

        Screen.Albums -> {
            // 每次重组都 new 一份 List 会让 SelectableList 的 remember(rows) 失效，
            // 千张专辑时每转一格就重建整份 MenuRow + 重跑 indexOfFirst。
            val albumRows = remember(albums, strings) {
                albums.map {
                    MenuRow(
                        // 同一 albumId 理论上可能挂两个专辑名：key 必须含标题，否则 LazyColumn 撞 key。
                        "album-${it.id}-${it.title}",
                        strings.albumLabel(it.title),
                        strings.artistLabel(it.artist),
                        artworkUri = it.albumArtUri
                    )
                }
            }
            MenuWithPreview(
                theme = theme,
                rows = albumRows,
                selectedId = albums.getOrNull(vm.selectedIndex)
                    ?.let { "album-${it.id}-${it.title}" } ?: "",
                previewTitle = albums.getOrNull(vm.selectedIndex)
                    ?.let { strings.albumLabel(it.title) } ?: "",
                previewSubtitle = albums.getOrNull(vm.selectedIndex)
                    ?.let { strings.artistLabel(it.artist) },
                previewArt = albums.getOrNull(vm.selectedIndex)?.albumArtUri
            )
        }

        Screen.Artists -> {
            val artistRows = remember(artists, strings) {
                artists.map {
                    MenuRow(
                        it.id.toString(),
                        strings.artistLabel(it.name),
                        strings.tracks(it.songCount),
                        iconText = "☺"
                    )
                }
            }
            MenuWithPreview(
                theme = theme,
                rows = artistRows,
                selectedId = artists.getOrNull(vm.selectedIndex)?.id?.toString() ?: "",
                previewTitle = artists.getOrNull(vm.selectedIndex)
                    ?.let { strings.artistLabel(it.name) } ?: "",
                previewSubtitle = strings.artists,
                previewArt = current?.albumArtUri
            )
        }

        is Screen.AlbumDetail, is Screen.ArtistDetail -> {
            val isArtist = screen is Screen.ArtistDetail
            val detailRows = remember(vm.detailSongs, strings) {
                vm.detailSongs.map { s ->
                    MenuRow(s.id.toString(), strings.songLabel(s.title), strings.artistLabel(s.artist))
                }
            }
            SplitLayout(
                theme = theme,
                title = if (isArtist) strings.artistLabel(vm.detailTitle)
                else strings.albumLabel(vm.detailTitle),
                subtitle = strings.tracks(vm.detailSongs.size),
                art = vm.detailSongs.firstOrNull()?.albumArtUri,
                list = {
                    SelectableList(
                        theme = theme,
                        rows = detailRows,
                        selectedId = vm.detailSongs.getOrNull(vm.selectedIndex)?.id?.toString() ?: "",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            )
        }

        Screen.Playlists -> {
            val playlistRows = remember(playlists, songs, strings) {
                listOf(MenuRow("new", strings.createPlaylist, iconText = "+")) +
                    playlists.map {
                        MenuRow(
                            "pl-${it.id}",
                            it.name,
                            strings.tracks(vm.playlistTrackCount(it)),
                            iconText = "≡"
                        )
                    }
            }
            MenuWithPreview(
                theme = theme,
                rows = playlistRows,
                // 索引 0 是「新建」，其余按下标取列表项；不要再构造一遍完整 MenuRow 列表。
                selectedId = if (vm.selectedIndex == 0) "new"
                else playlists.getOrNull(vm.selectedIndex - 1)?.let { "pl-${it.id}" } ?: "new",
                previewTitle = strings.playlists,
                previewSubtitle = if (playlists.isEmpty()) strings.noPlaylistYet else "${playlists.size}",
                previewArt = current?.albumArtUri
            )
        }

        // 「加入播放列表」第二步：选目标列表（第 0 项 = 新建并加入）
        is Screen.PickPlaylist -> {
            val pickRows = remember(playlists, strings) {
                listOf(MenuRow("new", strings.newPlaylistAndAdd, iconText = "+")) +
                    playlists.map { MenuRow("pl-${it.id}", it.name, iconText = "≡") }
            }
            MenuWithPreview(
                theme = theme,
                rows = pickRows,
                selectedId = if (vm.selectedIndex == 0) "new"
                else playlists.getOrNull(vm.selectedIndex - 1)?.let { "pl-${it.id}" } ?: "new",
                previewTitle = strings.selectPlaylist,
                previewSubtitle = if (playlists.isEmpty()) strings.noPlaylistYet else null,
                previewArt = null
            )
        }

        Screen.PlaylistDetail -> {
            // 前 4 行是操作，后面才是歌曲；索引与 ViewModel.PLAYLIST_ACTIONS 对齐。
            val rows = remember(vm.detailSongs, strings) {
                val actionRows = listOf(
                    MenuRow(ACTION_ADD, strings.addSongToPlaylist, iconText = "+"),
                    MenuRow(ACTION_REMOVE, strings.removeSongFromPlaylist, iconText = "−"),
                    MenuRow(ACTION_RENAME, strings.renamePlaylist, iconText = "✎"),
                    MenuRow(ACTION_DELETE, strings.deletePlaylist, iconText = "🗑"),
                )
                actionRows + vm.detailSongs.map {
                    MenuRow("song-${it.id}", strings.songLabel(it.title), strings.artistLabel(it.artist))
                }
            }
            SplitLayout(
                theme = theme,
                title = vm.detailTitle,
                // 空列表给一句明确的话，而不是干巴巴的「0 首」。
                subtitle = if (vm.detailSongs.isEmpty()) strings.emptyPlaylist
                else strings.tracks(vm.detailSongs.size),
                art = vm.detailSongs.firstOrNull()?.albumArtUri,
                list = {
                    SelectableList(
                        theme = theme,
                        rows = rows,
                        selectedId = rows.getOrNull(vm.selectedIndex)?.id ?: ACTION_ADD,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            )
        }

        is Screen.RemoveFromPlaylist -> {
            val target = screen
            SplitLayout(
                theme = theme,
                title = target.title,
                subtitle = strings.removeSongFromPlaylist,
                art = vm.detailSongs.firstOrNull()?.albumArtUri,
                list = {
                    val removeRows = remember(vm.detailSongs, strings) {
                        vm.detailSongs.map {
                            MenuRow("song-${it.id}", strings.songLabel(it.title), strings.artistLabel(it.artist))
                        }
                    }
                    SelectableList(
                        theme = theme,
                        rows = removeRows,
                        selectedId = vm.detailSongs.getOrNull(vm.selectedIndex)
                            ?.let { "song-${it.id}" } ?: "",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            )
        }

        is Screen.RenamePlaylist -> {
            Column(Modifier.fillMaxSize()) {
                androidx.compose.material3.OutlinedTextField(
                    value = vm.renameDraft,
                    onValueChange = { vm.renameDraft = it },
                    singleLine = true,
                    placeholder = { Text(strings.renameHint, fontSize = 11.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = if (dark) Color.White else Color.Black,
                        fontSize = 12.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
                SelectableList(
                    theme = theme,
                    rows = listOf(
                        MenuRow("save", strings.save, iconText = "✓"),
                        MenuRow("cancel", strings.cancel, iconText = "×"),
                    ),
                    selectedId = if (vm.selectedIndex == 0) "save" else "cancel",
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        is Screen.DeletePlaylist -> {
            val target = screen
            MenuWithPreview(
                theme = theme,
                rows = listOf(
                    MenuRow("confirm", strings.confirm, iconText = "🗑"),
                    MenuRow("cancel", strings.cancel, iconText = "×"),
                ),
                selectedId = if (vm.selectedIndex == 0) "confirm" else "cancel",
                previewTitle = strings.confirmDeletePlaylist(target.title),
                previewSubtitle = null,
                previewArt = null
            )
        }

        Screen.Search -> {
            Column(Modifier.fillMaxSize()) {
                // 直接绑 vm.searchQuery：再维护一份 remember 会在别处改查询时不同步。
                // 输入法组合期会连续触发 onValueChange，逐键搜索对上千首的曲库是浪费：
                // 停手 250ms 之后再查。
                LaunchedEffect(vm.searchQuery) {
                    if (vm.searchQuery.isNotEmpty()) {
                        kotlinx.coroutines.delay(250)
                    }
                    vm.runSearch()
                }
                androidx.compose.material3.OutlinedTextField(
                    value = vm.searchQuery,
                    onValueChange = { q -> vm.searchQuery = q },
                    singleLine = true,
                    placeholder = { Text(strings.searchHint, fontSize = 11.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = if (dark) Color.White else Color.Black,
                        fontSize = 12.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
                if (vm.searchQuery.isBlank()) {
                    Text(
                        strings.searchHint,
                        color = if (dark) Color(0xFF98989D) else Color(0xFF6E6E73),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(10.dp)
                    )
                } else {
                    val searchRows = remember(vm.searchResults, strings) {
                        vm.searchResults.map {
                            MenuRow(
                                "song-${it.id}",
                                strings.songLabel(it.title),
                                strings.artistLabel(it.artist),
                                artworkUri = it.albumArtUri
                            )
                        }
                    }
                    SelectableList(
                        theme = theme,
                        rows = searchRows,
                        selectedId = vm.searchResults.getOrNull(vm.selectedIndex)
                            ?.let { "song-${it.id}" } ?: "",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        Screen.CoverFlow -> CoverFlow(
            theme = theme,
            albums = albums,
            selectedIndex = vm.coverIndex,
            flipped = vm.coverFlipped,
            onIndexChange = { vm.moveCoverTo(it) },
            onFlip = { vm.flipCover() },
            onOpenAlbum = { vm.openAlbum(it) },
            tracks = vm.coverTracks
        )

        Screen.NowPlaying -> NowPlayingScreen(
            vm = vm,
            song = current,
            position = position,
            duration = duration,
            strings = strings,
            isPlaying = isPlaying
        )

        Screen.Lyrics -> LyricsScreen(vm, current, position, strings, isPlaying)

        Screen.Settings -> {
            // collectAsState 必须留在组合体里读，不能塞进 remember 工厂。
            val shOn = vm.shuffle.collectAsState().value
            val rep = vm.repeat.collectAsState().value
            val lyricDir = vm.lyricsDirUri.collectAsState().value
            val rows = remember(
                strings, vm.eqEnabled, vm.language, vm.theme.id,
                vm.clickSound, vm.vibrate, shOn, rep, vm.transitionMode, lyricDir
            ) {
                listOf(
                    MenuRow("eq", strings.equalizer, if (vm.eqEnabled) strings.on else strings.off, iconText = "EQ"),
                    MenuRow("lang", strings.language, if (vm.language == AppLanguage.ZH) strings.zh else strings.en, iconText = "文"),
                    MenuRow("theme", strings.theme, when (vm.theme.id) {
                        com.ipodplayer3.app.ui.theme.IpodThemeId.BLACK -> strings.black
                        com.ipodplayer3.app.ui.theme.IpodThemeId.U2 -> strings.u2
                        com.ipodplayer3.app.ui.theme.IpodThemeId.SILVER -> strings.silver
                    }, iconText = "◐"),
                    MenuRow("sound", strings.clickSound, if (vm.clickSound) strings.on else strings.off, iconText = "♪"),
                    MenuRow("vib", strings.vibrate, if (vm.vibrate) strings.on else strings.off, iconText = "≈"),
                    MenuRow("sh", strings.shuffle, if (shOn) strings.on else strings.off, iconText = "🔀"),
                    MenuRow("rep", strings.repeat, when (rep) {
                        com.ipodplayer3.app.data.player.RepeatMode.ONE -> strings.one
                        com.ipodplayer3.app.data.player.RepeatMode.ALL -> strings.all
                        else -> strings.none
                    }, iconText = "🔁"),
                    MenuRow(
                        "fx",
                        strings.transitionLabel,
                        when (vm.transitionMode) {
                            "A" -> strings.transitionFade
                            "C" -> strings.transitionMorph
                            else -> strings.transitionSlide
                        },
                        iconText = "FX"
                    ),
                    MenuRow("scan", strings.rescan, iconText = "↻"),
                    MenuRow(
                        "lyricdir",
                        strings.lyricsFolder,
                        if (lyricDir.isBlank()) strings.lyricsFolderNone
                        else strings.lyricsFolderSet,
                        iconText = "LRC"
                    ),
                )
            }
            MenuWithPreview(
                theme = theme,
                rows = rows,
                selectedId = listOf("eq", "lang", "theme", "sound", "vib", "sh", "rep", "fx", "scan", "lyricdir")
                    .getOrNull(vm.selectedIndex) ?: "eq",
                previewTitle = strings.settings,
                previewSubtitle = null,
                previewArt = null
            )
        }

        Screen.Eq -> {
            val labels = GraphicEqualizerProcessor.CENTER_HZ.map { hz ->
                if (hz >= 1000.0) "${(hz / 1000.0).toInt()}k" else hz.toInt().toString()
            }
            val presetLabel = when (vm.eqPreset) {
                EqPreset.FLAT -> strings.flat
                EqPreset.POP -> strings.pop
                EqPreset.ROCK -> strings.rock
                EqPreset.CLASSICAL -> strings.classical
                EqPreset.JAZZ -> strings.jazz
                EqPreset.BASS -> strings.bassBoost
            }
            val rows = listOf(
                MenuRow("en", strings.equalizer, if (vm.eqEnabled) strings.on else strings.off, iconText = "EQ"),
                MenuRow("preset", strings.equalizer + " · " + presetLabel, strings.preset, iconText = "♪"),
            ) + labels.mapIndexed { i, label ->
                val g = vm.eqBands.getOrElse(i) { 0f }
                // 指定 Locale：部分地区默认格式会用逗号做小数点。
                MenuRow(
                    "b$i",
                    "$label Hz",
                    String.format(java.util.Locale.US, "%+.1f dB", g),
                    iconText = " eq"
                )
            }
            MenuWithPreview(
                theme = theme,
                rows = rows,
                selectedId = (listOf("en", "preset") + labels.indices.map { "b$it" })
                    .getOrNull(vm.selectedIndex) ?: "en",
                previewTitle = strings.equalizer,
                previewSubtitle = if (vm.eqEnabled) "ON" else "OFF",
                previewArt = null
            )
        }

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
    } // when
    } // AnimatedContent
}

@Composable
private fun MenuWithPreview(
    theme: com.ipodplayer3.app.ui.theme.IpodTheme,
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
        // LCD 分栏：列表 : 预览 = 4 : 6（列表 40%，预览 60%）
        Box(
            Modifier
                .weight(4f)
                .fillMaxHeight()
        ) {
            list()
        }
        com.ipodplayer3.app.ui.components.SplitDivider(theme)
        Box(
            Modifier
                .weight(6f)
                .fillMaxHeight()
        ) {
            SplitPreviewPanel(
                theme = theme,
                title = title,
                subtitle = subtitle,
                artworkUri = art,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun SongListScreen(
    vm: MainViewModel,
    songs: List<Song>,
    strings: com.ipodplayer3.app.ui.settings.Strings.Bundle,
    title: String = strings.songs,
) {
    if (songs.isEmpty()) {
        val context = LocalContext.current
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                // 权限没给和「真的没有歌」是两件事，文案不该共用一句。
                if (vm.hasAudioPermission) strings.emptyLibrary else strings.noPermission,
                color = if (vm.theme.isDarkScreen) Color(0xFF98989D) else Color(0xFF6E6E73),
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            if (!vm.hasAudioPermission) {
                Spacer(Modifier.height(12.dp))
                Text(
                    strings.grantPermission,
                    color = vm.theme.accent,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable {
                        // 永久拒绝后 requestPermissions 不会再弹窗，只能引导去设置页。
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", context.packageName, null)
                            )
                        )
                    }
                )
            }
        }
        return
    }
    // 每滚一格就重建整份 MenuRow（曲库上万首时是明确的掉帧来源）→ 按曲库缓存。
    // strings 也要进 key：切换语言后空标题行仍会显示旧语言的「未知歌曲」。
    val rows = remember(songs, strings) {
        songs.map {
            MenuRow(
                "song-${it.id}",
                strings.songLabel(it.title),
                strings.artistLabel(it.artist),
                artworkUri = it.albumArtUri
            )
        }
    }
    val selected = songs.getOrNull(vm.selectedIndex)
    SplitLayout(
        theme = vm.theme,
        title = title,
        subtitle = strings.tracks(songs.size),
        art = selected?.albumArtUri,
        list = {
            SelectableList(
                theme = vm.theme,
                rows = rows,
                selectedId = selected?.let { "song-${it.id}" } ?: "",
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
        com.ipodplayer3.app.ui.components.AlbumArtImage(
            uri = song?.albumArtUri,
            title = song?.let { strings.songLabel(it.title) } ?: "♪",
            size = 120,
            modifier = Modifier.size(120.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            song?.let { strings.songLabel(it.title) } ?: "—",
            color = if (dark) Color.White else Color.Black,
            fontSize = 13.sp,
            maxLines = 1
        )
        Text(
            song?.let { strings.artistLabel(it.artist) } ?: "",
            color = if (dark) Color(0xFF98989D) else Color(0xFF6E6E73),
            fontSize = 11.sp,
            maxLines = 1
        )
        Text(
            song?.let { strings.albumLabel(it.album) } ?: "",
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
        // Action semantics: the glyph shows what pressing the wheel would do.
        // Pause is drawn as two bars rather than a text glyph (U+2759 has no
        // coverage in several CJK fonts and silently rendered blank).
        val iconColor = if (dark) Color.White else Color.Black
        if (isPlaying) {
            androidx.compose.foundation.Canvas(Modifier.size(width = 13.dp, height = 16.dp)) {
                val barW = size.width * 0.30f
                drawRect(iconColor, size = androidx.compose.ui.geometry.Size(barW, size.height))
                drawRect(
                    iconColor,
                    topLeft = androidx.compose.ui.geometry.Offset(size.width - barW, 0f),
                    size = androidx.compose.ui.geometry.Size(barW, size.height)
                )
            }
        } else {
            Text("▶", color = iconColor, fontSize = 16.sp)
        }
    }
}

@Composable
fun LyricsScreen(
    vm: MainViewModel,
    song: Song?,
    position: Long,
    strings: com.ipodplayer3.app.ui.settings.Strings.Bundle,
    isPlaying: Boolean,
) {
    val dark = vm.theme.isDarkScreen
    val lyrics = vm.lyrics
    // 直接订阅播放进度：position 参数经过 AnimatedContent 闭包后
    // 可能长期不刷新，导致扫光看起来「一动不动」。
    val livePosition by vm.positionMs.collectAsState()
    val pos = if (livePosition > 0L) livePosition else position
    // 亮/暗两色：当前句扫光用；已唱句整句用亮色，未唱句用暗色。
    val litColor = if (dark) Color(0xFFF2F2F7) else Color(0xFF1D1D1F)
    val dimColor = if (dark) Color(0xFF48484A) else Color(0xFF8E8E93)
    Column(
        Modifier
            .fillMaxSize()
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            song?.let { strings.songLabel(it.title) } ?: strings.nowPlaying,
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
            val active = lyrics.activeIndex(pos)
            val listState = rememberLazyListState()
            val density = LocalDensity.current
            // 记录上一次居中的行：seek 会一次跳多行，相邻换行只差 1，
            // 用它区分「瞬跳」与「动画」。原来拿 firstVisibleItemIndex 比，
            // 那是视口顶行、天然比 active 小好几行，条件恒成立 → 永远瞬跳。
            var lastCentered by remember(lyrics) { mutableIntStateOf(-1) }

            // 播放进度每 400ms 才采样一次；扫光直接拿它算会一顿一顿地跳。
            // 这里把「当前行」的进度补成逐帧连续值，行选中与滚动仍按采样值走。
            val livePosition = rememberLivePosition(pos, isPlaying)

            // 当前句居中 + 平滑滚动。
            // scrollOffset 的正负：KDoc 明确 "positive offset will scroll the
            // item further upward (taking it partly offscreen)"，即正值把该行
            // 继续向上推出屏幕；要让行中心落在视口中心必须传负值。
            LaunchedEffect(active, lyrics) {
                if (active < 0) return@LaunchedEffect
                // 首帧布局前 layoutInfo.viewport* 为 0；等它就绪再定位。
                // 否则本 effect 因 key 未变不会重跑，首句永远停在列表顶部。
                val viewport = snapshotFlow {
                    listState.layoutInfo
                        .let { it.viewportEndOffset - it.viewportStartOffset }
                }.first { it > 0 }
                val linePx = with(density) { LYRIC_LINE_HEIGHT.toPx() }
                val offset = -((viewport - linePx) / 2).toInt()
                val jump = lastCentered < 0 ||
                    kotlin.math.abs(lastCentered - active) > 1
                lastCentered = active
                if (jump) {
                    listState.scrollToItem(active, offset)
                } else {
                    listState.animateScrollToItem(active, offset)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(lyrics.lines.size) { i ->
                    val line = lyrics.lines[i]
                    val isActive = i == active
                    val isSung = i < active
                    val fontSize = if (isActive) 14.sp else 12.sp
                    // 当前句行内进度 0..1。只有当前行订阅逐帧进度，
                    // 其余行不读它，因此不会每帧重组整列。
                    val linePos = if (isActive) livePosition.value else pos
                    val sweep = if (isActive) lyrics.lineProgress(linePos, i) else 0f
                    KaraokeLine(
                        text = line.text,
                        progress = if (isActive) sweep else if (isSung) 1f else 0f,
                        litColor = litColor,
                        dimColor = dimColor,
                        fontSize = fontSize,
                        modifier = Modifier
                            .height(LYRIC_LINE_HEIGHT)
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                    )
                }
            }
        }
    }
}

/** 歌词单行高度：字号变化也占满，滚动居中按它计算。 */
private val LYRIC_LINE_HEIGHT = 34.dp

/**
 * 把 400ms 轮询出的播放进度补成逐帧连续值。
 *
 * 播放中进度随真实时间 1:1 前进，拿最近一次采样按「已经过了多少帧时间」外推即可，
 * 既不跳格也不落后于实测；暂停 / 拖动时采样本身就是真值，直接用、不外推。
 *
 * 返回 [State] 而不是 Long：本作用域只创建不读取，因此不会每帧重组；
 * 只有真正订阅 `.value` 的那当前一行才会动。
 */
@Composable
private fun rememberLivePosition(sampled: Long, playing: Boolean): State<Long> {
    // longState 而非 mutableStateOf：这个值每帧写一次，避免每帧装箱一个 Long。
    val live = remember { mutableLongStateOf(sampled) }
    LaunchedEffect(sampled, playing) {
        live.longValue = sampled
        if (!playing) return@LaunchedEffect
        // 时间锚点只在「采样刚到」时建立一次，之后不再更新：
        // 每帧算的是锚点到现在的累计时长，从采样点线性外推。
        // 若每帧都把锚点推到当前帧，增量恒等于一帧，外推值会永远卡在 sampled+16ms。
        var anchorNanos = -1L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (anchorNanos < 0L) {
                    anchorNanos = now
                } else {
                    live.longValue = sampled + (now - anchorNanos) / 1_000_000L
                }
            }
        }
    }
    return live
}

/**
 * 卡拉OK单行：逐字独立着色。
 *
 * 不用 brush / mask / 双层叠加——那些在 material3.Text 与部分 WebView 上
 * 会静默失效（color 合并掉 brush、mask 不渲染），看起来就是「没有动画」。
 * 这里按 progress 给每个字单独算颜色，任何环境都必定可见。
 */
@Composable
private fun KaraokeLine(
    text: String,
    progress: Float,
    litColor: Color,
    dimColor: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
) {
    val p = progress.coerceIn(0f, 1f)
    val annotated = remember(text, p, litColor, dimColor) {
        buildAnnotatedString {
            val chars = text.toList()
            val n = chars.size
            if (n == 0) return@buildAnnotatedString
            // 软边宽度（以字为单位），约 1.5 字
            val soft = 1.5f
            chars.forEachIndexed { i, c ->
                // 该字中心的点亮时刻
                val center = (i + 0.5f) / n
                val x = (p - center) / (soft / n)
                var t = when {
                    x <= -1f -> 0f
                    x >= 1f -> 1f
                    else -> (x + 1f) / 2f
                }
                t = t * t * (3f - 2f * t) // smoothstep
                withStyle(SpanStyle(color = lerpColor(dimColor, litColor, t))) {
                    append(c)
                }
            }
        }
    }
    BasicText(
        text = annotated,
        style = TextStyle(fontSize = fontSize, textAlign = TextAlign.Center),
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier,
    )
}

/** 颜色线性插值，避免依赖 androidx.compose.ui.graphics.lerp 的可见性。 */
private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val k = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * k,
        green = a.green + (b.green - a.green) * k,
        blue = a.blue + (b.blue - a.blue) * k,
        alpha = a.alpha + (b.alpha - a.alpha) * k,
    )
}
