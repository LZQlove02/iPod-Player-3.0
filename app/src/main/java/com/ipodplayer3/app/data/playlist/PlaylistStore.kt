package com.ipodplayer3.app.data.playlist

import android.content.Context
import android.util.Log
import com.ipodplayer3.app.data.model.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private const val TAG = "PlaylistStore"

@Serializable
data class PlaylistDto(
    val id: Long,
    val name: String,
    val songIds: List<Long>,
    val createdAt: Long,
)

/**
 * 播放列表的本地存储（JSON）。
 *
 * - 所有写操作串行化（[mutex]）并返回是否**真的落盘**：写失败时内存状态保持不变，
 *   避免界面显示「已保存」而重启后消失。
 * - 写盘走「临时文件 + rename」：中途被杀不会留下半截 JSON。
 * - 构造器收 [File] 而不是 Context，方便纯 JVM 单元测试注入临时目录。
 */
class PlaylistStore(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, "playlists.json"))

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 读-改-写必须串行：双击「新建播放列表」时两次 create 会拿到同一份快照，
     * 后写的那次会把前一次的结果覆盖掉（丢一个列表）。
     */
    private val mutex = Mutex()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    fun find(playlistId: Long): Playlist? = _playlists.value.firstOrNull { it.id == playlistId }

    suspend fun load() = mutex.withLock {
        withContext(Dispatchers.IO) {
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
                // 解析失败说明文件坏了：先把现场留一份副本，否则用户下一次
                // 「新建播放列表」就会把唯一的那份数据直接覆盖掉。
                runCatching {
                    val backup = File(file.parentFile, "playlists.json.corrupt-${System.currentTimeMillis()}")
                    // rename 失败时不能让原地坏文件留下：否则下一次 create() 会
                    // 直接覆盖，唯一的现场就没了。改用「拷贝后清空」兜底。
                    if (!file.renameTo(backup)) {
                        file.copyTo(backup, overwrite = true)
                        file.delete()
                    }
                }
                Log.e(TAG, "播放列表解析失败，原文件已改名为 .corrupt-*", it)
                _playlists.value = emptyList()
            }
        }
    }

    /** @return true 表示列表已创建并落盘 */
    suspend fun create(name: String): Playlist? = mutex.withLock {
        // 时间戳单独用会在同一毫秒内撞车（双击「新建」），再取一次当前最大 id 兜底。
        val lastId = _playlists.value.maxOfOrNull { it.id } ?: 0L
        val playlist = Playlist(
            id = maxOf(System.currentTimeMillis(), lastId + 1),
            // 默认名归界面层：数据层不许写死任何语言的文案（否则英文界面冒中文 / 中文界面冒英文）。
            // 只负责去空白；调用方保证非空（MainViewModel 用 strings.defaultPlaylistName 拼名）。
            name = name.trim(),
        )
        if (persist(_playlists.value + playlist)) playlist else null
    }

    /** 追加歌曲（已存在则视为成功，不产生重复项）。 */
    suspend fun addSong(playlistId: Long, songId: Long): Boolean = mutate(playlistId) { playlist ->
        if (songId in playlist.songIds) return@mutate playlist
        playlist.copy(songIds = playlist.songIds + songId)
    }

    suspend fun removeSong(playlistId: Long, songId: Long): Boolean = mutate(playlistId) { playlist ->
        playlist.copy(songIds = playlist.songIds - songId)
    }

    suspend fun rename(playlistId: Long, name: String): Boolean = mutate(playlistId) { playlist ->
        playlist.copy(name = name.trim().ifBlank { playlist.name })
    }

    suspend fun delete(playlistId: Long): Boolean = mutex.withLock {
        persist(_playlists.value.filterNot { it.id == playlistId })
    }

    private suspend fun mutate(
        playlistId: Long,
        transform: (Playlist) -> Playlist,
    ): Boolean = mutex.withLock {
        val current = _playlists.value
        val index = current.indexOfFirst { it.id == playlistId }
        if (index < 0) return@withLock false
        val updated = transform(current[index])
        if (updated == current[index]) return@withLock true
        persist(current.toMutableList().also { it[index] = updated })
    }

    /** 先写临时文件再改名：写到一半被杀掉不会留下半截 JSON。 */
    private suspend fun persist(list: List<Playlist>): Boolean = withContext(Dispatchers.IO) {
        val dtos = list.map { PlaylistDto(it.id, it.name, it.songIds, it.createdAt) }
        val text = json.encodeToString(dtos)
        val written = runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                // 个别文件系统上 rename 会失败，退回直接覆盖，至少保证写得进去。
                file.writeText(text)
                tmp.delete()
            }
        }.onFailure { Log.e(TAG, "播放列表写盘失败", it) }.isSuccess
        // 写盘失败就不更新内存状态：否则界面显示「已保存」，重启后却没了。
        if (written) _playlists.value = list
        written
    }
}
