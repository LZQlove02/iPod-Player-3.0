package com.ipodplayer3.app

import com.ipodplayer3.app.ui.device.formatTime
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTimeTest {

    @Test
    fun formatTime_values() {
        assertEquals("0:00", formatTime(0))
        assertEquals("0:01", formatTime(1000))
        assertEquals("1:05", formatTime(65000))
        assertEquals("12:34", formatTime((12 * 60 + 34) * 1000L))
    }

    @Test
    fun formatTime_hoursAndNegatives() {
        // 超过一小时（有声书/长混音）不能输出 "123:45"
        assertEquals("1:02:03", formatTime(((60 + 2) * 60 + 3) * 1000L))
        assertEquals("2:00:00", formatTime(2 * 60 * 60 * 1000L))
        // 负进度（seek 抖动）按 0 处理，不出现 "-1:00"
        assertEquals("0:00", formatTime(-5000))
    }
}
