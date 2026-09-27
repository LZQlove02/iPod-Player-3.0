package com.ipodplayer3.app.ui.device

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ipodplayer3.app.ui.theme.IpodTheme

@Composable
fun StatusBar(
    theme: IpodTheme,
    playing: Boolean,
    battery: Float = 0.85f,
    charging: Boolean = false,
    title: String = "",
) {
    val fg = if (theme.isDarkScreen) Color(0xFFF2F2F7) else Color(0xFF1D1D1F)
    val muted = if (theme.isDarkScreen) Color(0xFF98989D) else Color(0xFF6E6E73)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (playing) {
                Text("▶", color = muted, fontSize = 11.sp)
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = title,
                color = fg,
                fontSize = 12.sp,
                maxLines = 1
            )
        }
        BatteryIcon(fg, muted, battery, charging)
    }
}

@Composable
private fun BatteryIcon(fg: Color, muted: Color, level: Float, charging: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (charging) {
            Text("⚡", color = muted, fontSize = 11.sp)
            Spacer(Modifier.width(2.dp))
        }
        Canvas(Modifier.size(width = 22.dp, height = 12.dp)) {
            val stroke = 1.5.dp.toPx()
            drawRoundRect(
                color = fg,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                style = Stroke(stroke)
            )
            val fillW = (size.width - 6.dp.toPx()) * level.coerceIn(0f, 1f)
            if (fillW > 0) {
                drawRoundRect(
                    color = if (level < 0.2f) Color(0xFFD70015) else fg,
                    topLeft = androidx.compose.ui.geometry.Offset(2.dp.toPx(), 2.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(fillW, size.height - 4.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx())
                )
            }
            // nub
            drawRoundRect(
                color = fg,
                topLeft = androidx.compose.ui.geometry.Offset(size.width, size.height * 0.3f),
                size = androidx.compose.ui.geometry.Size(2.5.dp.toPx(), size.height * 0.4f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx())
            )
        }
    }
}

@Composable
fun DeviceChrome(
    theme: IpodTheme,
    modifier: Modifier = Modifier,
    screenWeight: Float = 0.58f,
    screenContent: @Composable () -> Unit,
    wheelContent: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .background(theme.body, RoundedCornerShape(28.dp))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(screenWeight)
                .background(
                    if (theme.isDarkScreen) Color(0xFF000000) else Color(0xFFFFFFFF),
                    RoundedCornerShape(6.dp)
                )
                .padding(1.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (theme.isDarkScreen) Color(0xFF1C1C1E) else Color(0xFFF5F5F7),
                        RoundedCornerShape(5.dp)
                    )
            ) {
                screenContent()
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.weight(1f - screenWeight), contentAlignment = Alignment.Center) {
            wheelContent()
        }
    }
}
