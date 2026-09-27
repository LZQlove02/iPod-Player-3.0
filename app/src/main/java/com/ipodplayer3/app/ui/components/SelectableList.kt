package com.ipodplayer3.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ipodplayer3.app.ui.theme.IpodTheme

data class MenuRow(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val iconText: String? = null,
    val artworkUri: Any? = null,
)

@Composable
fun SelectableList(
    theme: IpodTheme,
    rows: List<MenuRow>,
    selectedId: String,
    modifier: Modifier = Modifier,
    showSelection: Boolean = true,
    rowHeight: Int = 36,
) {
    val state = rememberLazyListState()
    val selectedIndex = rows.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)

    LaunchedEffect(selectedIndex, rows.size) {
        if (rows.isEmpty()) return@LaunchedEffect
        state.animateScrollToItem(selectedIndex)
    }

    val bg = if (theme.isDarkScreen) Color(0xFF1C1C1E) else Color(0xFFF5F5F7)
    val text = if (theme.isDarkScreen) Color(0xFFF2F2F7) else Color(0xFF1D1D1F)
    val muted = if (theme.isDarkScreen) Color(0xFF98989D) else Color(0xFF6E6E73)
    val selBg = theme.accent

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .background(bg),
        state = state
    ) {
        items(rows, key = { it.id }) { row ->
            val selected = showSelection && row.id == selectedId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight.dp)
                    .background(if (selected) selBg else Color.Transparent)
                    .padding(horizontal = 10.dp),
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
                                if (selected) Color.White.copy(alpha = 0.25f) else muted.copy(alpha = 0.15f),
                                RoundedCornerShape(4.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            row.iconText,
                            color = if (selected) Color.White else muted,
                            fontSize = 10.sp
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        row.title,
                        color = if (selected) Color.White else text,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (row.subtitle != null) {
                        Text(
                            row.subtitle,
                            color = if (selected) Color.White.copy(alpha = 0.85f) else muted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (selected) {
                    Text("›", color = Color.White, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
fun SplitPreviewPanel(
    theme: IpodTheme,
    title: String,
    subtitle: String?,
    artworkUri: Any?,
) {
    val bg = if (theme.isDarkScreen) Color(0xFF111113) else Color(0xFFE8E8ED)
    val text = if (theme.isDarkScreen) Color(0xFFF2F2F7) else Color(0xFF1D1D1F)
    val muted = if (theme.isDarkScreen) Color(0xFF8E8E93) else Color(0xFF6E6E73)

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(96.dp)
            .background(bg)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AsyncImage(
            model = artworkUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(muted.copy(alpha = 0.15f))
        )
        Spacer(Modifier.height(8.dp))
        Text(title, color = text, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, color = muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
