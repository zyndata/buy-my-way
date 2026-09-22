package dev.gorny.buymyway.ui.list

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.gorny.buymyway.R
import dev.gorny.buymyway.data.photo.PhotoRef

/** Loads [ref] at [maxPx]; null while loading or when it cannot be had. */
@Composable
private fun rememberPhoto(ref: PhotoRef, maxPx: Int, load: suspend (PhotoRef, Int) -> ImageBitmap?): LoadState {
    val state by produceState<LoadState>(LoadState.Loading, ref, maxPx) {
        value = load(ref, maxPx)?.let { LoadState.Loaded(it) } ?: LoadState.Missing
    }
    return state
}

private sealed interface LoadState {
    data object Loading : LoadState

    data object Missing : LoadState

    data class Loaded(val bitmap: ImageBitmap) : LoadState
}

/**
 * An item's photo as a small square (PLAN.md Phase 6, task 3): in the row, and in the edit
 * sheet. A tap opens it full screen. Only a thumbnail on screen is ever loaded.
 */
@Composable
fun PhotoThumbnail(
    ref: PhotoRef,
    name: String,
    size: Dp,
    load: suspend (PhotoRef, Int) -> ImageBitmap?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val state = rememberPhoto(ref, px, load)
    val description = stringResource(R.string.photo_description, name)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClickLabel = stringResource(R.string.action_show_photo), role = Role.Image, onClick = onOpen)
            .testTag("photo:$name"),
    ) {
        when (state) {
            is LoadState.Loaded -> Image(
                bitmap = state.bitmap,
                contentDescription = description,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Still loading, or not reachable now (offline): a photo is there all the same.
            else -> Icon(
                painterResource(R.drawable.ic_image),
                contentDescription = description,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size / 2),
            )
        }
    }
}

/** The photo full screen, with pinch-zoom and a double tap to zoom in or back. */
@Composable
fun PhotoViewer(
    ref: PhotoRef,
    name: String,
    load: suspend (PhotoRef, Int) -> ImageBitmap?,
    onDismiss: () -> Unit,
) {
    val state = rememberPhoto(ref, FULL_PX, load)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag("photoViewer"),
        ) {
            when (state) {
                is LoadState.Loaded -> Image(
                    bitmap = state.bitmap,
                    contentDescription = stringResource(R.string.photo_description, name),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                                offset = if (scale == 1f) Offset.Zero else offset + pan
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = DOUBLE_TAP_ZOOM
                                }
                            })
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
                LoadState.Loading -> CircularProgressIndicator(color = Color.White)
                LoadState.Missing -> Text(
                    stringResource(R.string.photo_unavailable),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(32.dp),
                )
            }
            IconButton(
                onClick = onDismiss,
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .safeDrawingPadding()
                    .padding(8.dp),
            ) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_close))
            }
        }
    }
}

/** A stored photo is at most 800 px: the viewer loads it whole. */
private const val FULL_PX = 800
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
