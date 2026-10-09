package com.ipodplayer3.app.data.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 10-band graphic equalizer as a Media3 AudioProcessor.
 * Peaking biquads at ~ISO centers; coefficients rebuilt only when gains change.
 */
@UnstableApi
class GraphicEqualizerProcessor : BaseAudioProcessor() {

    companion object {
        val CENTER_HZ = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
        const val BAND_COUNT = 10
        const val MAX_GAIN_DB = 12f
        const val MIN_GAIN_DB = -12f
    }

    private val gainsDb = FloatArray(BAND_COUNT)
    /** Guards [gainsDb]: UI thread writes, audio thread reads in [computeCoefficients]. */
    private val gainsLock = Any()

    /**
     * 默认关闭，与 SettingsRepository 的默认值一致。否则 DataStore 收集器跑之前
     * 处理器是「开」状态（当前增益 0 dB 虽恒等，但语义不一致，容易埋雷）。
     */
    @Volatile
    private var enabled = false

    @Volatile
    private var coefficientsDirty = true

    private var sampleRate = 44100
    private var channelCount = 2

    private lateinit var x1: Array<FloatArray>
    private lateinit var x2: Array<FloatArray>
    private lateinit var y1: Array<FloatArray>
    private lateinit var y2: Array<FloatArray>
    private val b0 = FloatArray(BAND_COUNT)
    private val b1 = FloatArray(BAND_COUNT)
    private val b2 = FloatArray(BAND_COUNT)
    private val a1 = FloatArray(BAND_COUNT)
    private val a2 = FloatArray(BAND_COUNT)

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun setBandGain(index: Int, gainDb: Float) {
        synchronized(gainsLock) {
            if (index !in gainsDb.indices) return
            gainsDb[index] = gainDb.safeGainDb()
        }
        coefficientsDirty = true
    }

    fun setGains(gains: FloatArray) {
        synchronized(gainsLock) {
            for (i in gainsDb.indices) {
                gainsDb[i] = gains.getOrElse(i) { 0f }.safeGainDb()
            }
        }
        coefficientsDirty = true
    }

    /**
     * NaN / Infinity 会让 biquad 系数变成 NaN，进而让整首歌静音；而 coerceIn 对
     * NaN 的比较恒为 false，拦不住它。所以先在入口归零。
     */
    private fun Float.safeGainDb(): Float =
        if (isFinite()) coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) else 0f

    fun getGains(): FloatArray = synchronized(gainsLock) { gainsDb.copyOf() }

    fun applyPreset(name: String) {
        val shape = when (name.lowercase()) {
            "pop" -> floatArrayOf(-1f, 1f, 3f, 4f, 3f, 1f, -1f, -1f, 0f, 0f)
            "rock" -> floatArrayOf(4f, 3f, 1f, -1f, -2f, 0f, 2f, 3f, 4f, 4f)
            "classical" -> floatArrayOf(3f, 2f, 1f, 0f, 0f, 0f, -1f, 1f, 2f, 3f)
            "jazz" -> floatArrayOf(2f, 1f, 0f, 1f, 2f, 2f, 1f, 2f, 3f, 3f)
            "bass" -> floatArrayOf(7f, 6f, 4f, 2f, 0f, -1f, -1f, 0f, 1f, 2f)
            else -> FloatArray(BAND_COUNT)
        }
        setGains(shape)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        ensureState()
        coefficientsDirty = true
        return inputAudioFormat
    }

    override fun onFlush() {
        if (::x1.isInitialized) {
            for (c in x1.indices) {
                x1[c].fill(0f)
                x2[c].fill(0f)
                y1[c].fill(0f)
                y2[c].fill(0f)
            }
        }
    }

    override fun onReset() {
        onFlush()
    }

    /**
     * Must depend only on the configured format. Tying this to [enabled] would
     * make `AudioProcessingPipeline` drop the processor at the next flush when
     * EQ is off at configure time, and re-enabling EQ then has no effect until
     * a later flush. Disabling is handled as a true bypass in [queueInput].
     */
    override fun isActive(): Boolean = super.isActive()

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val format = inputAudioFormat
        // 整块快照一次开关：否则同一个 buffer 可能前半直通、后半被滤波（切开关时咔哒），
        // 同时也省掉每个样本一次 volatile 读。
        val eqOn = enabled

        if (!eqOn) {
            // True bypass: copy PCM as-is. The 16-bit float round-trip is lossy
            // (v/32768 → *32767 → toInt truncates small samples to zero), so a
            // disabled EQ must not run it.
            val output = replaceOutputBuffer(inputBuffer.remaining())
            output.put(inputBuffer)
            output.flip()
            inputBuffer.position(inputBuffer.limit())
            return
        }

        sampleRate = format.sampleRate
        channelCount = format.channelCount
        ensureState()
        if (coefficientsDirty) {
            // 先清标志再算系数：若这中间 UI 又改了增益，标志会被重新置起、
            // 下一块再算一遍；反过来写（先算后清）会吞掉那次改动 ——
            // 表现就是「滚轮调了没反应」。
            coefficientsDirty = false
            computeCoefficients()
        }

        val output = replaceOutputBuffer(inputBuffer.remaining())

        if (format.encoding == C.ENCODING_PCM_16BIT) {
            val frames = inputBuffer.remaining() / (2 * channelCount)
            var written = 0
            repeat(frames) {
                for (c in 0 until channelCount) {
                    val s = inputBuffer.short / 32768f
                    val y = processSample(c, s)
                    // *32768 (not 32767) keeps the mapping reversible for the
                    // in-range samples the biquad produces; clamp still guards overflow.
                    output.putShort((y * 32768f).toInt().coerceIn(-32768, 32767).toShort())
                    written += 2
                }
            }
            inputBuffer.position(inputBuffer.limit())
            output.position(0)
            output.limit(written)
        } else {
            val frames = inputBuffer.remaining() / (4 * channelCount)
            var written = 0
            repeat(frames) {
                for (c in 0 until channelCount) {
                    val s = inputBuffer.float
                    output.putFloat(processSample(c, s))
                    written += 4
                }
            }
            inputBuffer.position(inputBuffer.limit())
            output.position(0)
            output.limit(written)
        }
    }

    private fun processSample(channel: Int, input: Float): Float {
        var x = input
        val xs1 = x1[channel]
        val xs2 = x2[channel]
        val ys1 = y1[channel]
        val ys2 = y2[channel]
        for (band in 0 until BAND_COUNT) {
            val y = (b0[band] * x + b1[band] * xs1[band] + b2[band] * xs2[band] -
                a1[band] * ys1[band] - a2[band] * ys2[band])
            xs2[band] = xs1[band]
            xs1[band] = x
            ys2[band] = ys1[band]
            ys1[band] = y
            x = y
        }
        return x
    }

    private fun ensureState() {
        if (::x1.isInitialized && x1.size == channelCount) return
        x1 = Array(channelCount) { FloatArray(BAND_COUNT) }
        x2 = Array(channelCount) { FloatArray(BAND_COUNT) }
        y1 = Array(channelCount) { FloatArray(BAND_COUNT) }
        y2 = Array(channelCount) { FloatArray(BAND_COUNT) }
    }

    private fun computeCoefficients() {
        val fs = sampleRate.toDouble().coerceAtLeast(8000.0)
        // Snapshot under the lock so a concurrent UI write cannot tear a band.
        val gainsSnapshot = synchronized(gainsLock) { gainsDb.copyOf() }
        for (i in 0 until BAND_COUNT) {
            val gainDb = gainsSnapshot[i].toDouble()
            val f0 = CENTER_HZ[i].coerceAtMost(fs / 2.2)
            val q = 1.1
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * f0 / fs
            val alpha = sin(w0) / (2.0 * q)
            val cosw = cos(w0)

            val b0c = 1.0 + alpha * a
            val b1c = -2.0 * cosw
            val b2c = 1.0 - alpha * a
            val a0 = 1.0 + alpha / a
            val a1c = -2.0 * cosw
            val a2c = 1.0 - alpha / a

            b0[i] = (b0c / a0).toFloat()
            b1[i] = (b1c / a0).toFloat()
            b2[i] = (b2c / a0).toFloat()
            a1[i] = (a1c / a0).toFloat()
            a2[i] = (a2c / a0).toFloat()
        }
    }
}
