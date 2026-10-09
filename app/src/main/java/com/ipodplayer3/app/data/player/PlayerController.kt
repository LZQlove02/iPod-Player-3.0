package com.ipodplayer3.app.data.player

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.ipodplayer3.app.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RepeatMode { OFF, ONE, ALL }

/** How often a continuous scrub is allowed to call seekTo (~25 Hz). */
private const val SCRUB_SEEK_THROTTLE_MS = 40L
/** Keep showing the committed scrub target until Media3 seek lands. */
private const val PENDING_SEEK_GRACE_MS = 600L

@OptIn(markerClass = [UnstableApi::class])
class PlayerController(private val context: Context) {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _repeat = MutableStateFlow(RepeatMode.OFF)
    val repeat: StateFlow<RepeatMode> = _repeat.asStateFlow()

    /**
     * 与 MediaController 的 mediaItem 一一下标对齐。
     * 元素为 null 表示「Service 里有、但曲库已查不到」——保留占位以免后续索引错位。
     */
    private var queue: List<Song?> = emptyList()
    private var pendingStartIndex: Int? = null
    /**
     * MediaController 尚未连上时 [restoreQueue] 会拿到解析器却补不了队列。
     * 先记下，等 connect 回调里再补做（否则状态栏 / 正在播放永远是空的）。
     */
    private var pendingRestore: ((Long) -> Song?)? = null

    /** Stepless scrub session: absolute target = base + accum. */
    private var scrubbing = false
    private var scrubBaseMs = 0L
    private var scrubAccumMs = 0L
    private var lastScrubSeekAt = 0L
    /**
     * After endScrub, Media3 may report the old playhead until seek lands.
     * Hold the committed target so pollPosition cannot snap the UI backwards.
     */
    private var pendingSeekTargetMs: Long? = null
    private var pendingSeekDeadline = 0L

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val index = controller?.currentMediaItemIndex ?: return
            _currentSong.value = queue.getOrNull(index)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _durationMs.value = controller?.duration?.takeIf { it > 0 } ?: 0L
        }
    }

    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener({
            // get() 抛 ExecutionException（Service 绑不上等）时监听器跑在 directExecutor
            // 上会直接崩；必须吞掉并清掉 future，否则此后所有 connect() 重试都被
            // `if (controllerFuture != null) return` 挡掉，播放器永久失联。
            val c = try {
                future.get()
            } catch (_: Exception) {
                if (controllerFuture === future) {
                    runCatching { MediaController.releaseFuture(future) }
                    controllerFuture = null
                }
                return@addListener
            }
            // release() 可能已经先到：future 随后才完成，此时控制器已被释放，
            // 再挂 listener 就是留一个没人用也没人解绑的 controller（轻微泄漏）。
            if (controllerFuture !== future) {
                runCatching { MediaController.releaseFuture(future) }
                return@addListener
            }
            controller = c
            c.addListener(listener)
            // 连上后补做本地已保存但尚未生效的开关：冷启动时用户可能在
            // MediaController 就绪前就切了 shuffle/repeat，当时只写了本地 StateFlow。
            c.shuffleModeEnabled = _shuffle.value
            c.repeatMode = when (_repeat.value) {
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            }
            _isPlaying.value = c.isPlaying
            _durationMs.value = c.duration.takeIf { it > 0 } ?: 0L
            // 曲库可能先于 MediaController 就绪（划掉 App 再进）：补做队列恢复。
            val restore = pendingRestore
            if (restore != null) {
                pendingRestore = null
                applyRestoredQueue(c, restore)
            }
            val pending = pendingStartIndex
            if (pending != null && queue.isNotEmpty()) {
                pendingStartIndex = null
                // pendingStartIndex 只由 playQueue 写入，那时 queue 全非空；
                // restoreQueue 不会抢在它前面覆盖（它要求 queue 为空）。
                playQueue(queue.filterNotNull(), pending)
            }
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        pendingRestore = null
        pendingStartIndex = null
        // 清掉本地队列/曲目：否则同进程重建 Activity 时「正在播放」会显示
        // 上次残留的歌。Service 若还在放，下一次 restoreQueue 会从 mediaId 补回。
        queue = emptyList()
        _currentSong.value = null
        _isPlaying.value = false
        _positionMs.value = 0L
        _durationMs.value = 0L
    }

    fun playQueue(songs: List<Song>, startIndex: Int = 0) {
        queue = songs
        val c = controller
        if (c == null) {
            pendingStartIndex = startIndex
            connect()
            return
        }
        val items = songs.map { it.toMediaItem() }
        c.setMediaItems(items, startIndex, 0L)
        c.prepare()
        c.play()
        _currentSong.value = songs.getOrNull(startIndex)
    }

    /**
     * 进程被杀后 Service 里可能还在放，而本进程的 [queue] 是空的 —— 界面于是
     * 显示不出「正在播放」的是什么。用 MediaController 里的 mediaId 反查曲库，
     * 把队列和当前曲目补回来（不碰播放状态，避免打断正在放的声音）。
     *
     * @param resolve 按 id 取**原始（未去重）**曲目的解析器。不能直接收
     *   `List<Song>`：界面列表是去重后的视图，恰好被合并掉的那条会反查不到，
     *   「正在播放」就凭空空了。
     */
    fun restoreQueue(resolve: (Long) -> Song?) {
        val c = controller
        if (c == null) {
            // 连接还在路上：记下解析器，connect 成功后补做；失败则下次 loadLibrary 再试。
            pendingRestore = resolve
            connect()
            return
        }
        applyRestoredQueue(c, resolve)
    }

    private fun applyRestoredQueue(c: MediaController, resolve: (Long) -> Song?) {
        if (queue.isNotEmpty() || c.mediaItemCount == 0) return
        // 与 MediaController 的 mediaItemCount 一一下标对齐；查不到的 id 留 null，
        // 不能整批丢弃 —— 只要有一首被删/换机，整个「正在播放」就会永远是空的。
        val restored = (0 until c.mediaItemCount).map { i ->
            c.getMediaItemAt(i).mediaId.toLongOrNull()?.let(resolve)
        }
        if (restored.all { it == null }) return
        queue = restored
        _currentSong.value = queue.getOrNull(c.currentMediaItemIndex)
        _durationMs.value = c.duration.takeIf { it > 0 } ?: 0L
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > 3000) {
            c.seekTo(0)
        } else {
            c.seekToPreviousMediaItem()
        }
    }

    /**
     * Start a continuous (stepless) scrub. Position freezes at the playhead
     * and subsequent [scrubBy] deltas move a local target without quantizing.
     */
    private fun beginScrub() {
        val c = controller ?: return
        scrubBaseMs = c.currentPosition
        scrubAccumMs = 0L
        scrubbing = true
        lastScrubSeekAt = 0L
        pendingSeekTargetMs = null
        _positionMs.value = scrubBaseMs
    }

    /**
     * Apply a continuous scrub delta in ms. UI position updates every call;
     * the actual seek is throttled so a fast spin does not flood Media3.
     */
    fun scrubBy(deltaMs: Long) {
        val c = controller ?: return
        if (!scrubbing) beginScrub()
        scrubAccumMs += deltaMs
        val duration = c.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val raw = scrubBaseMs + scrubAccumMs
        val target = raw.coerceIn(0L, duration)
        // Absorb end-stops so a reverse swipe reacts immediately
        // instead of undoing invisible overshoot first.
        if (raw != target) scrubAccumMs = target - scrubBaseMs
        _positionMs.value = target
        val now = SystemClock.elapsedRealtime()
        if (now - lastScrubSeekAt >= SCRUB_SEEK_THROTTLE_MS) {
            c.seekTo(target)
            lastScrubSeekAt = now
        }
    }

    /** Commit the final scrub position and close the session. Returns true if a session was open. */
    fun endScrub(): Boolean {
        if (!scrubbing) return false
        scrubbing = false
        val c = controller ?: return true
        // Unknown duration must not collapse the target to 0.
        val duration = c.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (scrubBaseMs + scrubAccumMs).coerceIn(0L, duration)
        c.seekTo(target)
        _positionMs.value = target
        pendingSeekTargetMs = target
        pendingSeekDeadline = SystemClock.elapsedRealtime() + PENDING_SEEK_GRACE_MS
        return true
    }

    fun setShuffle(enabled: Boolean) {
        _shuffle.value = enabled
        controller?.shuffleModeEnabled = enabled
    }

    fun setRepeat(mode: RepeatMode) {
        _repeat.value = mode
        val c = controller ?: return
        c.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        }
    }

    fun pollPosition() {
        val c = controller ?: return
        // Keep the UI on the scrub target; playhead lags behind seekTo.
        if (scrubbing) {
            if (_durationMs.value <= 0 && c.duration > 0) {
                _durationMs.value = c.duration
            }
            return
        }
        pendingSeekTargetMs?.let { pending ->
            val now = SystemClock.elapsedRealtime()
            val landed = kotlin.math.abs(c.currentPosition - pending) < 750L
            if (landed || now >= pendingSeekDeadline) {
                pendingSeekTargetMs = null
            } else {
                // Seek still in flight — do not snap the UI back.
                _positionMs.value = pending
                return
            }
        }
        _positionMs.value = c.currentPosition
        if (_durationMs.value <= 0 && c.duration > 0) {
            _durationMs.value = c.duration
        }
        if (c.isPlaying) {
            val idx = c.currentMediaItemIndex
            val expected = queue.getOrNull(idx)
            if (expected != null && expected.id != _currentSong.value?.id) {
                _currentSong.value = expected
            }
        }
    }

    /**
     * Toggle EQ only. The 10-band curve is owned by [setEqBand]/[applyEqPreset]
     * and must not be recomputed here — otherwise each settings emission wipes
     * the user's curve.
     */
    fun setEqEnabled(enabled: Boolean) {
        AudioFx.eq.setEnabled(enabled)
    }

    fun setEqBand(index: Int, gainDb: Float) {
        AudioFx.eq.setBand(index, gainDb)
    }

    /** Restore a whole curve at once (saved settings on start-up). */
    fun setEqBands(gains: FloatArray) {
        AudioFx.eq.setAllBands(gains)
    }

    fun applyEqPreset(name: String) {
        AudioFx.eq.applyPreset(name)
    }

    fun eqGains(): FloatArray = AudioFx.eq.gains()
}

private fun Song.toMediaItem(): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setArtworkUri(albumArtUri)
        .build()
    return MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(metadata)
        .build()
}
