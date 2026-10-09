package com.ipodplayer3.app

import android.net.FakeUri
import android.net.Uri
import com.ipodplayer3.app.data.library.LibraryAggregator
import com.ipodplayer3.app.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryAggregatorTest {

    private var nextId = 1L

    /**
     * JVM 单测里 `Uri.parse` 返回 null（AGP 的 mockable android.jar 只给默认值），
     * 而 `Song.uri` 是非空类型；聚合逻辑不读 uri，用一个只用于占位的替身即可。
     * 见 [FakeUri]。
     */
    private val fakeUri: Uri = FakeUri()

    private fun song(
        title: String,
        artist: String = "A",
        album: String = "Alb",
        albumId: Long = 1L,
        track: Int = 0,
        duration: Long = 180_000L,
        art: Boolean = false,
    ) = Song(
        id = nextId++,
        title = title,
        artist = artist,
        album = album,
        albumId = albumId,
        durationMs = duration,
        track = track,
        uri = fakeUri,
        albumArtUri = if (art) fakeUri else null,
    )

    @Test
    fun albums_groupsAndSorts_andPicksFirstArtwork() {
        val songs = listOf(
            song("s1", album = "Zeta", albumId = 2, art = false),
            song("s2", album = "Zeta", albumId = 2, art = true),
            song("s3", album = "alpha", albumId = 1),
            song("s4", album = "", albumId = 0),
        )
        val albums = LibraryAggregator.albums(songs)
        // 空专辑名排最前（lowercase 后是空串）
        assertEquals(listOf("", "alpha", "Zeta"), albums.map { it.title })
        val zeta = albums.first { it.title == "Zeta" }
        assertEquals(2, zeta.songCount)
        // 组里第一首没有封面时取第一个非空的
        assertTrue(zeta.albumArtUri != null)
    }

    @Test
    fun artists_idsAreUnique_evenOnHashCollision() {
        // 构造两个 hashCode 相同但名字不同的艺术家
        val a = "Aa"
        val b = "BB"
        assertEquals(a.hashCode(), b.hashCode())
        val songs = listOf(
            song("s1", artist = a),
            song("s2", artist = a),
            song("s3", artist = b),
            song("s4", artist = ""),
        )
        val artists = LibraryAggregator.artists(songs)
        assertEquals(3, artists.size)
        assertEquals(artists.size, artists.map { it.id }.distinct().size)
        assertEquals(2, artists.first { it.name == a }.songCount)
    }

    @Test
    fun songsOfAlbum_ordersByTrackWithUntaggedLast() {
        val songs = listOf(
            song("no-track", albumId = 7, track = 0),
            song("t2", albumId = 7, track = 2),
            song("disc2t1", albumId = 7, track = 1001),
            song("other", albumId = 8, track = 1),
        )
        val album = LibraryAggregator.songsOfAlbum(songs, 7)
        assertEquals(listOf("t2", "disc2t1", "no-track"), album.map { it.title })
    }

    @Test
    fun songsOfAlbum_titleFiltersSplitIdentities() {
        // 同一 albumId 挂两个专辑名时，按标题过滤，避免混入另一张的歌。
        val songs = listOf(
            song("a1", album = "Alpha", albumId = 9),
            song("b1", album = "Beta", albumId = 9),
            song("a2", album = "Alpha", albumId = 9),
        )
        assertEquals(listOf("a1", "a2"), LibraryAggregator.songsOfAlbum(songs, 9, "Alpha").map { it.title })
        assertEquals(listOf("b1"), LibraryAggregator.songsOfAlbum(songs, 9, "Beta").map { it.title })
        // title == null 时保持旧行为（只按 albumId）
        assertEquals(3, LibraryAggregator.songsOfAlbum(songs, 9).size)
    }

    @Test
    fun songsOfArtist_filters() {
        val songs = listOf(song("a", artist = "X"), song("b", artist = "Y"))
        assertEquals(listOf("b"), LibraryAggregator.songsOfArtist(songs, "Y").map { it.title })
    }

    @Test
    fun search_isCaseInsensitiveAndBlankReturnsEmpty() {
        val songs = listOf(
            song("Hello World", artist = "Adele", album = "25"),
            song("别的歌", artist = "某人", album = "专辑"),
        )
        assertEquals(1, LibraryAggregator.search(songs, "hello").size)
        assertEquals(1, LibraryAggregator.search(songs, "ADELE").size)
        assertEquals(1, LibraryAggregator.search(songs, "专辑").size)
        assertTrue(LibraryAggregator.search(songs, "   ").isEmpty())
    }

    @Test
    fun dedupe_dropsSameTrackFromTwoVolumes() {
        val a = song("Same", artist = "X", album = "Y", duration = 1000L)
        val b = song("Same", artist = "X", album = "Y", duration = 1000L)
        val real = song("Same", artist = "X", album = "Y", duration = 2000L)
        val deduped = LibraryAggregator.dedupe(listOf(a, b, real))
        assertEquals(2, deduped.size)
        assertEquals(listOf(1000L, 2000L), deduped.map { it.durationMs })
    }
}
