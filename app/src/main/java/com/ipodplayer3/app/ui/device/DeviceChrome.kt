package com.ipodplayer3.app.ui.device

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ipodplayer3.app.ui.theme.IpodTheme
import com.ipodplayer3.app.ui.theme.LocalIpodTheme

/** Classic status bar: play marker + title only (no battery). */
@Composable
fun StatusBar(
    playing: Boolean,
    title: String = "",
    theme: IpodTheme = LocalIpodTheme.current,
) {
    val fg = theme.screenFg
    val muted = theme.screenMuted

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 最小高度：系统字体放大时 24dp 固定高度会把标题裁掉。
            .heightIn(min = 24.dp)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (playing) {
            Text("▶", color = muted, fontSize = 10.sp)
        }
        Text(
            text = title,
            color = fg,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = if (playing) 4.dp else 0.dp)
        )
    }
}

/**
 * Device body: LCD on top, click wheel below.
 * 屏幕与滚轮的权重比 = [screenWeight] : (1 - screenWeight)。
 */
@Composable
fun DeviceChrome(
    modifier: Modifier = Modifier,
    screenWeight: Float = SCREEN_WEIGHT,
    theme: IpodTheme = LocalIpodTheme.current,
    screenContent: @Composable () -> Unit,
    wheelContent: @Composable () -> Unit,
) {
    // 传 1.2f 会得到负权重并在布局期抛异常；这里先挡住。
    require(screenWeight in 0.2f..0.9f) { "screenWeight must be in 0.2..0.9" }

    Column(
        modifier = modifier
            .background(theme.body, RoundedCornerShape(28.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(screenWeight)
                .background(Color.Black, RoundedCornerShape(6.dp))
                .padding(1.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(theme.screenBg, RoundedCornerShape(5.dp))
                    // LCD 内层必须裁剪：全出血内容会盖住圆角，变成直角矩形。
                    .clip(RoundedCornerShape(5.dp))
            ) {
                screenContent()
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.weight(1f - screenWeight), contentAlignment = Alignment.Center) {
            wheelContent()
        }
    }
}

private const val SCREEN_WEIGHT = 0.60f
