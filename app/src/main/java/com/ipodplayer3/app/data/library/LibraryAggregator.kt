package com.ipodplayer3.app.data.library

import com.ipodplayer3.app.data.model.Album
import com.ipodplayer3.app.data.model.Artist
import com.ipodplayer3.app.data.model.Song
import java.util.Locale

/**
 * 曲库的纯计算部分：只看 `List<Song>`，不碰 MediaStore / Context。
 *
 * 抽出来的原因是这些逻辑（分组、排序、去重、搜索）最容易出错，却原来埋在
 * `MusicRepository` 里必须先有 Context 才能测。现在它们是纯函数，JVM 直测。
 */
internal object LibraryAggregator {

    /** 按 (albumId, album 名) 分组；空串代表「未知专辑」，由界面替换文案。 */
    fun albums(songs: List<Song>): List<Album> =
        songs.groupBy { it.albumId to it.album }
            .map { (key, group) ->
                Album(
                    id = key.first,
                    title = key.second,
                    artist = group.firstOrNull()?.artist.orEmpty(),
                    songCount = group.size,
                    // 组里第一首可能正好没有封面，取第一个非空的。
                    albumArtUri = group.firstOrNull { it.albumArtUri != null }?.albumArtUri,
                )
            }
            .sortedBy { it.title.lowercase(Locale.ROOT) }

    fun artists(songs: List<Song>): List<Artist> {
        // 空串（未知艺术家）自然归到一组，名字由界面替换。
        val groups = songs.groupBy { it.artist }
        // Name.hashCode() can collide across distinct artists; resolve in-place
        // so every id is unique within the list (LazyColumn keys require this).
        val usedIds = HashSet<Long>()
        return groups.map { (name, group) ->
            var id = name.hashCode().toLong()
            while (!usedIds.add(id)) id++
            Artist(
                id = id,
                name = name,
                songCount = group.size,
            )
        }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    /**
     * 专辑内排序：TRACK 可能是 disc*1000+track，按原值排就能让多碟专辑顺序正确；
     * 没有音轨号（0）的排到最后，而不是插在最前面。
     *
     * [albumTitle] 非 null 时一并按标题过滤：同一 albumId 理论上可能挂两个专辑名，
     * 只按 id 过滤会把另一张的歌混进来。
     */
    fun songsOfAlbum(
        songs: List<Song>,
        albumId: Long,
        albumTitle: String? = null,
    ): List<Song> =
        songs.filter { it.albumId == albumId && (albumTitle == null || it.album == albumTitle) }
            .sortedWith(
                compareBy({ if (it.track == 0) Int.MAX_VALUE else it.track }, { it.title })
            )

    fun songsOfArtist(songs: List<Song>, artist: String): List<Song> =
        songs.filter { it.artist == artist }.sortedBy { it.album.lowercase(Locale.ROOT) }

    fun search(songs: List<Song>, query: String): List<Song> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return songs.filter {
            it.title.contains(q, true) ||
                it.artist.contains(q, true) ||
                it.album.contains(q, true)
        }
    }

    /**
     * 同一首歌同时存在于内置存储与 SD 卡（或 mp3/flac 各一份）时会重复出现，
     * 专辑与艺术家的计数也跟着翻倍。按「标题+艺术家+专辑+时长」去重。
     */
    fun dedupe(songs: List<Song>): List<Song> {
        val seen = HashSet<String>()
        return songs.filter {
            seen.add(
                "${it.title.lowercase(Locale.ROOT)}\u0000${it.artist}\u0000${it.album}\u0000${it.durationMs}"
            )
        }
    }
}
