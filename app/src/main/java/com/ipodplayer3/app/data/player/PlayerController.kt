package com.ipodplayer3.app.data.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.ipodplayer3.app.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RepeatMode { OFF, ONE, ALL }

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

    private var queue: List<Song> = emptyList()

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
            val c = future.get()
            controller = c
            c.addListener(listener)
            _isPlaying.value = c.isPlaying
            _durationMs.value = c.duration.takeIf { it > 0 } ?: 0L
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
    }

    fun playQueue(songs: List<Song>, startIndex: Int = 0) {
        queue = songs
        val items = songs.map { it.toMediaItem() }
        val c = controller ?: return
        c.setMediaItems(items, startIndex, 0L)
        c.prepare()
        c.play()
        _currentSong.value = songs.getOrNull(startIndex)
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

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        val target = (c.currentPosition + deltaMs).coerceIn(0L, c.duration.takeIf { it > 0 } ?: 0L)
        c.seekTo(target)
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

    fun cycleRepeat() {
        setRepeat(
            when (_repeat.value) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
        )
    }

    fun pollPosition() {
        val c = controller ?: return
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

    fun setEqualizer(enabled: Boolean, bassBoost: Int, treble: Int) {
        runCatching {
            val c = controller ?: return
            val p = (c as? Player)?.let { (it as? androidx.media3.exoplayer.ExoPlayer) } ?: return
            // Simple loudness / pitch-free shaping via audio processor placeholder:
            // Media3 built-in EQ is device-dependent; we expose settings for UI and
            // apply gain via setVolume-style presets where available.
            p.volume = when {
                !enabled -> 1f
                bassBoost > 0 -> 0.98f
                else -> 1f
            }
        }
    }
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
