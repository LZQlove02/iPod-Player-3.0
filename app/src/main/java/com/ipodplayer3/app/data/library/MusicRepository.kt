package com.ipodplayer3.app.data.library

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.ipodplayer3.app.data.model.Album
import com.ipodplayer3.app.data.model.Artist
import com.ipodplayer3.app.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地曲库。
 *
 * 约定：**数据层不做本地化文案**。「未知专辑 / 未知艺术家 / 未知歌曲」在库里
 * 统一是空串，由界面层替换成 strings.unknownXxx —— 否则英文界面里会冒出中文。
 */
class MusicRepository(private val context: Context) {

    /**
     * 原始、**未去重**曲目按 MediaStore id 建的索引。
     *
     * 播放列表存的是 MediaStore id，而这个 id 可能正落在被去重合并掉的那一条上
     * （同一首歌在内置存储与 SD 卡各存一份）—— 按 id 只能回这张原始表查，
     * 否则那首歌在列表详情里会凭空消失、播放时被静默跳过。
     */
    @Volatile
    private var indexById: Map<Long, Song> = emptyMap()

    /** 去重后的视图：界面列表与专辑/艺术家聚合都用它，避免计数翻倍。 */
    @Volatile
    private var view: List<Song> = emptyList()

    /**
     * 媒体库变更代数。
     *
     * 观察者跑在主线程、loadAll 跑在 IO 线程，两者若直接抢着改 [indexById] / [view]，
     * 可能被切出「view 已有数据、indexById 还没建」这种半截状态 —— 界面看着像空库，
     * 却因为 loadedOnce 已置位而不再重查。所以观察者**只递增这个计数、不碰缓存**，
     * loadAll 记下查询前的代数，查询完再落盘；代数对不上就说明查的这批已经过期。
     */
    private val changeVersion = AtomicLong(0L)

    /** 已装载的 [indexById] / [view] 对应哪一代变更；[UNLOADED] 表示还没查过。 */
    @Volatile
    private var loadedVersion = UNLOADED

    /** 手动「重新扫描」与 ContentObserver 可能并发，串行化查询。 */
    private val loadMutex = Mutex()

    private var observerRegistered = false

    /**
     * 系统媒体库变化（新增 / 删除 / 改名）后自动失效缓存，
     * 不用等用户去设置里手动「重新扫描曲库」。
     *
     * 不清缓存：清了会让「正在看的播放列表详情」在重查完成前瞬间变空；
     * 保留旧数据 + 让 loadAll 重查才是安全的失效方式。
     */
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            changeVersion.incrementAndGet()
            _invalidated.tryEmit(Unit)
        }
    }

    /**
     * 缓存失效信号：ViewModel 订阅后自动重查，否则只是「下次 loadAll 再查」——
     * 而 loadAll 只在启动 / 权限回调 / 手动重扫时被调用，用户看不到新歌。
     */
    private val _invalidated = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val invalidated: SharedFlow<Unit> = _invalidated.asSharedFlow()

    suspend fun loadAll(force: Boolean = false): List<Song> = loadMutex.withLock {
        withContext(Dispatchers.IO) {
            // 先取代数再决定查不查：这样查询期间若又来了变更，落盘时的代数
            // 会和当前代数对不上，下一次 loadAll 自然会再查一遍，不会漏。
            val base = changeVersion.get()
            if (!force && loadedVersion == base) return@withContext view
            registerObserver()
            // 权限在运行期被撤销（或某些 ROM 的聚合 cursor 缺列）会抛
            // SecurityException / IllegalArgumentException：抛出去就是崩溃，
            // 这里降级为「保持上一次的曲库」。
            val raw = runCatching { queryAudio() }
                .onFailure { Log.w(TAG, "MediaStore 查询失败", it) }
                .getOrNull()
                ?: return@withContext view
            // indexById 与 view 必须成对发布：只更新一半会让详情页按 id 查不到歌。
            indexById = buildIdIndex(raw)
            view = LibraryAggregator.dedupe(raw)
            // 查失败时不动这个值 → 代数仍对不上 → 下一次 loadAll 会重试，而不是
            // 停在「上次那批歌」上再也不动。
            loadedVersion = base
            view
        }
    }

    /**
     * 按 MediaStore id 查**原始**（未去重）曲目。
     *
     * 播放列表按 id 存歌，而 [LibraryAggregator.dedupe] 会把同名同时长的副本合并掉；
     * 只在去重后的列表里找，被合并的那条就查不回来 —— 详情页少一首、播放时静默跳过。
     */
    fun findById(id: Long): Song? = indexById[id]

    private fun buildIdIndex(raw: List<Song>): Map<Long, Song> {
        val map = HashMap<Long, Song>(raw.size)
        // 聚合卷下不同卷可能撞 _ID：保留先出现的那条，与 dedupe「保留首条」一致。
        for (song in raw) if (!map.containsKey(song.id)) map[song.id] = song
        return map
    }

    private fun registerObserver() {
        if (observerRegistered) return
        observerRegistered = true
        runCatching {
            context.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
        }.onFailure { Log.w(TAG, "注册媒体库监听失败", it) }
    }

    /**
     * @param songs 显式传入的曲目；null 表示「调用方没有现成列表，请自查」。
     *   不能用 emptyList() 当哨兵 —— 那样「显式传空」和「不传」不可区分，
     *   真·空曲库会平白多走一次 loadAll()。
     */
    suspend fun albums(songs: List<Song>? = null): List<Album> {
        val list = songs ?: loadAll()
        // 聚合是纯 CPU 工作，调用方是 viewModelScope（Main dispatcher），
        // 必须自己切走，否则万首歌库会卡住主线程。
        return withContext(Dispatchers.Default) { LibraryAggregator.albums(list) }
    }

    suspend fun artists(songs: List<Song>? = null): List<Artist> {
        val list = songs ?: loadAll()
        return withContext(Dispatchers.Default) { LibraryAggregator.artists(list) }
    }

    suspend fun songsByAlbum(albumId: Long, albumTitle: String? = null): List<Song> {
        val list = loadAll()
        return withContext(Dispatchers.Default) {
            LibraryAggregator.songsOfAlbum(list, albumId, albumTitle)
        }
    }

    suspend fun songsByArtist(artist: String): List<Song> {
        val list = loadAll()
        return withContext(Dispatchers.Default) { LibraryAggregator.songsOfArtist(list, artist) }
    }

    suspend fun search(query: String): List<Song> {
        if (query.isBlank()) return emptyList()
        val list = loadAll()
        // 搜索逐键触发，同样不能在主线程过滤。
        return withContext(Dispatchers.Default) { LibraryAggregator.search(list, query) }
    }

    private fun queryAudio(): List<Song> {
        val collection: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        // DATA 在 Android 10+ 已废弃，部分 ROM 的聚合 cursor 会对请求 DATA 的
        // projection 直接抛异常 → 整库加载失败、曲库永远是空的。先按含 DATA 的
        // 投影查（歌词要 file path），失败再退回不含 DATA 的最小投影。
        val withData = runCatching { queryAudio(collection, includeData = true) }
            .getOrNull()
        return withData ?: queryAudio(collection, includeData = false)
    }

    private fun queryAudio(collection: Uri, includeData: Boolean): List<Song> {
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.TRACK)
            // 聚合卷一次查回主卷 + SD 卡，但 _ID 只在单个卷内唯一。
            // 逐行读回这条记录自己所在的卷，才能拼出它真正的地址（见 songUri）。
            // VOLUME_NAME 是 API 29 才加的列；更低版本只有一个 external 卷，用不上。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.VOLUME_NAME)
            }
            if (includeData) add(MediaStore.Audio.Media.DATA)
        }.toTypedArray()

        // 只用 IS_MUSIC 过滤：DURATION>0 会把扫描未完成、以及部分 ROM 上
        // 时长列为 0 的 m4a/opus/wav 静默丢掉，用户完全看不到。
        val selection = buildString {
            append("${MediaStore.Audio.Media.IS_MUSIC}!=0")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                append(" AND ${MediaStore.Audio.Media.IS_PENDING}=0")
            }
        }
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
            // DATA 已废弃：部分 ROM 的聚合 cursor 上可能缺席，用 getColumnIndex 兜底。
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            // VOLUME_NAME 同理：低于 API 29 的 projection 里根本没这一列。
            val volumeCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Audio.Media.VOLUME_NAME)
            } else {
                -1
            }

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val albumId = cursor.getLong(albumIdCol)
                val volumeName = if (volumeCol >= 0) cursor.getString(volumeCol) else null
                songs += Song(
                    id = id,
                    title = cursor.getString(titleCol).orEmpty(),
                    artist = normalizeArtist(cursor.getString(artistCol)),
                    album = cursor.getString(albumCol).orEmpty(),
                    albumId = albumId,
                    durationMs = cursor.getLong(durationCol),
                    track = cursor.getInt(trackCol),
                    uri = songUri(id, volumeName),
                    // albumId <= 0（没有专辑标签）时 albumart 端点必然失败，
                    // 交给界面显示占位色块，别让 Coil 反复重试一个死链。
                    albumArtUri = if (albumId > 0L) albumArtUri(albumId) else null,
                    path = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else "",
                )
            }
        }
        // 去重延后到 loadAll：这里必须返回原始表，否则被合并掉的 id 无从查回。
        return songs
    }

    /**
     * 拼这条记录**自己所在卷**的播放地址。
     *
     * 查询用的是聚合卷（`VOLUME_EXTERNAL` = 主卷 + SD 卡），而 `_ID` 只在单卷内唯一。
     * 拿聚合查询出来的 id 去拼主卷 `EXTERNAL_CONTENT_URI`，内置与 SD 上各有一份时
     * 会指向另一首歌或干脆解析不到 —— 点播放就是静默失败。
     */
    private fun songUri(id: Long, volumeName: String?): Uri {
        // isNullOrEmpty 的 contract 会在 false 分支把 volumeName 收窄成非空 String。
        val base = if (!volumeName.isNullOrEmpty() &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) {
            // getContentUri(volumeName) 只在 API 29+ 接受卷名；卷名也只在那时才读得到。
            MediaStore.Audio.Media.getContentUri(volumeName)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        return ContentUris.withAppendedId(base, id)
    }

    private fun albumArtUri(albumId: Long): Uri =
        ContentUris.withAppendedId(ALBUM_ART_BASE, albumId)

    /** 空串 = 未知；具体文案（未知艺术家）由界面层给。 */
    private fun normalizeArtist(raw: String?): String {
        val value = raw?.trim().orEmpty()
        return if (value.equals("<unknown>", true)) "" else value
    }

    private companion object {
        const val TAG = "MusicRepository"

        /** 任何一次真实变更都会让代数走到 0 以上，所以 -1 恒等于「没查过」。 */
        const val UNLOADED = -1L

        /**
         * 专辑封面端点不是公开 SDK 契约，但它是 Android 上存在时间最长、
         * 兼容性最好的取图路径；某些 ROM 会换掉卷名，失败时 Coil 会退到占位。
         */
        val ALBUM_ART_BASE: Uri = Uri.parse("content://media/external/audio/albumart")
    }
}
