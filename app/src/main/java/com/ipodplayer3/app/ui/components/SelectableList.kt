package com.ipodplayer3.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ipodplayer3.app.ui.theme.IpodTheme
import com.ipodplayer3.app.ui.theme.LocalIpodTheme

data class MenuRow(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val iconText: String? = null,
    val artworkUri: Any? = null,
)

@Composable
fun SelectableList(
    rows: List<MenuRow>,
    selectedId: String,
    modifier: Modifier = Modifier,
    theme: IpodTheme = LocalIpodTheme.current,
) {
    val state = rememberLazyListState()
    // 组合期对全表 indexOfFirst：曲库上千首时每滚一格都要重扫一遍，按入参缓存。
    val selectedIndex = remember(rows, selectedId) {
        rows.indexOfFirst { it.id == selectedId }
    }

    LaunchedEffect(selectedIndex, rows.size) {
        // -1 = 选中项不在当前列表里，别跳回顶部。
        if (selectedIndex < 0) return@LaunchedEffect
        // 已经看得见就不动：否则每滚一格都重启一次动画，上一次被取消在半路，
        // 连续转轮时列表会「越转越黏」。真机 iPod 也是顶到边缘才滚。
        if (state.layoutInfo.visibleItemsInfo.none { it.index == selectedIndex }) {
            state.animateScrollToItem(selectedIndex)
        }
    }

    val bg = theme.screenBg
    val text = theme.screenFg
    val muted = theme.screenMuted
    val selBg = theme.accent

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .background(bg),
        state = state
    ) {
        items(rows, key = { it.id }) { row ->
            val selected = row.id == selectedId
            // 选项过渡：选中色随切换淡入淡出（约 220ms，FastOutSlowIn）
            val rowBg by animateColorAsState(
                targetValue = if (selected) selBg else Color.Transparent,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                label = "rowSel"
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 最小高度而非固定高度：系统字体放大到 1.5~2.0 时
                    // 固定 44dp 会把两行文字裁掉。
                    .heightIn(min = 44.dp)
                    .background(rowBg)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (row.artworkUri != null) {
                    AsyncImage(
                        model = row.artworkUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(muted.copy(alpha = 0.2f))
                    )
                    Spacer(Modifier.width(8.dp))
                } else if (row.iconText != null) {
                    Box(
                        Modifier
                            .size(22.dp)
                            .background(
                                if (selected) theme.onAccent.copy(alpha = 0.25f)
                                else muted.copy(alpha = 0.15f),
                                RoundedCornerShape(4.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            row.iconText,
                            color = if (selected) theme.onAccent else muted,
                            fontSize = 10.sp
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        row.title,
                        color = if (selected) theme.onAccent else text,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (row.subtitle != null) {
                        Text(
                            row.subtitle,
                            color = if (selected) theme.onAccent.copy(alpha = 0.85f) else muted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // 箭头常驻、只切换透明度：选中时才插入会让长标题重排（滚动时宽度抖动），
                // 读屏也会念出「大于号」。clearAndSetSemantics 把它从语义里摘掉。
                Text(
                    "›",
                    color = theme.onAccent,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .alpha(if (selected) 1f else 0f)
                        .clearAndSetSemantics {}
                )
            }
        }
    }
}

@Composable
fun SplitPreviewPanel(
    title: String,
    subtitle: String?,
    artworkUri: Any?,
    modifier: Modifier = Modifier,
    theme: IpodTheme = LocalIpodTheme.current,
) {
    val bg = theme.screenPanel
    val text = theme.screenFg
    val muted = theme.screenMuted

    // 矮屏 / 横屏：封面不能写死 128dp，否则会被上下裁掉。
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        val artSize = minOf(128.dp, maxHeight * 0.45f, maxWidth * 0.8f)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.verticalScroll(rememberScrollState())
        ) {
            if (artworkUri != null) {
                AsyncImage(
                    model = artworkUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(artSize)
                        .clip(RoundedCornerShape(6.dp))
                        .background(muted.copy(alpha = 0.15f))
                )
            } else {
                Box(
                    Modifier
                        .size(artSize)
                        .clip(RoundedCornerShape(6.dp))
                        .background(muted.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("♪", color = muted.copy(alpha = 0.7f), fontSize = 40.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                title,
                color = text,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    subtitle,
                    color = muted,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

/** 1dp hairline between list and preview (Classic split-screen look). */
@Composable
fun SplitDivider(theme: IpodTheme = LocalIpodTheme.current) {
    Box(
        Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(theme.screenDivider)
    )
}
