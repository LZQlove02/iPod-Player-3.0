package com.ipodplayer3.app.data.model

import android.net.Uri

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val track: Int,
    val uri: Uri,
    val albumArtUri: Uri?,
    /** 仅用于歌词查找（.lrc 同名文件）；scoped storage 下可能为空。 */
    val path: String = "",
)

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val songCount: Int,
    val albumArtUri: Uri?,
)

data class Artist(
    val id: Long,
    val name: String,
    val songCount: Int,
)

data class Playlist(
    val id: Long,
    val name: String,
    val songIds: List<Long> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

data class LyricLine(
    val timeMs: Long,
    val text: String,
)

data class Lyrics(
    val lines: List<LyricLine>,
    val offsetMs: Long = 0L,
) {
    /**
     * 用 Long：调用点原来在 `position.toInt()` 处有截断。
     * @return 当前行下标；空歌词或首句尚未开始时返回 -1（无当前行）。
     */
    fun activeIndex(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        return indexAt(positionMs - offsetMs)
    }

    /**
     * 第 [index] 行在播放时间轴上的起点。
     * 注意 [offsetMs] 的语义是 `t = position - offsetMs`，折回 position 时要加回来。
     */
    fun lineStartMs(index: Int): Long = lines[index].timeMs + offsetMs

    /** 第 [index] 行在播放时间轴上的终点；末行用 [fallbackMs] 兜底（如 4s）。 */
    fun lineEndMs(index: Int, fallbackMs: Long = 4000L): Long =
        if (index in 0 until lines.lastIndex) lines[index + 1].timeMs + offsetMs
        else lineStartMs(index) + fallbackMs

    /**
     * 行内进度 0f..1f。按本行起点到下一行起点线性推进；
     * 对等宽 CJK 单行来说，时间均分 ≈ 按字数均分。
     */
    fun lineProgress(positionMs: Long, index: Int, fallbackMs: Long = 4000L): Float {
        if (index !in lines.indices) return 0f
        val start = lineStartMs(index)
        val end = lineEndMs(index, fallbackMs)
        if (end <= start) return 1f
        return ((positionMs - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f)
    }

    /**
     * 二分查找当前行。播放进度每 400ms 触发一次重组，歌词页每帧都要问一次，
     * 线性扫描在长歌词（几百行）上是白白的浪费。
     * 首句之前返回 -1（无当前行），不再回退成 0 —— 否则前奏期会把第 0 行
     * 误判为「正在唱」，歌词页据此居中/扫光，看起来就是定位错行。
     */
    private fun indexAt(t: Long): Int {
        var lo = 0
        var hi = lines.lastIndex
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= t) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return idx
    }
}
