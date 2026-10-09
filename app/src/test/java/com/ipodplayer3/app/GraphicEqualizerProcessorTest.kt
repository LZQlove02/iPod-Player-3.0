package com.ipodplayer3.app

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.ipodplayer3.app.data.player.GraphicEqualizerProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 均衡器处理器的纯数学行为（JVM 直测，不需要设备）。
 *
 * 关注三件曾经会出问题的事：
 * 1. EQ 关闭时**逐字节**原样输出（不能走 16bit↔float 有损往返）；
 * 2. 0 dB 近似恒等（系数算错会直接听出来）；
 * 3. NaN/Inf 增益不能让 biquad 输出 NaN（否则整首歌静音）。
 *
 * 注：`queueInput` 里的 float 分支在当前 Media3 配置下不可达
 * （DefaultRenderersFactory 默认关闭 float 输出），所以这里只测 16bit 链路。
 */
class GraphicEqualizerProcessorTest {

    // 注意参数顺序：(sampleRate, channelCount, encoding)
    private val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)

    private fun pcm(vararg samples: Int): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder()).apply {
            samples.forEach { putShort(it.toShort()) }
            flip()
        }

    private fun ShortArray.toBuffer(): ByteBuffer =
        ByteBuffer.allocateDirect(size * 2).order(ByteOrder.nativeOrder()).apply {
            this@toBuffer.forEach { putShort(it) }
            flip()
        }

    private fun drain(output: ByteBuffer): ShortArray {
        // duplicate() 的字节序不一定被继承，显式对齐原始缓冲的字节序。
        val copy = output.duplicate().order(output.order())
        val out = ShortArray(copy.remaining() / 2)
        for (i in out.indices) out[i] = copy.short
        return out
    }

    @Test
    fun disabled_isBitExact() {
        val processor = GraphicEqualizerProcessor()
        processor.setEnabled(false)
        processor.configure(format)
        processor.flush()

        val input = pcm(1, -1, 100, -100, 32767, -32768)
        processor.queueInput(input)
        val out = drain(processor.output)
        assertEquals(6, out.size)
        assertTrue(shortArrayOf(1, -1, 100, -100, 32767, -32768).contentEquals(out))
    }

    @Test
    fun zeroGain_isCloseToIdentity() {
        val processor = GraphicEqualizerProcessor()
        processor.setEnabled(true)
        processor.setGains(FloatArray(10))
        processor.configure(format)
        processor.flush()

        val samples = ShortArray(512) { (8000 * Math.sin(it / 8.0)).toInt().toShort() }
        processor.queueInput(samples.toBuffer())
        val out = drain(processor.output)
        assertEquals(samples.size, out.size)
        // 0 dB 的 peaking biquad 应为恒等；允许 1 LSB 的浮点误差
        var maxDiff = 0
        for (i in samples.indices) {
            maxDiff = maxOf(maxDiff, kotlin.math.abs(samples[i] - out[i]))
        }
        assertTrue("maxDiff=$maxDiff", maxDiff <= 1)
    }

    @Test
    fun nanGain_doesNotProduceNanOrSilence() {
        val processor = GraphicEqualizerProcessor()
        processor.setEnabled(true)
        // 入口会把 NaN 归零（coerceIn 拦不住 NaN）
        val gains = FloatArray(10)
        gains[0] = Float.NaN
        gains[1] = Float.POSITIVE_INFINITY
        processor.setGains(gains)
        assertEquals(0f, processor.getGains()[0], 0f)
        assertEquals(0f, processor.getGains()[1], 0f)

        processor.configure(format)
        processor.flush()
        val samples = ShortArray(256) { (4000 * Math.sin(it / 4.0)).toInt().toShort() }
        processor.queueInput(samples.toBuffer())
        val out = drain(processor.output)
        assertEquals(samples.size, out.size)
        // 输出不能全是 0（静音），也不能出现无意义的重复常量
        assertTrue(out.any { it != 0.toShort() })
    }

    @Test
    fun gainIsClampedToRange() {
        val processor = GraphicEqualizerProcessor()
        val gains = FloatArray(10) { 99f }
        processor.setGains(gains)
        assertTrue(processor.getGains().all { it <= GraphicEqualizerProcessor.MAX_GAIN_DB })
        processor.setBandGain(0, -99f)
        assertEquals(GraphicEqualizerProcessor.MIN_GAIN_DB, processor.getGains()[0], 0f)
    }

    @Test
    fun bypassDoesNotConsumeLessThanInput() {
        val processor = GraphicEqualizerProcessor()
        processor.setEnabled(false)
        processor.configure(format)
        processor.flush()
        val input = pcm(1, 2, 3, 4)
        processor.queueInput(input)
        // 输入必须被完整消费，否则 Media3 会认为还有数据没处理
        assertEquals(0, input.remaining())
    }
}
