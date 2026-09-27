package com.ipodplayer3.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.ipodplayer3.app.data.model.Album
import com.ipodplayer3.app.ui.theme.IpodTheme
import kotlin.math.abs

/**
 * Classic Cover Flow: center cover faces viewer,
 * sides tilt away and shrink; drag or scroll to move.
 */
@Composable
fun CoverFlow(
    theme: IpodTheme,
    albums: List<Album>,
    selectedIndex: Int,
    flipped: Boolean,
    onIndexChange: (Int) -> Unit,
    onFlip: () -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onScrollDelta: (Int) -> Unit,
    tracks: List<String> = emptyList(),
) {
    if (albums.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("—", color = if (theme.isDarkScreen) Color.White else Color.Black)
        }
        return
    }

    val bg = if (theme.isDarkScreen) Color(0xFF000000) else Color(0xFF0B0B0C)
    val safeIndex = selectedIndex.coerceIn(0, albums.lastIndex)
    val flipAnim by animateFloatAsState(targetValue = if (flipped) 1f else 0f, label = "flip")

    Box(
        Modifier
            .fillMaxSize()
            .background(bg)
            .pointerInput(albums.size) {
                detectHorizontalDragGestures(
                    onDragStart = { },
                    onHorizontalDrag = { _, dragAmount ->
                        if (abs(dragAmount) > 28f) {
                            val dir = if (dragAmount < 0) 1 else -1
                            onScrollDelta(dir)
                        }
                    }
                )
            }
            .clickable {
                if (flipped) onFlip() else onFlip()
            }
    ) {
        // side + center covers
        val center = safeIndex
        val visible = 3
        for (offset in -visible..visible) {
            val idx = center + offset
            if (idx !in albums.indices) continue
            val album = albums[idx]
            val rel = offset.toFloat()
            val absRel = abs(rel)
            val scale = (1f - absRel * 0.18f).coerceIn(0.55f, 1f)
            val rotateY = when {
                rel < 0 -> 55f
                rel > 0 -> -55f
                else -> if (flipAnim > 0.5f) 180f else 0f
            }
            val zIndexValue = 100f - absRel
            val xOffset = rel * 92f

            Box(
                Modifier
                    .zIndex(zIndexValue)
                    .fillMaxSize()
                    .graphicsLayer {
                        cameraDistance = 18f * density
                        scaleX = scale
                        scaleY = scale
                        rotationY = rotateY
                        translationX = xOffset * density
                    },
                contentAlignment = Alignment.Center
            ) {
                if (offset != 0 || flipAnim < 0.5f) {
                    AlbumCoverCard(album, theme, size = 140, showReflection = offset == 0)
                } else {
                    Column(
                        Modifier
                            .size(170.dp, 210.dp)
                            .background(
                                if (theme.isDarkScreen) Color(0xFF1C1C1E) else Color(0xFFEDEDF0),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            album.title,
                            color = if (theme.isDarkScreen) Color.White else Color.Black,
                            fontSize = 12.sp,
                            maxLines = 2
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            album.artist,
                            color = if (theme.isDarkScreen) Color.White.copy(0.7f) else Color.Black.copy(0.6f),
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(8.dp))
                        tracks.take(8).forEach {
                            Text(
                                "• $it",
                                color = if (theme.isDarkScreen) Color.White.copy(0.85f) else Color.Black.copy(0.8f),
                                fontSize = 9.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = albums[safeIndex].title,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
        )
    }
}

@Composable
fun AlbumCoverCard(
    album: Album,
    theme: IpodTheme,
    size: Int,
    showReflection: Boolean = false,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AsyncImage(
            model = album.albumArtUri,
            contentDescription = album.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (theme.isDarkScreen) Color(0xFF2C2C2E) else Color(0xFFD2D2D7))
        )
        if (showReflection) {
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier
                    .size(size.dp, size.dp / 3)
                    .graphicsLayer {
                        scaleY = -1f
                        alpha = 0.28f
                    }
                    .clip(RoundedCornerShape(4.dp))
            ) {
                AsyncImage(
                    model = album.albumArtUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
