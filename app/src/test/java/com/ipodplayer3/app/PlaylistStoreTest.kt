package com.ipodplayer3.app

import com.ipodplayer3.app.data.model.Playlist
import com.ipodplayer3.app.data.playlist.PlaylistStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlaylistStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(file: File = File(temp.root, "playlists.json")) = PlaylistStore(file)

    @Test
    fun create_load_roundTrip() = runTest {
        val file = File(temp.root, "playlists.json")
        val store = store(file)
        store.load()
        val created = store.create("晨跑")
        assertNotNull(created)
        assertEquals(1, store.playlists.value.size)

        // 新实例从磁盘读回：说明真的落盘了，不只是改了内存
        val reopened = store(file)
        reopened.load()
        assertEquals("晨跑", reopened.playlists.value.single().name)
    }

    @Test
    fun addSong_isIdempotent_andKeepsOrder() = runTest {
        val store = store()
        store.load()
        val playlist = store.create("A")!!
        assertTrue(store.addSong(playlist.id, 11L))
        assertTrue(store.addSong(playlist.id, 22L))
        // 重复加入不产生重复项
        assertTrue(store.addSong(playlist.id, 11L))
        assertEquals(listOf(11L, 22L), store.playlists.value.single().songIds)
    }

    @Test
    fun removeSong_rename_delete() = runTest {
        val store = store()
        store.load()
        val playlist = store.create("A")!!
        store.addSong(playlist.id, 11L)
        store.addSong(playlist.id, 22L)

        assertTrue(store.removeSong(playlist.id, 11L))
        assertEquals(listOf(22L), store.playlists.value.single().songIds)

        assertTrue(store.rename(playlist.id, "  新名字  "))
        assertEquals("新名字", store.playlists.value.single().name)

        assertTrue(store.delete(playlist.id))
        assertTrue(store.playlists.value.isEmpty())

        // 对不存在的列表操作返回 false，而不是抛异常
        assertFalse(store.addSong(playlist.id, 33L))
        assertFalse(store.rename(playlist.id, "x"))
    }

    @Test
    fun concurrentCreate_doesNotLosePlaylists() = runTest {
        val store = store()
        store.load()
        // 串行化前：两次 create 会基于同一份快照，后写覆盖前写
        val a = store.create("A")
        val b = store.create("B")
        assertEquals(2, store.playlists.value.size)
        assertTrue(a!!.id != b!!.id)
    }

    @Test
    fun corruptFile_isKeptAsideInsteadOfOverwritten() = runTest {
        val file = File(temp.root, "playlists.json")
        file.writeText("{ this is not valid json")
        val store = store(file)
        store.load()
        assertTrue(store.playlists.value.isEmpty())
        // 坏文件被改名留档，下一次写盘不会覆盖唯一副本
        assertFalse(file.exists())
        val kept = temp.root.listFiles()?.filter { it.name.startsWith("playlists.json.corrupt-") }
        assertEquals(1, kept?.size)
    }

    @Test
    fun blankName_isNotInventedByDataLayer() = runTest {
        val store = store()
        store.load()
        val playlist = store.create("   ")!!
        // 数据层不许凭空造任何语言的默认名（否则英文界面会冒出 "Playlist N"）。
        // 只负责去掉首尾空白；填什么名字由界面层（res/values/strings.xml）负责。
        assertEquals("", playlist.name)
    }

    @Test
    fun find_returnsPlaylist() = runTest {
        val store = store()
        store.load()
        val playlist = store.create("A")!!
        assertNotNull(store.find(playlist.id))
        assertNull(store.find(playlist.id + 1000))
    }

    @Test
    fun emptyListIsCachedAfterFirstLoad() = runTest {
        val store = store()
        // 文件不存在时 load 后列表为空，且不应把「空」当成「没查过」反复读盘
        store.load()
        assertEquals(emptyList<Playlist>(), store.playlists.value)
    }
}
