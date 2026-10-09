package com.ipodplayer3.app.data.player

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Tone control for playback.
 *
 * - [GraphicEqualizerProcessor] is the **only** gain stage (true 10-band).
 * - Platform [Equalizer]/[BassBoost]/[Virtualizer] are bound to the audio
 *   session for device compatibility, but left **disabled** so the same
 *   spectrum is not boosted twice.
 */
@UnstableApi
class EqController {

    private var systemEqualizer: Equalizer? = null
    private var systemBass: BassBoost? = null
    private var systemVirtual: Virtualizer? = null
    private var attachedSession: Int = 0

    private val graphic = GraphicEqualizerProcessor()

    val processor: GraphicEqualizerProcessor get() = graphic

    fun attach(exo: ExoPlayer) {
        val session = runCatching { exo.audioSessionId }.getOrNull() ?: return
        if (session == 0 || session == android.media.AudioManager.ERROR) return
        // STATE_READY 每首歌都会触发一次。只按 session 判断：若还要求
        // systemEqualizer != null，那么在不支持 Equalizer 的机型上会「每首歌
        // 重建一次」BassBoost/Virtualizer，反复占用音频效果器槽位。
        if (session == attachedSession) return

        releaseSystemEffects()
        attachedSession = session

        runCatching {
            systemEqualizer = Equalizer(0, session).also { eq ->
                // Keep disabled: graphic processor applies the 10-band curve.
                eq.enabled = false
                Log.d("EqController", "system EQ bands=${eq.numberOfBands} (disabled, graphic is master)")
            }
        }.onFailure { Log.w("EqController", "no system Equalizer", it) }

        runCatching { systemBass = BassBoost(0, session).also { it.enabled = false } }
        runCatching { systemVirtual = Virtualizer(0, session).also { it.enabled = false } }
    }

    fun release() {
        releaseSystemEffects()
    }

    private fun releaseSystemEffects() {
        runCatching { systemEqualizer?.release() }
        runCatching { systemBass?.release() }
        runCatching { systemVirtual?.release() }
        systemEqualizer = null
        systemBass = null
        systemVirtual = null
        attachedSession = 0
    }

    fun setEnabled(enabled: Boolean) {
        graphic.setEnabled(enabled)
    }

    fun setBand(index: Int, gainDb: Float) {
        graphic.setBandGain(index, gainDb)
    }

    fun setAllBands(gains: FloatArray) {
        graphic.setGains(gains)
    }

    fun applyPreset(name: String) {
        graphic.applyPreset(name)
    }

    fun gains(): FloatArray = graphic.getGains()
}
