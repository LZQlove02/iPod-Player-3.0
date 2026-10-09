package com.ipodplayer3.app.ui

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ipodplayer3.app.IPodApplication
import com.ipodplayer3.app.data.lyrics.LyricParser
import com.ipodplayer3.app.data.model.Album
import com.ipodplayer3.app.data.model.Lyrics
import com.ipodplayer3.app.data.model.Playlist
import com.ipodplayer3.app.data.model.Song
import com.ipodplayer3.app.data.player.GraphicEqualizerProcessor
import com.ipodplayer3.app.data.player.RepeatMode
import com.ipodplayer3.app.ui.settings.AppLanguage
import com.ipodplayer3.app.ui.settings.EqPreset
import com.ipodplayer3.app.ui.settings.SettingsRepository
import com.ipodplayer3.app.ui.settings.Strings
import com.ipodplayer3.app.ui.theme.IpodTheme
import com.ipodplayer3.app.ui.theme.SilverTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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

    // ---- 播放列表编辑流程 ----
    /** 选一首歌，准备加入某个播放列表。 */
    data object PickSongForPlaylist : Screen
    /** 选目标播放列表（第 0 项 = 新建并加入）。 */
    data class PickPlaylist(val songId: Long) : Screen
    /** 在某个播放列表里选一首歌移除。 */
    data class RemoveFromPlaylist(val playlistId: Long, val title: String) : Screen
    /** 重命名（触摸输入框 + 滚轮选「保存」）。 */
    data class RenamePlaylist(val playlistId: Long) : Screen
    /** 删除前确认。 */
    data class DeletePlaylist(val playlistId: Long, val title: String) : Screen
    /** 在某个播放列表里挑歌加入（详情页入口，可连续加多首）。 */
    data class AddSongsToPlaylist(val playlistId: Long) : Screen
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IPodApplication
    private val musicRepo = app.musicRepository
    private val playlistStore = app.playlistStore
    private val player = app.playerController
    val settings = SettingsRepository(application)

    var screen by mutableStateOf<Screen>(Screen.MainMenu)
    var stack by mutableStateOf(listOf<Screen>(Screen.MainMenu))
        private set

    var selectedIndex by mutableIntStateOf(0)
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
    /** A 淡入 / B 滑页 / C 形变 */
    var transitionMode by mutableStateOf("B")
    var clickSound by mutableStateOf(true)
    var vibrate by mutableStateOf(true)
    var eqEnabled by mutableStateOf(false)
    var eqPreset by mutableStateOf(EqPreset.FLAT)
    var eqBands by mutableStateOf(List(GraphicEqualizerProcessor.BAND_COUNT) { 0f })

    var lyrics by mutableStateOf<Lyrics?>(null)
    var searchQuery by mutableStateOf("")

    /** 一次性提示（「已加入…」「保存失败」），由界面消费后清空。 */
    var toast by mutableStateOf<String?>(null)
        private set

    fun showToast(message: String) {
        toast = message
    }

    fun toastShown() {
        toast = null
    }

    private val _lyricsDirUri = MutableStateFlow("")
    /** SAF tree URI for the user-picked lyrics folder, "" when unset. */
    val lyricsDirUri: StateFlow<String> = _lyricsDirUri.asStateFlow()

    /**
     * Incremented when the user asks to pick a lyrics folder. MainActivity
     * observes this and launches the SAF picker (the launcher cannot live here).
     */
    var lyricsDirPickerRequest by mutableIntStateOf(0)
        private set

    fun requestLyricsDirPicker() {
        lyricsDirPickerRequest++
    }

    fun setLyricsDir(uri: String) {
        viewModelScope.launch {
            settings.setLyricsDirUri(uri)
            _lyricsDirUri.value = uri
            // Reload current song's lyrics through the new folder.
            loadLyricsFor(player.currentSong.value)
        }
    }

    var coverIndex by mutableIntStateOf(0)
    var coverFlipped by mutableStateOf(false)
    var coverTracks by mutableStateOf<List<String>>(emptyList())
    var detailSongs by mutableStateOf<List<Song>>(emptyList())
        private set
    var detailTitle by mutableStateOf("")
        private set

    /**
     * 搜索结果单独一份，不与详情页共用 [detailSongs]。
     * 共用时只能靠 `screen == Screen.Search` 判定防串页，任何一处漏判就会
     * 把搜索结果塞进专辑/播放列表详情，或反过来。
     */
    var searchResults by mutableStateOf<List<Song>>(emptyList())
        private set

    /** 当前详情页对应的播放列表 id（详情页顶部的操作行要用）。 */
    private var detailPlaylistId by mutableStateOf<Long?>(null)

    /** 重命名页的编辑草稿。 */
    var renameDraft by mutableStateOf("")

    /**
     * 界面文案。ViewModel 自己也要用（提示语、默认列表名），所以这里持有一份
     * 随语言开关刷新的实例；界面侧直接读它即可，不必各自构造。
     */
    var strings by mutableStateOf(Strings.of(application, AppLanguage.ZH))
        private set

    private fun refreshStrings() {
        strings = Strings.of(getApplication(), language)
    }

    private val savedFailedMessage: String get() = strings.playlistSaveFailed

    private fun addedMessage(playlistName: String): String = strings.addedTo(playlistName)

    private var coverTracksJob: Job? = null

    fun loadCoverTracks() {
        coverTracksJob?.cancel()
        val album = albums.value.getOrNull(coverIndex)
        if (album == null) {
            coverTracks = emptyList()
            return
        }
        val albumKey = album.id to album.title
        coverTracksJob = viewModelScope.launch {
            val tracks = musicRepo.songsByAlbum(album.id, album.title).map { it.title }
            // 快速翻面/换封面时上一次查询可能更晚返回；只接受仍是当前封面的结果，
            // 否则背面会张冠李戴显示上一张专辑的曲目。
            val current = albums.value.getOrNull(coverIndex)
            if (current != null && (current.id to current.title) == albumKey && coverFlipped) {
                coverTracks = tracks
            }
        }
    }

    /**
     * 翻阅封面的唯一入口（滚轮中心键与直接点击封面都走这里）。
     * 直接改 coverFlipped 会漏掉 loadCoverTracks()，背面就是一张空白卡片。
     */
    fun flipCover() {
        coverFlipped = !coverFlipped
        if (coverFlipped) loadCoverTracks() else coverTracks = emptyList()
    }

    /**
     * 合上封面并丢掉背面曲目。
     * 只翻 coverFlipped 不清 coverTracks 的话，背面残留的仍是上一次加载的曲目表，
     * 下次翻面、加载尚未返回的那一瞬就会显示成别的专辑。
     */
    private fun closeCover() {
        coverFlipped = false
        coverTracks = emptyList()
        coverTracksJob?.cancel()
    }

    /**
     * 换封面必然合上，并清掉上一张专辑的背面曲目（否则会张冠李戴）。
     * 名字不能叫 setCoverIndex —— 会和 `var coverIndex` 生成的 JVM setter 撞签名。
     */
    fun moveCoverTo(index: Int) {
        if (index == coverIndex) return
        coverIndex = index
        coverFlipped = false
        coverTracks = emptyList()
    }

    /**
     * Activity 每次重建（深色模式、字体大小、语言、分屏等配置变化）都会再调一次
     * onCreate → onInit，而 ViewModel 是存活的：没有这道闸门就会重复订阅 DataStore、
     * 叠加多个 while(true) 轮询协程。
     */
    private var initialized = false

    /** MediaStore 连发通知时的重扫防抖句柄。 */
    private var rescanJob: Job? = null

    fun onInit() {
        if (initialized) return
        initialized = true
        refreshStrings()
        player.connect()
        viewModelScope.launch {
            settings.language.distinctUntilChanged().collect {
                language = it
                refreshStrings()
            }
        }
        viewModelScope.launch {
            settings.themeName.distinctUntilChanged().collect { name ->
                theme = IpodTheme.of(name)
            }
        }
        viewModelScope.launch {
            settings.transitionMode.distinctUntilChanged().collect { transitionMode = it }
        }
        viewModelScope.launch {
            settings.clickSound.distinctUntilChanged().collect {
                clickSound = it
                com.ipodplayer3.app.ui.device.ClickSound.setEnabled(it)
            }
        }
        viewModelScope.launch {
            settings.vibrate.distinctUntilChanged().collect {
                vibrate = it
                com.ipodplayer3.app.ui.device.Haptic.setEnabled(it)
            }
        }
        viewModelScope.launch {
            settings.eqEnabled.distinctUntilChanged().collect {
                eqEnabled = it
                player.setEqEnabled(it)
            }
        }
        viewModelScope.launch {
            // 这里只同步标签。DataStore 任意一项设置变化都会让所有 Flow 重新发射，
            // 若在此处套用预设，用户手调的 10 段曲线会被无声抹掉。
            settings.eqPreset.distinctUntilChanged().collect { eqPreset = it }
        }
        viewModelScope.launch {
            // 恢复上次保存的曲线；从未保存过曲线时才套用已选预设。
            val saved = parseEqBands(settings.eqBands.first())
            if (saved != null) {
                eqBands = saved.toList()
                player.setEqBands(saved)
            } else {
                player.applyEqPreset(settings.eqPreset.first().name.lowercase())
                eqBands = player.eqGains().toList()
            }
        }
        viewModelScope.launch {
            settings.lyricsDirUri.distinctUntilChanged().collect { _lyricsDirUri.value = it }
        }
        // 启动恢复 shuffle / repeat：与其它设置一样落盘，否则重启后一律回 OFF。
        // 只在变化时推给 player，避免 DataStore 任意一项变更时反复回灌。
        viewModelScope.launch {
            settings.shuffle.distinctUntilChanged().collect { player.setShuffle(it) }
        }
        viewModelScope.launch {
            settings.repeatMode.distinctUntilChanged().collect { raw ->
                val mode = runCatching { RepeatMode.valueOf(raw) }
                    .getOrDefault(RepeatMode.OFF)
                player.setRepeat(mode)
            }
        }
        viewModelScope.launch {
            playlistStore.load()
        }
        viewModelScope.launch {
            // ContentObserver 只负责失效缓存；这里订阅信号真正重查。
            // 批量拷歌时 onChange 会连发，用 Job 防抖合并成一次 loadAll。
            musicRepo.invalidated.collect {
                rescanJob?.cancel()
                rescanJob = launch {
                    delay(400)
                    loadLibrary()
                }
            }
        }
        viewModelScope.launch {
            // Back off to 1s while idle so a paused player is not polled at 2.5 Hz.
            // 间隔在「本轮 sleep 前」判定，暂停后下一轮立刻降到 1000ms。
            while (true) {
                player.pollPosition()
                delay(if (isPlaying.value) 400L else 1000L)
            }
        }
    }

    fun loadLibrary(force: Boolean = false) {
        viewModelScope.launch {
            val list = musicRepo.loadAll(force)
            // loadAll 缓存命中时返回同一份实例，所以这里的「变了没」是可靠的：
            // 没变就不必再对整库跑一遍专辑/艺术家聚合（onResume 每次都会走到这）。
            val changed = list != _songs.value
            _songs.value = list
            if (changed) {
                _albums.value = musicRepo.albums(list)
                _artists.value = musicRepo.artists(list)
                // 曲库换了一批歌：详情页与搜索结果都是快照，停在这些页面上必须补刷，
                // 否则歌删了还留着、新歌搜不到，而且不会自愈。
                refreshSnapshotScreens()
                // 封面页的封面下标与背面曲目同样是快照：只在翻着面时才需要重取，
                // 合着的封面本来就没有背面内容，也不该打断用户正在翻阅的光标。
                if (coverFlipped) {
                    coverIndex = coverIndex.coerceIn(0, (_albums.value.size - 1).coerceAtLeast(0))
                    loadCoverTracks()
                }
            }
            // 进程被杀后 Service 可能还在放：用 mediaId 反查曲库，把当前曲目补回来。
            // MediaController 可能尚未连上；restoreQueue 会自己排队，连上后再补。
            // 解析器必须走 findById（原始表）：list 是去重视图，被合并的那条会漏。
            if (player.currentSong.value == null) player.restoreQueue(musicRepo::findById)
        }
    }

    /**
     * 曲库重扫后，把「内容是曲库快照」的页面各自重算一遍。
     * 只有停在对应页面时才动手：别的页面的 detailSongs 会在自己 push 时重新加载。
     */
    private suspend fun refreshSnapshotScreens() {
        when (val s = screen) {
            Screen.PlaylistDetail -> detailPlaylistId?.let { reloadDetailSongs(it) }
            is Screen.AlbumDetail -> detailSongs = musicRepo.songsByAlbum(s.albumId, s.title)
            is Screen.ArtistDetail -> detailSongs = musicRepo.songsByArtist(s.artist)
            // 同一个查询重跑，只是结果有增减：别动光标。
            Screen.Search -> runSearch(resetCursor = false)
            else -> Unit
        }
    }

    /** Rescan MediaStore and rebuild the library. */
    fun refreshLibrary() = loadLibrary(force = true)

    private fun audioPermissionName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun checkAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), audioPermissionName()) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 曲库为空时用来区分「没授权」和「确实没有音乐」——两者文案不同。
     *
     * 必须是 Compose State：从系统设置页授权回来时列表仍为空，StateFlow 不会发射，
     * 只有这个标记变了，「去系统设置授权」那块文案才会换成「曲库是空的」。
     */
    var hasAudioPermission by mutableStateOf(checkAudioPermission())
        private set

    /** 从系统设置页返回 / 权限回调时刷新标记。 */
    fun refreshPermissionState() {
        hasAudioPermission = checkAudioPermission()
    }

    // ---- navigation ----
    /**
     * 与 [stack] 平行的光标快照，长度恒等于 stack.size。
     * [savedSelections][i] = 返回 stack[i] 时应恢复的行号。
     * 进入子页前把当前行号写进末位，再给子页追加 0 —— MENU 返回时原位恢复，
     * 这是真机 iPod 的手感；一律归零会把用户丢回第 0 行（常常是动作行）。
     */
    private var savedSelections = listOf(0)

    fun push(screen: Screen) {
        wheelScrubEnd()
        savedSelections = savedSelections.dropLast(1) + selectedIndex + 0
        stack = stack + screen
        this.screen = screen
        selectedIndex = 0
        closeCover()
    }

    fun pop() {
        if (stack.size <= 1) return
        wheelScrubEnd()
        // 先丢掉子页那一格，末位就是离开父页时的光标。
        savedSelections = savedSelections.dropLast(1)
        stack = stack.dropLast(1)
        screen = stack.last()
        selectedIndex = restoreSelection()
        closeCover()
    }

    /** 一路退回目标页（加歌成功后用它直接回到音乐菜单，不用连按几次 MENU）。 */
    private fun popTo(target: Screen) {
        val index = stack.indexOfLast { it == target }
        if (index < 0) return
        wheelScrubEnd()
        stack = stack.take(index + 1)
        screen = stack.last()
        savedSelections = savedSelections.take(index + 1)
        selectedIndex = restoreSelection()
        closeCover()
    }

    /** 父页行数可能在子页期间变短（移歌/重扫），恢复后必须夹回合法范围。 */
    private fun restoreSelection(): Int {
        val saved = savedSelections.getOrElse(savedSelections.lastIndex) { 0 }
        val count = currentMenuCount()
        return if (count <= 0) 0 else saved.coerceIn(0, count - 1)
    }

    fun select(delta: Int, itemCount: Int) {
        if (itemCount <= 0) return
        selectedIndex = (selectedIndex + delta).coerceIn(0, itemCount - 1)
    }

    // ---- library actions ----
    fun playSongs(list: List<Song>, index: Int = 0) {
        if (list.isEmpty()) return
        // 统一兜底夹取：调用方各自夹过了，这里再夹一次，
        // 防止后续新增调用点漏掉导致 setMediaItems 越界崩溃。
        player.playQueue(list, index.coerceIn(0, list.lastIndex))
        // 歌词由 IpodApp 的 LaunchedEffect(current?.id) → onQueueIndexChanged() 统一加载：
        // 自动切歌、通知栏切歌也走同一条路径，这里再调一次只会重复读一遍文件。
    }

    fun onQueueIndexChanged() {
        loadLyricsFor(player.currentSong.value)
    }

    private var lyricsJob: Job? = null

    /** 当前 [lyrics] 属于哪首歌；用于换歌时先清空旧歌词而不重设歌词文件夹。 */
    private var lyricsSongId: Long? = null

    private fun loadLyricsFor(song: Song?) {
        lyricsJob?.cancel()
        if (song == null) {
            lyrics = null
            lyricsSongId = null
            return
        }
        val songId = song.id
        // 换歌时先清掉上一首的歌词：否则新歌 IO 加载完成前，
        // 歌词页会拿新歌的进度去滚动/高亮旧歌词，看起来就是定位错行。
        if (lyricsSongId != songId) {
            lyrics = null
        }
        lyricsJob = viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                // File path works below Android 10 / legacy storage.
                LyricParser.loadForAudio(song.path)?.let { return@withContext it }
                // Scoped storage: .lrc is not a media type, so File cannot read it.
                // Fall back to the user-granted SAF lyrics folder.
                val dir = lyricsDirUri.value
                if (dir.isBlank()) return@withContext null
                val name = song.path.substringAfterLast('/').ifBlank { song.title + ".mp3" }
                LyricParser.loadViaSaf(getApplication(), android.net.Uri.parse(dir), name)
            }
            // 快速切歌时上一次 IO 可能更晚返回；只接受仍是当前曲目的结果。
            if (songId == player.currentSong.value?.id) {
                lyrics = loaded
                lyricsSongId = songId
            }
        }
    }

    fun openAlbum(album: Album) {
        viewModelScope.launch {
            detailSongs = musicRepo.songsByAlbum(album.id, album.title)
            detailTitle = album.title
            detailPlaylistId = null
            push(Screen.AlbumDetail(album.id, album.title))
        }
    }

    fun openArtist(artist: String) {
        viewModelScope.launch {
            detailSongs = musicRepo.songsByArtist(artist)
            detailTitle = artist
            detailPlaylistId = null
            push(Screen.ArtistDetail(artist))
        }
    }

    /**
     * 播放列表 → 曲库歌单。按 id 回**原始表**查，不用 `_songs.value` 过滤：
     * 后者是去重后的视图，列表里存的 id 可能正对着被合并掉的那一条，
     * 只筛视图会让那首歌凭空消失（详情页少一首、播放时被静默跳过）。
     */
    private fun songsOfPlaylist(playlist: com.ipodplayer3.app.data.model.Playlist): List<Song> {
        if (playlist.songIds.isEmpty()) return emptyList()
        val order = HashMap<Long, Int>(playlist.songIds.size)
        playlist.songIds.forEachIndexed { i, id -> order[id] = i }
        return playlist.songIds.mapNotNull { musicRepo.findById(it) }
            .sortedBy { order.getValue(it.id) }
    }

    /**
     * 列表页与详情页共用的「实际可播条数」。
     * songIds 里可能残留已被删除 / 换机丢失的 id，不能再直接用 songIds.size。
     */
    fun playlistTrackCount(playlist: com.ipodplayer3.app.data.model.Playlist): Int =
        songsOfPlaylist(playlist).size

    fun openPlaylist(id: Long) {
        val playlist = playlists.value.firstOrNull { it.id == id } ?: return
        detailTitle = playlist.name
        detailPlaylistId = id
        detailSongs = songsOfPlaylist(playlist)
        push(Screen.PlaylistDetail)
    }

    private fun reloadDetailSongs(playlistId: Long) {
        val playlist = playlists.value.firstOrNull { it.id == playlistId } ?: return
        detailSongs = songsOfPlaylist(playlist)
    }

    private var searchJob: Job? = null

    /**
     * @param resetCursor 查询换了 → 结果整批换掉，光标归零。
     *   曲库重扫（[refreshSnapshotScreens]）只是同一批结果的增减，
     *   夹回合法范围即可，不该把正在翻的用户丢回第一行。
     */
    fun runSearch(resetCursor: Boolean = true) {
        searchJob?.cancel()
        val query = searchQuery
        searchJob = viewModelScope.launch {
            val results = musicRepo.search(query)
            // 快速改查询时上一次查询可能更晚返回；只接受仍是当前查询的结果，
            // 否则旧结果会盖掉刚查出来的新结果。
            if (searchQuery != query) return@launch
            searchResults = results
            selectedIndex = if (resetCursor) 0
            else selectedIndex.coerceIn(0, maxOf(results.lastIndex, 0))
        }
    }

    // ---- playlists ----
    fun createPlaylist(name: String) {
        viewModelScope.launch {
            val p = playlistStore.create(name)
            if (p == null) {
                showToast(savedFailedMessage)
            } else {
                openPlaylist(p.id)
            }
        }
    }

    /** 加歌公共部分：落盘 + 刷新详情 + 提示。 */
    private suspend fun addSongAndNotify(playlistId: Long, songId: Long): Boolean {
        val name = playlistStore.find(playlistId)?.name
        if (!playlistStore.addSong(playlistId, songId)) return false
        // 详情页可能正开着这个列表，刷新一下内容。
        // 注意此刻 screen 可能是选歌页，不能用它判断；看 detailPlaylistId。
        if (detailPlaylistId == playlistId) reloadDetailSongs(playlistId)
        showToast(addedMessage(name ?: ""))
        return true
    }

    /** 把一首歌加入播放列表；成功后退回音乐菜单。 */
    fun addSongToPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch {
            if (addSongAndNotify(playlistId, songId)) popTo(Screen.MusicMenu)
            else showToast(savedFailedMessage)
        }
    }

    /** 详情页入口：留在选歌页，方便连续加多首；MENU 返回详情页。 */
    fun addSongToDetailPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch {
            if (!addSongAndNotify(playlistId, songId)) showToast(savedFailedMessage)
        }
    }

    /** 新建一个列表并把这首歌放进去。 */
    fun createPlaylistWithSong(defaultName: String, songId: Long) {
        viewModelScope.launch {
            val created = playlistStore.create(defaultName)
            if (created == null) {
                showToast(savedFailedMessage)
                return@launch
            }
            if (playlistStore.addSong(created.id, songId)) {
                showToast(addedMessage(created.name))
                popTo(Screen.MusicMenu)
            } else {
                showToast(savedFailedMessage)
            }
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch {
            if (playlistStore.removeSong(playlistId, songId)) {
                reloadDetailSongs(playlistId)
                showToast(strings.playlistRemoved)
                pop()
            } else {
                showToast(savedFailedMessage)
            }
        }
    }

    fun renamePlaylist(playlistId: Long, name: String) {
        // 空白名 PlaylistStore 会静默回退成原名（「没有变化」直接返回 true）。
        // 那不是重命名成功：既没有内容可提示，也会把详情页标题写成空白串。
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            pop()
            return
        }
        viewModelScope.launch {
            if (playlistStore.rename(playlistId, trimmed)) {
                // 此刻 screen 是 RenamePlaylist，用 detailPlaylistId 判断是否要改详情页标题。
                // 必须用「落盘后的名字」而不是用户敲的原串，两者可能只差首尾空格。
                if (detailPlaylistId == playlistId) {
                    detailTitle = playlistStore.find(playlistId)?.name ?: trimmed
                }
                reloadDetailSongs(playlistId)
                showToast(strings.playlistRenamed)
            } else {
                showToast(savedFailedMessage)
            }
            pop()
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            if (playlistStore.delete(playlistId)) {
                showToast(strings.playlistDeleted)
                popTo(Screen.Playlists)
            } else {
                showToast(savedFailedMessage)
            }
        }
    }

    // ---- player controls from wheel ----
    fun wheelScroll(delta: Int) {
        when (screen) {
            Screen.CoverFlow -> {
                val list = albums.value
                if (list.isEmpty()) return
                moveCoverTo((coverIndex + delta).coerceIn(0, list.lastIndex))
            }
            is Screen.NowPlaying, is Screen.Lyrics -> {
                // Seek is continuous via wheelScrub; discrete notches are ignored.
            }
            else -> {
                val count = currentMenuCount()
                select(delta, count)
            }
        }
    }

    /**
     * Stepless seek: raw wheel angle in degrees maps to playback time.
     * ~60ms per degree ≈ 22s of audio per full revolution — same ballpark
     * as the old 1s/notch feel, but with no quantization.
     */
    fun wheelScrub(deltaDegrees: Float) {
        when (screen) {
            is Screen.NowPlaying, is Screen.Lyrics -> {
                scrubFracMs += deltaDegrees * SEEK_MS_PER_DEGREE
                val whole = scrubFracMs.toLong()
                if (whole != 0L) {
                    scrubFracMs -= whole.toFloat()
                    player.scrubBy(whole)
                }
            }
            else -> Unit
        }
    }

    fun wheelScrubEnd() {
        scrubFracMs = 0f
        // Only buzz when a real scrub session closed. push/pop call this too,
        // and clickFeedback already ticked — buzzing again would double up.
        if (player.endScrub()) {
            com.ipodplayer3.app.ui.device.Haptic.tick()
        }
    }

    fun isSeekScreen(): Boolean =
        screen is Screen.NowPlaying || screen is Screen.Lyrics

    private var scrubFracMs = 0f

    /** "3.0,-1.0,..." → 10 段增益；长度不符或含 NaN/Inf 时返回 null。 */
    private fun parseEqBands(raw: String): FloatArray? {
        if (raw.isBlank()) return null
        val values = raw.split(',').mapNotNull {
            it.trim().toFloatOrNull()?.takeIf { v -> v.isFinite() }
        }
        return if (values.size == GraphicEqualizerProcessor.BAND_COUNT) values.toFloatArray() else null
    }

    private companion object {
        const val SEEK_MS_PER_DEGREE = 60f
        /** 播放列表详情页顶部的操作行数（添加 / 移除 / 重命名 / 删除）。 */
        const val PLAYLIST_ACTIONS = 4
    }

    fun currentMenuCount(): Int = when (screen) {
        Screen.MainMenu -> 7
        Screen.MusicMenu -> 6
        Screen.Songs, Screen.PickSongForPlaylist, is Screen.AddSongsToPlaylist -> songs.value.size
        Screen.Albums -> albums.value.size
        Screen.Artists -> artists.value.size
        // CoverFlow 的光标是 coverIndex，不走 select()/selectedIndex；这里给 0
        // 表示「该页没有列表光标」，与 NowPlaying/Lyrics 同类。
        Screen.CoverFlow -> 0
        Screen.Playlists, is Screen.PickPlaylist -> playlists.value.size + 1
        Screen.PlaylistDetail -> detailSongs.size + PLAYLIST_ACTIONS
        is Screen.RemoveFromPlaylist -> detailSongs.size
        is Screen.RenamePlaylist, is Screen.DeletePlaylist -> 2
        is Screen.AlbumDetail,
        is Screen.ArtistDetail -> detailSongs.size
        Screen.Search -> maxOf(searchResults.size, 1)
        Screen.NowPlaying -> 0
        Screen.Lyrics -> 0
        Screen.Settings -> 10
        Screen.Eq -> 2 + GraphicEqualizerProcessor.BAND_COUNT
        Screen.About -> 0
    }

    /**
     * 滚轮中心键：进入 / 确认当前行。
     *
     * @param defaultPlaylistName 新建播放列表的默认名字（文案在资源里，由界面传入）。
     */
    fun activate(defaultPlaylistName: String) {
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
                4 -> push(Screen.Playlists)
                else -> push(Screen.PickSongForPlaylist)
            }
            Screen.Songs -> {
                val list = songs.value
                // Empty library: do not push a blank Now Playing screen.
                // 同时夹取索引：ContentObserver 重扫会让 list 变短，而 selectedIndex
                // 只在滚动那一刻夹过，这里再夹一次才能避免 setMediaItems 越界。
                if (list.isEmpty()) return
                playSongs(list, selectedIndex.coerceIn(0, list.lastIndex))
                push(Screen.NowPlaying)
            }
            Screen.Albums -> {
                albums.value.getOrNull(selectedIndex)?.let { openAlbum(it) }
            }
            Screen.Artists -> {
                artists.value.getOrNull(selectedIndex)?.let { openArtist(it.name) }
            }
            Screen.CoverFlow -> {
                if (coverFlipped) {
                    albums.value.getOrNull(coverIndex)?.let { openAlbum(it) }
                } else {
                    flipCover()
                }
            }
            Screen.Playlists -> when {
                selectedIndex == 0 ->
                    createPlaylist("$defaultPlaylistName ${playlists.value.size + 1}")
                else -> playlists.value.getOrNull(selectedIndex - 1)?.let { openPlaylist(it.id) }
            }
            Screen.PlaylistDetail -> {
                val playlistId = detailPlaylistId
                when {
                    playlistId == null -> Unit
                    selectedIndex == 0 ->
                        push(Screen.AddSongsToPlaylist(playlistId))
                    selectedIndex == 1 ->
                        push(Screen.RemoveFromPlaylist(playlistId, detailTitle))
                    selectedIndex == 2 -> {
                        renameDraft = playlistStore.find(playlistId)?.name ?: detailTitle
                        push(Screen.RenamePlaylist(playlistId))
                    }
                    selectedIndex == 3 ->
                        push(Screen.DeletePlaylist(playlistId, detailTitle))
                    else -> {
                        val index = selectedIndex - PLAYLIST_ACTIONS
                        if (index !in detailSongs.indices) return
                        playSongs(detailSongs, index)
                        push(Screen.NowPlaying)
                    }
                }
            }
            is Screen.AlbumDetail,
            is Screen.ArtistDetail -> {
                if (detailSongs.isEmpty()) return
                playSongs(detailSongs, selectedIndex.coerceIn(0, detailSongs.lastIndex))
                push(Screen.NowPlaying)
            }
            Screen.PickSongForPlaylist -> {
                val song = songs.value.getOrNull(selectedIndex) ?: return
                push(Screen.PickPlaylist(song.id))
            }
            is Screen.AddSongsToPlaylist -> {
                val target = screen as Screen.AddSongsToPlaylist
                songs.value.getOrNull(selectedIndex)?.let {
                    addSongToDetailPlaylist(target.playlistId, it.id)
                }
            }
            is Screen.PickPlaylist -> {
                val songId = (screen as Screen.PickPlaylist).songId
                if (selectedIndex == 0) {
                    createPlaylistWithSong(
                        "$defaultPlaylistName ${playlists.value.size + 1}",
                        songId
                    )
                } else {
                    playlists.value.getOrNull(selectedIndex - 1)
                        ?.let { addSongToPlaylist(it.id, songId) }
                }
            }
            is Screen.RemoveFromPlaylist -> {
                val target = screen as Screen.RemoveFromPlaylist
                detailSongs.getOrNull(selectedIndex)
                    ?.let { removeSongFromPlaylist(target.playlistId, it.id) }
            }
            is Screen.RenamePlaylist -> {
                val target = screen as Screen.RenamePlaylist
                if (selectedIndex == 0) renamePlaylist(target.playlistId, renameDraft) else pop()
            }
            is Screen.DeletePlaylist -> {
                val target = screen as Screen.DeletePlaylist
                if (selectedIndex == 0) deletePlaylist(target.playlistId) else pop()
            }
            Screen.Search -> {
                if (searchResults.isNotEmpty()) {
                    val idx = selectedIndex.coerceIn(0, searchResults.lastIndex)
                    playSongs(searchResults, idx)
                    push(Screen.NowPlaying)
                }
            }
            Screen.NowPlaying -> push(Screen.Lyrics)
            Screen.Settings -> when (selectedIndex) {
                0 -> push(Screen.Eq)
                1 -> viewModelScope.launch {
                    settings.setLanguage(
                        if (language == AppLanguage.ZH) AppLanguage.EN else AppLanguage.ZH
                    )
                }
                2 -> viewModelScope.launch {
                    settings.setTheme(theme.id.next().key)
                }
                3 -> toggleClickSound()
                4 -> toggleVibrate()
                5 -> viewModelScope.launch {
                    // 先写盘、由 collector 推给 player，保证 UI 与 DSP 同一来源。
                    settings.setShuffle(!shuffle.value)
                }
                6 -> viewModelScope.launch {
                    val next = when (repeat.value) {
                        RepeatMode.OFF -> RepeatMode.ALL
                        RepeatMode.ALL -> RepeatMode.ONE
                        RepeatMode.ONE -> RepeatMode.OFF
                    }
                    settings.setRepeatMode(next.name)
                }
                7 -> viewModelScope.launch {
                    val next = when (transitionMode) {
                        "A" -> "B"
                        "B" -> "C"
                        else -> "A"
                    }
                    settings.setTransitionMode(next)
                }
                8 -> refreshLibrary()
                else -> requestLyricsDirPicker()
            }
            Screen.Eq -> when {
                selectedIndex == 0 -> {
                    // 只写盘，由 onInit 里的 collector 推给 player：
                    // UI 与 DSP 必须同一条通路，这里再直写一次就成了两个来源。
                    val next = !eqEnabled
                    viewModelScope.launch { settings.setEqEnabled(next) }
                }
                selectedIndex == 1 -> viewModelScope.launch {
                    val presets = EqPreset.entries
                    val next = presets[(eqPreset.ordinal + 1) % presets.size]
                    settings.setEqPreset(next)
                    player.applyEqPreset(next.name.lowercase())
                    eqBands = player.eqGains().toList()
                    // 预设本身就是一条曲线，一起落盘，重启后才能原样恢复。
                    settings.setEqBands(eqBands)
                }
                else -> {
                    val band = selectedIndex - 2
                    val current = eqBands.getOrElse(band) { 0f }
                    // Step through the full [MIN_GAIN_DB, MAX_GAIN_DB] range; wrap after
                    // exceeding +12. 必须用 `current + 2 > max` 而不是 `current >= 12`：
                    // 预设曲线里全是奇数（classical +3、bass +7），+2 步进会经过 13，
                    // 显示与 DSP 夹取不一致。
                    val next = when {
                        current + 2f > GraphicEqualizerProcessor.MAX_GAIN_DB ->
                            GraphicEqualizerProcessor.MIN_GAIN_DB
                        else -> current + 2f
                    }
                    eqBands = eqBands.toMutableList().also {
                        while (it.size < GraphicEqualizerProcessor.BAND_COUNT) it.add(0f)
                        it[band] = next
                    }
                    player.setEqBand(band, next)
                    viewModelScope.launch { settings.setEqBands(eqBands) }
                }
            }
            Screen.About -> Unit
            Screen.Lyrics -> Unit
        }
    }

    fun togglePlayPause() = player.togglePlayPause()
    fun next() = player.next()

    fun previous() = player.previous()

    /** 立即生效：UI 状态 + 震动开关 + 持久化，三者同步。 */
    fun toggleVibrate() {
        val next = !vibrate
        vibrate = next
        com.ipodplayer3.app.ui.device.Haptic.setEnabled(next)
        viewModelScope.launch { settings.setVibrate(next) }
        // confirm() is force=true — must buzz so the switch is verifiable.
        if (next) com.ipodplayer3.app.ui.device.Haptic.confirm()
    }

    /** 立即生效：UI 状态 + 按键音开关 + 持久化。 */
    fun toggleClickSound() {
        val next = !clickSound
        clickSound = next
        com.ipodplayer3.app.ui.device.ClickSound.setEnabled(next)
        viewModelScope.launch { settings.setClickSound(next) }
        if (next) com.ipodplayer3.app.ui.device.ClickSound.play()
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
