package com.ipodplayer3.app.ui.device

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.SoundPool
import com.ipodplayer3.app.R
import kotlin.math.sin

/**
 * Short mechanical click for the click wheel.
 *
 * Uses [AudioAttributes.USAGE_MEDIA] so the click is mixed on the same
 * volume stream as music. USAGE_ASSISTANCE_SONIFICATION / system streams
 * go silent whenever the system volume is down while media plays — that
 * made 按键音 look "gone".
 */
object ClickSound {
    private var pool: SoundPool? = null
    private var soundId = 0

    /** 由 SoundPool 的加载回调线程写、主线程读，必须是 volatile。 */
    @Volatile
    private var loaded = false
    private var appContext: Context? = null

    /** 合成兜底用的可复用 track（见 [playSynthClick]）。 */
    private var synthTrack: AudioTrack? = null

    @Volatile
    private var enabled = true

    fun init(context: Context) {
        if (pool != null) return
        appContext = context.applicationContext
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        // 快速转轮时 4 条流不够：第 5 次 play 会掐掉最早那条（丢音）。
        pool = SoundPool.Builder().setMaxStreams(12).setAudioAttributes(attrs).build()
        soundId = pool!!.load(context, R.raw.click, 1)
        pool!!.setOnLoadCompleteListener { _, _, status ->
            loaded = status == 0
        }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun play() {
        if (!enabled) return
        val p = pool
        if (p != null && loaded && soundId != 0) {
            p.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f)
            return
        }
        // Resource still loading or failed — keep the wheel audible.
        playSynthClick()
    }

    /**
     * ~6ms media-stream tick generated in-process (no sample dependency).
     *
     * 复用一条 MODE_STATIC 的 AudioTrack：以前每次点击都新建一条，构造写 play
     * 都没包 runCatching（异常会从指针输入协程冒出来直接崩），而且 play 失败时
     * marker 不触发、release 不执行 —— 每个 notch 漏一条 native track。
     */
    private fun playSynthClick() {
        if (appContext == null) return
        val track = synthTrack ?: buildSynthTrack()?.also { synthTrack = it } ?: return
        runCatching {
            track.stop()
            track.flush()
            track.write(SYNTH_PCM, 0, SYNTH_PCM.size)
            track.play()
        }.onFailure {
            // 既不响又占着 native 资源：丢掉，下次重建。
            runCatching { track.release() }
            synthTrack = null
        }
    }

    private fun buildSynthTrack(): AudioTrack? {
        val minBuf = AudioTrack.getMinBufferSize(
            SYNTH_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return null
        return runCatching {
            AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(SYNTH_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                // MODE_STATIC 的缓冲必须 >= 采样数据长度，否则 write 会被截断。
                maxOf(minBuf, SYNTH_PCM.size * 2),
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            ).also { it.write(SYNTH_PCM, 0, SYNTH_PCM.size) }
        }.getOrNull()
    }

    /** 1800Hz 衰减包络，PCM 只生成一次（音量恒定，不随点击变化）。 */
    private val SYNTH_PCM: ShortArray by lazy {
        val n = (SYNTH_RATE * 0.006).toInt().coerceAtLeast(64)
        ShortArray(n) { i ->
            val t = i.toFloat() / SYNTH_RATE
            val env = 1f - i.toFloat() / n
            val s = sin(2.0 * Math.PI * 1800.0 * t).toFloat() * env * env
            (s * Short.MAX_VALUE * 0.45f).toInt().coerceIn(
                Short.MIN_VALUE.toInt(),
                Short.MAX_VALUE.toInt()
            ).toShort()
        }
    }

    private const val SYNTH_RATE = 22050
}
