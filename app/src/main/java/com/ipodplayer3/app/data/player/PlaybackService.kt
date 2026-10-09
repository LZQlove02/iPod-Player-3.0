package com.ipodplayer3.app.data.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ipodplayer3.app.MainActivity

/**
 * Process-wide audio FX entry so the UI can drive EQ while playback
 * lives inside [PlaybackService] (same process).
 */
@OptIn(markerClass = [UnstableApi::class])
object AudioFx {
    val eq = EqController()

    @UnstableApi
    fun onPlayerCreated(exo: ExoPlayer) {
        eq.attach(exo)
    }

    fun onPlayerReleased() {
        eq.release()
    }
}

@UnstableApi
class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    /**
     * 会话就绪后重挂 AudioFx 的监听。必须留一份引用：onDestroy 里要摘掉，
     * 否则这个匿名内部类（持有本 Service 与 ExoPlayer）会一直挂在播放器上。
     */
    private var audioFxListener: Player.Listener? = null

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink {
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(AudioFx.eq.processor))
                    .build()
            }
        }

        val exo = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, exo)
            .setSessionActivity(sessionActivity)
            .build()

        player = exo
        AudioFx.onPlayerCreated(exo)
        // Re-bind AudioEffects after the session is fully ready.
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    AudioFx.onPlayerCreated(exo)
                }
            }
        }
        audioFxListener = listener
        exo.addListener(listener)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        AudioFx.onPlayerReleased()
        // 先摘监听、再 release：留着它，已销毁的 Service 仍会被回调到。
        audioFxListener?.let { listener -> player?.removeListener(listener) }
        audioFxListener = null
        // 显式用本类的 player 字段，别靠 run 的接收者解析到 MediaSession.player
        // （恰好是同一实例，但语义上容易被误改）。
        player?.release()
        mediaSession?.release()
        mediaSession = null
        player = null
        super.onDestroy()
    }
}
