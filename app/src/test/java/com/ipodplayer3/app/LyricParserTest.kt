package com.ipodplayer3.app

import com.ipodplayer3.app.data.lyrics.LyricParser
import com.ipodplayer3.app.data.model.LyricLine
import com.ipodplayer3.app.data.model.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricParserTest {

    @Test
    fun parseLrc_basicTimes() {
        val lrc = """
            [00:01.00]第一句
            [00:05.50]第二句
            [01:02]第三句
        """.trimIndent()
        val lyrics = LyricParser.parseLrc(lrc)
        assertEquals(3, lyrics.lines.size)
        assertEquals(1000L, lyrics.lines[0].timeMs)
        assertEquals(5500L, lyrics.lines[1].timeMs)
        assertEquals(62000L, lyrics.lines[2].timeMs)
        assertEquals("第一句", lyrics.lines[0].text)
    }

    @Test
    fun parseLrc_multiTimestamp() {
        val lrc = "[00:01.00][00:10.00]副歌"
        val lyrics = LyricParser.parseLrc(lrc)
        assertEquals(2, lyrics.lines.size)
        assertEquals("副歌", lyrics.lines[0].text)
    }

    @Test
    fun parseLrc_fractionDigits() {
        // 1/2/3/4 位小数分别是 ×100 / ×10 / ×1 / ÷10 毫秒
        val lyrics = LyricParser.parseLrc(
            "[00:01.5]a\n[00:02.25]b\n[00:03.125]c\n[00:04.0625]d"
        )
        assertEquals(1500L, lyrics.lines[0].timeMs)
        assertEquals(2250L, lyrics.lines[1].timeMs)
        assertEquals(3125L, lyrics.lines[2].timeMs)
        assertEquals(4062L, lyrics.lines[3].timeMs)
    }

    @Test
    fun parseLrc_skipsMetadataAndEmptyLines() {
        val lrc = """
            [ti:标题]
            [ar:歌手]
            [00:15.00]
            [00:20.00]真正的第一句
        """.trimIndent()
        val lyrics = LyricParser.parseLrc(lrc)
        // 只有时间标签、没有文字的行不该生成空歌词行
        assertEquals(1, lyrics.lines.size)
        assertEquals("真正的第一句", lyrics.lines[0].text)
    }

    @Test
    fun parseLrc_offsetTag() {
        val lyrics = LyricParser.parseLrc("[offset:+500]\n[00:01.00]a")
        assertEquals(500L, lyrics.offsetMs)
        assertEquals(1, lyrics.lines.size)
        val negative = LyricParser.parseLrc("[offset:-200]\n[00:01.00]a")
        assertEquals(-200L, negative.offsetMs)
    }

    @Test
    fun parseLrc_crlfAndBomResidue() {
        val lyrics = LyricParser.parseLrc("\uFEFF[00:01.00]第一句\r\n[00:02.00]第二句")
        assertEquals(2, lyrics.lines.size)
        assertEquals("第一句", lyrics.lines[0].text)
    }

    @Test
    fun decodeLrc_utf8Bom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "[00:01.00]第一句".toByteArray(Charsets.UTF_8)
        val text = LyricParser.decodeLrc(bytes)
        assertFalse(text.startsWith("\uFEFF"))
        assertEquals("[00:01.00]第一句", text)
        assertEquals("第一句", LyricParser.parseLrc(text).lines[0].text)
    }

    @Test
    fun decodeLrc_gb18030Fallback() {
        // 中文 LRC 常见编码：GBK/GB18030。硬编码 UTF-8 会得到满屏乱码。
        val gbk = "[00:01.00]第一句".toByteArray(charset("GB18030"))
        val text = LyricParser.decodeLrc(gbk)
        assertEquals("[00:01.00]第一句", text)
        assertEquals("第一句", LyricParser.parseLrc(text).lines[0].text)
    }

    @Test
    fun decodeLrc_utf16() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "[00:01.00]a".toByteArray(Charsets.UTF_16LE)
        assertEquals("[00:01.00]a", LyricParser.decodeLrc(le))
    }

    @Test
    fun activeIndex_resolvesCurrentLine() {
        val lyrics = Lyrics(
            listOf(
                LyricLine(0, "a"),
                LyricLine(1000, "b"),
                LyricLine(5000, "c"),
            )
        )
        assertEquals(0, lyrics.activeIndex(0L))
        assertEquals(1, lyrics.activeIndex(1500L))
        assertEquals(2, lyrics.activeIndex(9000L))
        assertEquals(1, lyrics.activeIndex(1200L))
        assertEquals(2, lyrics.activeIndex(5000L))
    }

    @Test
    fun activeIndex_boundaries() {
        val lyrics = Lyrics(
            listOf(
                LyricLine(1000, "a"),
                LyricLine(2000, "b"),
                LyricLine(2000, "b2"),
                LyricLine(9000, "c"),
            )
        )
        // 首句尚未开始：返回 -1 表示「无当前行」，不能回退成第 0 行
        assertEquals(-1, lyrics.activeIndex(0L))
        // 时间戳相同时取最后一个（稳定排序后的最后一个匹配项）
        assertEquals(2, lyrics.activeIndex(2000L))
        assertEquals(3, lyrics.activeIndex(99999L))
        assertEquals(-1, Lyrics(emptyList()).activeIndex(0L))
    }

    @Test
    fun lineStartEnd_respectsOffset() {
        // offsetMs 语义：t = position - offsetMs，行起点折回 position 要加回 offset。
        val lyrics = Lyrics(
            listOf(LyricLine(1000, "a"), LyricLine(3000, "b")),
            offsetMs = 500,
        )
        assertEquals(1500L, lyrics.lineStartMs(0))
        assertEquals(3500L, lyrics.lineEndMs(0))
        assertEquals(3500L, lyrics.lineStartMs(1))
        // 末行用 fallback 兜底
        assertEquals(3500L + 4000L, lyrics.lineEndMs(1))
        assertEquals(3500L + 123L, lyrics.lineEndMs(1, fallbackMs = 123L))
    }

    @Test
    fun lineProgress_withinLine() {
        val lyrics = Lyrics(
            listOf(LyricLine(0, "a"), LyricLine(1000, "b")),
        )
        assertEquals(0f, lyrics.lineProgress(0L, 0), 1e-4f)
        assertEquals(0.5f, lyrics.lineProgress(500L, 0), 1e-4f)
        assertEquals(1f, lyrics.lineProgress(1000L, 0), 1e-4f)
        // 末行没有下一句：1000..5000（fallback 4s），2000ms 处正好 0.25
        assertEquals(0.25f, lyrics.lineProgress(2000L, 1), 1e-4f)
        assertEquals(0.0625f, lyrics.lineProgress(1250L, 1), 1e-4f)
    }

    @Test
    fun lineProgress_clampsAndOutOfRange() {
        val lyrics = Lyrics(listOf(LyricLine(1000, "a"), LyricLine(2000, "b")))
        // 起点之前不越界
        assertEquals(0f, lyrics.lineProgress(0L, 0), 1e-4f)
        // 末行播完之后夹到 1
        assertEquals(1f, lyrics.lineProgress(99999L, 1), 1e-4f)
        // 非法下标返回 0
        assertEquals(0f, lyrics.lineProgress(1500L, 5), 1e-4f)
    }

    @Test
    fun lineProgress_lastLineFallback() {
        val lyrics = Lyrics(listOf(LyricLine(0, "only")))
        assertEquals(0f, lyrics.lineProgress(0L, 0), 1e-4f)
        assertEquals(0.5f, lyrics.lineProgress(2000L, 0), 1e-4f)
        assertEquals(1f, lyrics.lineProgress(4000L, 0), 1e-4f)
    }
}
