package com.ipodplayer3.app.ui.device

import android.view.SoundEffectConstants
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.ipodplayer3.app.ui.theme.IpodTheme
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Classic Click Wheel:
 * - drag around the ring → scroll events
 * - MENU / ▶❙❙ / ⏮ / ⏭ zones
 * - center = select
 */
@Composable
fun ClickWheel(
    theme: IpodTheme,
    diameter: Dp = 200.dp,
    soundEnabled: Boolean = true,
    vibrateEnabled: Boolean = true,
    onScroll: (Int) -> Unit,
    onMenu: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSelect: () -> Unit,
) {
    val view = LocalView.current
    var lastAngle by remember { mutableFloatStateOf(Float.NaN) }
    var accum by remember { mutableFloatStateOf(0f) }
    var downPos by remember { mutableStateOf(Offset.Zero) }
    var dragged by remember { mutableStateOf(false) }

    fun clickFeedback() {
        if (soundEnabled) view.playSoundEffect(SoundEffectConstants.CLICK)
        if (vibrateEnabled) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    Box(
        modifier = Modifier
            .size(diameter)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        downPos = offset
                        dragged = false
                        lastAngle = Float.NaN
                        accum = 0f
                    },
                    onDrag = { change, _ ->
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val angle = atan2(change.position.y - c.y, change.position.x - c.x)
                        if (lastAngle.isNaN()) {
                            lastAngle = angle
                            return@detectDragGestures
                        }
                        var delta = Math.toDegrees((angle - lastAngle).toDouble()).toFloat()
                        if (delta > 180f) delta -= 360f
                        if (delta < -180f) delta += 360f
                        lastAngle = angle
                        accum += delta
                        // ~12° per click, like the original wheel
                        val steps = (accum / 12f).toInt()
                        if (steps != 0) {
                            accum -= steps * 12f
                            dragged = true
                            onScroll(steps)
                            clickFeedback()
                        }
                    },
                    onDragEnd = {
                        if (!dragged) {
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val x = downPos.x - c.x
                            val y = downPos.y - c.y
                            val dist = kotlin.math.hypot(x.toDouble(), y.toDouble())
                            val radius = minOf(size.width, size.height) / 2.0
                            if (dist < radius * 0.38) {
                                clickFeedback()
                                onSelect()
                            } else {
                                val deg = (Math.toDegrees(atan2(y.toDouble(), x.toDouble())) + 360.0) % 360.0
                                when {
                                    deg in 200.0..340.0 -> { clickFeedback(); onMenu() }
                                    deg in 20.0..160.0 -> { clickFeedback(); onPlayPause() }
                                    deg > 160.0 && deg < 200.0 -> { clickFeedback(); onPrevious() }
                                    else -> { clickFeedback(); onNext() }
                                }
                            }
                        }
                        lastAngle = Float.NaN
                    }
                )
            }
            .background(theme.wheel, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 2.dp.toPx()
            drawCircle(
                color = theme.wheelEdge,
                radius = size.minDimension / 2f - stroke,
                style = Stroke(width = stroke)
            )
            drawCircle(
                color = theme.wheelEdge.copy(alpha = 0.35f),
                radius = size.minDimension * 0.38f,
                style = Stroke(width = 1.5.dp.toPx())
            )
        }

        Text(
            text = "MENU",
            color = theme.wheelEdge.copy(alpha = 0.75f),
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.TopCenter)
        )
        Text(
            text = "▶❙❙",
            color = theme.wheelEdge.copy(alpha = 0.75f),
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
        Text(
            text = "⏮",
            color = theme.wheelEdge.copy(alpha = 0.75f),
            fontSize = 14.sp,
            modifier = Modifier.align(Alignment.CenterStart)
        )
        Text(
            text = "⏭",
            color = theme.wheelEdge.copy(alpha = 0.75f),
            fontSize = 14.sp,
            modifier = Modifier.align(Alignment.CenterEnd)
        )

        Box(
            Modifier
                .size(diameter * 0.38f)
                .background(theme.wheelEdge.copy(alpha = 0.22f), CircleShape)
        )
    }
}

fun formatTime(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0) / 1000.0).roundToInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
