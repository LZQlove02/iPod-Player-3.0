package com.ipodplayer3.app.ui.components

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.ipodplayer3.app.data.model.Album
import com.ipodplayer3.app.ui.theme.IpodTheme
import com.ipodplayer3.app.ui.theme.LocalIpodTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * Cover Flow — drag/fling + non-linear perspective + tap-to-center.
 *
 * Perf: gesture keys do not restart per index; covers keep fixed layout size
 * (scale in graphicsLayer); draw window follows animated position; root is
 * clipped so covers cannot paint outside the LCD.
 */
@Composable
fun CoverFlow(
    albums: List<Album>,
    selectedIndex: Int,
    flipped: Boolean,
    onIndexChange: (Int) -> Unit,
    onFlip: () -> Unit,
    onOpenAlbum: (Album) -> Unit,
    tracks: List<String> = emptyList(),
    theme: IpodTheme = LocalIpodTheme.current,
) {
    if (albums.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("—", color = CoverFlowFg.copy(alpha = 0.5f))
        }
        return
    }

    val bg = theme.coverFlowBg
    val lastIndex = albums.lastIndex
    val safeIndex = selectedIndex.coerceIn(0, lastIndex)

    // key 里带上首张专辑的 id：同长度换库（重扫/换目录）时不能让动画停在旧位置。
    val pos = remember(albums.size, albums.firstOrNull()?.id) {
        Animatable(safeIndex.toFloat())
    }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var isDragging by remember { mutableStateOf(false) }
    val itemPx = with(density) { COVER_ITEM_DP.toPx() }

    val onIndexChangeState = rememberUpdatedState(onIndexChange)
    val onFlipState = rememberUpdatedState(onFlip)
    val onOpenAlbumState = rememberUpdatedState(onOpenAlbum)
    val flippedState = rememberUpdatedState(flipped)
    val safeIndexState = rememberUpdatedState(safeIndex)
    val tracksState = rememberUpdatedState(tracks)
    // pointerInput(albums.size) 的闭包是「创建那次组合」的快照：重扫曲库后列表
    // 身份变了而长度恰好没变时，直接读 albums 会拿到旧对象（点开错误的专辑）。
    val albumsState = rememberUpdatedState(albums)
    // 稳定 lambda：否则每次组合都是新实例，CoverSlot 永远无法跳过重组。
    val posValue = remember(pos) { { pos.value } }

    LaunchedEffect(safeIndex) {
        // 拖拽结束后再校正，而不是在 safeIndex 变化的那一刻读一次 isDragging 快照：
        // 若选中项恰在拖拽中被改（滚轮按键），effect 会被跳过且不再重试，
        // 画面与 vm.coverIndex 就会长期不同步。
        snapshotFlow { isDragging }.first { !it }
        if (abs(pos.value - safeIndex) > 0.02f) {
            pos.animateTo(
                targetValue = safeIndex.toFloat(),
                animationSpec = spring(
                    dampingRatio = 0.92f,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }

    val flipAnim by animateFloatAsState(
        targetValue = if (flipped) 1f else 0f,
        animationSpec = tween(durationMillis = 280),
        label = "flip"
    )

    val velocityTracker = remember { VelocityTracker() }

    // Follow animated center so fast flings still compose incoming covers.
    // Keyed on pos/lastIndex: pos is itself rebuilt when albums.size changes,
    // and an unkeyed remember would keep a stale lastIndex forever (frozen UI,
    // or albums[c] out of bounds once the list shrinks).
    val animCenter = remember(pos, lastIndex) {
        derivedStateOf { pos.value.roundToInt().coerceIn(0, lastIndex) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds() // 封面不得盖到 LCD / 其它 UI 之上
            .background(bg)
            // Do NOT key on safeIndex/flipped — that rebuilt gestures every slide
            .pointerInput(albums.size) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pointerId = down.id
                    velocityTracker.resetTracking()
                    velocityTracker.addPosition(down.uptimeMillis, down.position)
                    var dragged = false
                    var last = down.position
                    val start = down.position

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            // 锁定按下的那根手指，否则第二指按下时 dx 会瞬间跳变。
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            if (!change.pressed) break

                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            val dx = change.position.x - last.x
                            last = change.position
                            val total = change.position - start
                            val move = kotlin.math.hypot(total.x.toDouble(), total.y.toDouble())
                            if (move > viewConfiguration.touchSlop) dragged = true

                            if (dragged) {
                                isDragging = true
                                val next = (pos.value - dx / itemPx)
                                    .coerceIn(-0.35f, lastIndex + 0.35f)
                                scope.launch { pos.snapTo(next) }
                                change.consume()
                            }
                        }
                    } finally {
                        // 手势协程被取消（切页、父级抢占指针）时也要复位，否则
                        // isDragging 永远为 true，LaunchedEffect 里的外部换页动画
                        // 会被 !isDragging 挡住，封面看起来卡死。
                        isDragging = false
                    }

                    val safe = safeIndexState.value
                    val flip = flippedState.value
                    if (!dragged) {
                        val tapRel = (start.x - size.width / 2f) / itemPx
                        val targetIdx = (pos.value + tapRel).roundToInt().coerceIn(0, lastIndex)
                        if (targetIdx == safe) {
                            if (flip) {
                                albumsState.value.getOrNull(safe)?.let {
                                    onOpenAlbumState.value.invoke(it)
                                }
                            } else {
                                onFlipState.value.invoke()
                            }
                        } else {
                            onIndexChangeState.value.invoke(targetIdx)
                        }
                    } else {
                        val v = velocityTracker.calculateVelocity().x
                        val fling = -v / itemPx
                        // docs/coverflow.html: projected = pos + vel * flingGain * 12 (≈3.36)
                        val projected = (pos.value + fling * 0.28f * 12f).roundToInt()
                            .coerceIn(0, lastIndex)
                        // 先上报索引再让位移动画追赶：否则动画播完（几百毫秒）之前
                        // vm.coverIndex 还是旧值，此时按中心键会打开上一张专辑。
                        onIndexChangeState.value.invoke(projected)
                        scope.launch {
                            if (abs(pos.value - projected) > 0.02f) {
                                pos.animateTo(
                                    targetValue = projected.toFloat(),
                                    animationSpec = spring(
                                        dampingRatio = 0.82f,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                            }
                        }
                    }
                }
            }
    ) {
        val visible = VISIBLE_RADIUS
        val c = animCenter.value
        // 不取整的视觉中心：zIndex 与「谁是中间那张」都该按动画位置算。
        val centerFloat = pos.value
        val from = (c - visible).coerceAtLeast(0)
        val to = (c + visible).coerceAtMost(lastIndex)

        for (idx in from..to) {
            val album = albums[idx]
            // key 只跟专辑身份绑定：带上 idx 会让窗口每次平移一格就丢弃整组
            // remember（ImageRequest 重建、crossfade 重放 → 每格全屏闪一下）。
            // (id, title) 是 groupBy 的分组键，组内必然唯一。
            key(album.id, album.title) {
                CoverSlot(
                    album = album,
                    index = idx,
                    posValue = posValue,
                    centerFloat = centerFloat,
                    flip = flipAnim,
                    visibleRadius = visible,
                    theme = theme,
                    tracks = tracksState.value
                )
            }
        }

        Text(
            text = albums[c].title,
            color = CoverFlowFg.copy(alpha = 0.88f),
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .zIndex(50f)
        )
    }
}

@Composable
private fun CoverSlot(
    album: Album,
    index: Int,
    posValue: () -> Float,
    centerFloat: Float,
    flip: Float,
    visibleRadius: Int,
    theme: IpodTheme,
    tracks: List<String>,
) {
    Box(
        Modifier
            // 绘制层级跟随动画中心，而不是目标 safeIndex：惯性途中才不会被邻居盖住。
            .zIndex(visibleRadius * 2f - abs(index - centerFloat))
            .fillMaxSize()
            .graphicsLayer {
                val p = posValue()
                val rel = index - p
                val absRel = abs(rel)
                if (absRel > visibleRadius + 0.8f) {
                    alpha = 0f
                    return@graphicsLayer
                }

                // 与 docs/coverflow.html layout() 一致
                val t = (minOf(absRel, 3f) / 3f).coerceIn(0f, 1f)
                val rotY = sign(rel) * MAX_ROT_Y * sin(t * Math.PI.toFloat() * 0.5f)
                val scale = 1.12f * max(0f, 1f - absRel * 0.16f).pow(0.85f)
                val x = rel * COVER_ITEM_DP.value * (1f + absRel * 0.06f)
                val y = absRel * 5f
                val alphaV = max(0.12f, 1f - max(0f, absRel - 2.6f) * 0.45f)

                val d = this.density
                cameraDistance = 22f * d
                scaleX = scale
                scaleY = scale
                rotationY = rotY + flip * 180f
                translationX = x * d
                translationY = y * d
                alpha = alphaV
            },
        contentAlignment = Alignment.Center
    ) {
        // 是否居中看的是动画位置，不是选中项。
        val isCenter = abs(index - centerFloat) < 0.5f
        if (!isCenter || flip < 0.5f) {
            AlbumCoverCard(
                album = album,
                theme = theme,
                size = 148,
                showReflection = true
            )
        } else {
            Box(Modifier.graphicsLayer { rotationY = 180f }) {
                TrackBackside(album, theme, tracks)
            }
        }
    }
}

@Composable
private fun TrackBackside(album: Album, theme: IpodTheme, tracks: List<String>) {
    Column(
        Modifier
            .size(154.dp, 192.dp)
            .background(theme.coverBacksideBg, RoundedCornerShape(6.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(album.title, color = CoverFlowFg, fontSize = 11.sp, maxLines = 2)
        Spacer(Modifier.height(4.dp))
        Text(album.artist, color = CoverFlowFg.copy(0.55f), fontSize = 9.sp, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        tracks.take(7).forEach {
            Text("• $it", color = CoverFlowFg.copy(0.82f), fontSize = 8.sp, maxLines = 1)
        }
    }
}

@Composable
fun AlbumCoverCard(
    album: Album,
    size: Int,
    showReflection: Boolean = false,
    theme: IpodTheme = LocalIpodTheme.current,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // 主图与倒影共用一个 painter：两张不同尺寸的 AsyncImage 会让 Coil
        // 按不同 targetSize 各解码一份（可见 11 张封面 → 峰值多十几 MB bitmap）。
        val painter = rememberAsyncImagePainter(
            ImageRequest.Builder(LocalContext.current)
                .data(album.albumArtUri)
                .crossfade(180)
                .build()
        )
        val letter = remember(album.title) { albumLetter(album.title) }
        val placeholder = remember(album.title) { albumPlaceholderColors(album.title) }
        Box(
            Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Brush.linearGradient(listOf(placeholder.first, placeholder.second))),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = letter,
                color = CoverFlowFg.copy(alpha = 0.9f),
                fontSize = with(LocalDensity.current) { (size * 0.38f).dp.toSp() }
            )
            if (album.albumArtUri != null) {
                Image(
                    painter = painter,
                    contentDescription = album.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        if (showReflection) {
            Spacer(Modifier.height(1.dp))
            Box(
                Modifier
                    .size(size.dp, (size * 0.42f).dp)
                    .graphicsLayer {
                        scaleY = -1f
                        alpha = 0.28f
                    }
                    .clip(RoundedCornerShape(4.dp))
                    .drawWithContent {
                        drawContent()
                        // 纵向羽化：靠近封面一端浅，远端渐没
                        drawRect(
                            brush = Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0f to Color.Black.copy(alpha = 0.05f),
                                    0.35f to Color.Black.copy(alpha = 0.22f),
                                    0.72f to Color.Black.copy(alpha = 0.55f),
                                    1f to Color.Black.copy(alpha = 0.88f)
                                )
                            )
                        )
                        // 横向羽化：左右边缘软化，去掉硬直边
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colorStops = arrayOf(
                                    0f to Color.Black.copy(alpha = 0.55f),
                                    0.14f to Color.Black.copy(alpha = 0.12f),
                                    0.22f to Color.Transparent,
                                    0.78f to Color.Transparent,
                                    0.86f to Color.Black.copy(alpha = 0.12f),
                                    1f to Color.Black.copy(alpha = 0.55f)
                                )
                            )
                        )
                    }
            ) {
                if (album.albumArtUri != null) {
                    Image(
                        painter = painter,
                        // 倒影是装饰：不要再让读屏把同一张专辑念第二遍。
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
fun AlbumArtImage(
    uri: Uri?,
    title: String,
    size: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val placeholder = remember(title) { albumPlaceholderColors(title) }
    val letter = remember(title) { albumLetter(title) }
    val model = remember(uri) {
        if (uri == null) null
        else ImageRequest.Builder(context)
            .data(uri)
            .crossfade(180)
            .build()
    }

    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Brush.linearGradient(listOf(placeholder.first, placeholder.second))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = letter,
            color = CoverFlowFg.copy(alpha = 0.9f),
            // dp 当 sp 用的话，系统字号放大到 2.0 时字号会超过封面框本身。
            fontSize = with(LocalDensity.current) { (size * 0.38f).dp.toSp() }
        )
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** 占位字母：取第一个**码点**，别把 emoji 专辑名截成半个代理对。 */
private fun albumLetter(title: String): String {
    val trimmed = title.trim()
    if (trimmed.isEmpty()) return "♪"
    val end = if (Character.isHighSurrogate(trimmed[0]) && trimmed.length > 1) 2 else 1
    return trimmed.substring(0, end).uppercase()
}

internal fun albumPlaceholderColors(title: String): Pair<Color, Color> {
    val h = title.hashCode().toUInt() % 5u
    return when (h.toInt()) {
        0 -> Color(0xFF4A6FA5) to Color(0xFF2C3E66)
        1 -> Color(0xFF8B5E3C) to Color(0xFF5C3A22)
        2 -> Color(0xFF3D7A6A) to Color(0xFF234A42)
        3 -> Color(0xFF7A4E7A) to Color(0xFF4A2B4A)
        else -> Color(0xFF5A5A66) to Color(0xFF2E2E36)
    }
}

/** 封面之间的水平间距（dp）。绘制与命中测试都从这里派生，不要各写一份 132。 */
private val COVER_ITEM_DP = 132.dp

/** 中心左右各绘制多少张封面。 */
private const val VISIBLE_RADIUS = 5

/** 最外侧封面的 Y 轴旋转角（度）。 */
private const val MAX_ROT_Y = 52f

/** Cover Flow 恒为黑底，文字也用固定的白（不跟机身主题走）。 */
private val CoverFlowFg = Color.White
