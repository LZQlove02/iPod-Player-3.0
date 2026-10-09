package com.ipodplayer3.app.ui.device

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ipodplayer3.app.ui.theme.IpodTheme
import com.ipodplayer3.app.ui.theme.LocalIpodTheme
import java.util.Locale
import kotlin.math.atan2

/**
 * 中心键半径占整轮半径的比例。命中判定（handleTapZones）与绘制（Canvas 内圆、
 * 中心圆盘）必须共用同一个值，否则点得到的区域和看到的圆会错位。
 */
private const val CENTER_RATIO = 0.38f

/**
 * Classic Click Wheel:
 * - drag around ring → scroll (discrete) or scrub (continuous)
 * - tap zones: MENU (top) / play (bottom) / prev (left) / next (right)
 * - tap center → select
 *
 * @param continuous when true, ring drag reports raw degree deltas via [onScrub]
 *   (no notch quantization, no click per step) — used for stepless seek.
 * @param strings 传入后会给滚轮挂上无障碍语义与自定义操作（读屏可用）；
 *   文案来自资源，所以只能由界面传入。
 */
@Composable
fun ClickWheel(
    theme: IpodTheme = LocalIpodTheme.current,
    diameter: Dp = 200.dp,
    soundEnabled: Boolean = true,
    continuous: Boolean = false,
    strings: com.ipodplayer3.app.ui.settings.Strings.Bundle? = null,
    onScroll: (Int) -> Unit,
    onScrub: (Float) -> Unit = {},
    onScrubEnd: () -> Unit = {},
    onMenu: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSelect: () -> Unit,
) {
    val view = LocalView.current

    // pointerInput(Unit) keeps the first gesture coroutine — pin live values.
    val continuousState = rememberUpdatedState(continuous)
    val onScrollState = rememberUpdatedState(onScroll)
    val onScrubState = rememberUpdatedState(onScrub)
    val onScrubEndState = rememberUpdatedState(onScrubEnd)
    val onMenuState = rememberUpdatedState(onMenu)
    val onPlayPauseState = rememberUpdatedState(onPlayPause)
    val onNextState = rememberUpdatedState(onNext)
    val onPreviousState = rememberUpdatedState(onPrevious)
    val onSelectState = rememberUpdatedState(onSelect)
    val soundEnabledState = rememberUpdatedState(soundEnabled)
    val stringsState = rememberUpdatedState(strings)

    // Give Haptic a View so settings confirm() can use performHapticFeedback.
    SideEffect {
        Haptic.attach(view)
    }

    fun clickFeedback() {
        // ClickSound already carries its own synth fallback, and it is mixed on
        // the media stream (playSoundEffect would land on the system stream and
        // double the click).
        if (soundEnabledState.value) {
            ClickSound.play()
        }
        // Gate is Haptic.enabled (settings switch); tick() is multi-path.
        Haptic.tick(view)
    }

    fun handleTapZones(offset: Offset, sizeW: Float, sizeH: Float) {
        val c = Offset(sizeW / 2f, sizeH / 2f)
        val x = offset.x - c.x
        val y = offset.y - c.y
        val dist = kotlin.math.hypot(x.toDouble(), y.toDouble())
        val radius = minOf(sizeW, sizeH) / 2.0
        // Box 是方的、轮是圆的：圆外那四个角不属于任何按键。
        if (dist > radius) return
        if (dist < radius * CENTER_RATIO) {
            clickFeedback()
            onSelectState.value()
            return
        }
        // screen: y increases downward. 270° (or -90°) = top = MENU
        val deg = (Math.toDegrees(atan2(y.toDouble(), x.toDouble())) + 360.0) % 360.0
        clickFeedback()
        // 四个扇形各占 90°，端点不重叠：135° 归上/下哪侧固定给 play，
        // 225° 给 menu，315° 给 next，避免 `when` 顺序偷偷决定归属。
        when {
            deg >= 225.0 && deg < 315.0 -> onMenuState.value()       // top
            deg >= 45.0 && deg < 135.0 -> onPlayPauseState.value()   // bottom
            deg >= 135.0 && deg < 225.0 -> onPreviousState.value()   // left
            else -> onNextState.value()                              // right
        }
    }

    Box(
        modifier = Modifier
            .size(diameter)
            // 语义要放在手势节点之前：合并后的语义会被 pointerInput 覆盖。
            .semantics {
                val s = stringsState.value
                if (s != null) {
                    contentDescription = s.clickWheel
                    customActions = listOf(
                        CustomAccessibilityAction(s.a11yMenu) { onMenuState.value(); true },
                        CustomAccessibilityAction(s.a11yPlayPause) { onPlayPauseState.value(); true },
                        CustomAccessibilityAction(s.a11yPrevious) { onPreviousState.value(); true },
                        CustomAccessibilityAction(s.a11yNext) { onNextState.value(); true },
                        CustomAccessibilityAction(s.a11ySelect) { onSelectState.value(); true },
                    )
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pointerId = down.id
                    var maxMove = 0f
                    var absRotate = 0f
                    var emitted = false
                    // 手势局部量：每次手势开始都是新的，不需要跨手势保留。
                    var lastAngle = Float.NaN
                    var accum = 0f
                    val c = Offset(size.width / 2f, size.height / 2f)

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            // 锁住按下的那根手指：多指时 firstOrNull 会拿到别的手指，
                            // 角度/位移会瞬间跳变。
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            if (!change.pressed) {
                                break
                            }
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            maxMove = maxOf(maxMove, kotlin.math.hypot(dx, dy))

                            if (maxMove > viewConfiguration.touchSlop) {
                                val angle = atan2(change.position.y - c.y, change.position.x - c.x)
                                if (lastAngle.isNaN()) {
                                    lastAngle = angle
                                } else {
                                    var delta = Math.toDegrees((angle - lastAngle).toDouble()).toFloat()
                                    if (delta > 180f) delta -= 360f
                                    if (delta < -180f) delta += 360f
                                    lastAngle = angle
                                    absRotate += kotlin.math.abs(delta)
                                    if (continuousState.value) {
                                        // Stepless: report every degree, no notch / click.
                                        if (delta != 0f) {
                                            onScrubState.value(delta)
                                            emitted = true
                                        }
                                    } else {
                                        accum += delta
                                        val steps = (accum / 12f).toInt()
                                        if (steps != 0) {
                                            accum -= steps * 12f
                                            onScrollState.value(steps)
                                            emitted = true
                                            clickFeedback()
                                        }
                                    }
                                }
                                change.consume()
                            }
                        }
                    } finally {
                        // 手势被取消（下拉通知栏、指针焦点丢失、页面切换）时也必须收尾，
                        // 否则 PlayerController 会永远停在 scrubbing=true：进度显示冻结，
                        // 下一次拖动还会接着旧的基准位置累加。
                        if (emitted && continuousState.value) onScrubEndState.value()
                    }

                    // A slight finger drift on a tap must NOT eat the press:
                    // only treat as drag when the ring actually turned (or we
                    // already emitted scroll/scrub). Otherwise fire tap zones.
                    if (!(emitted || absRotate >= 6f)) {
                        handleTapZones(down.position, size.width.toFloat(), size.height.toFloat())
                    }
                }
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
                radius = size.minDimension * CENTER_RATIO,
                style = Stroke(width = 1.5.dp.toPx())
            )
        }

        val glyphColor = theme.wheelEdge.copy(alpha = 0.92f)
        // 这些只是装饰性字形：不要进无障碍树（滚轮整体已有语义与自定义操作）。
        Text(
            text = "MENU",
            color = glyphColor,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .clearAndSetSemantics {}
        )
        // 用「▶ + 画两条竖线」代替 "▶❙❙"(U+2759)：后者在不少 CJK 字体里缺字形，
        // 会静默渲染成空白（「正在播放」页早就因此改成自绘了）。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .clearAndSetSemantics {}
        ) {
            Text(text = "▶", color = glyphColor, fontSize = 10.sp)
            Spacer(Modifier.width(3.dp))
            Canvas(Modifier.size(width = 6.dp, height = 9.dp)) {
                val barW = size.width * 0.34f
                drawRect(glyphColor, size = Size(barW, size.height))
                drawRect(
                    glyphColor,
                    topLeft = Offset(size.width - barW, 0f),
                    size = Size(barW, size.height)
                )
            }
        }
        Text(
            text = "⏮",
            color = glyphColor,
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .clearAndSetSemantics {}
        )
        Text(
            text = "⏭",
            color = glyphColor,
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .clearAndSetSemantics {}
        )

        Box(
            Modifier
                .size(diameter * CENTER_RATIO)
                .background(theme.wheelEdge.copy(alpha = 0.22f), CircleShape)
        )
    }
}

fun formatTime(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0) / 1000).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    // 超过一小时的长音频（有声书/长混音）用 h:mm:ss，否则会输出 "123:45"
    return if (m >= 60) {
        String.format(Locale.US, "%d:%02d:%02d", m / 60, m % 60, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}
