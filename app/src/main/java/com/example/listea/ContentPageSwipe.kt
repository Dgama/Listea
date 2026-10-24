package com.example.listea

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Horizontal paging for a structured Content page on the **read-only** viewing surfaces.
 *
 * A Prompt's page is a vertically scrolling card, not a picture, so it never went through
 * [AssetRun]'s swipe. On device that left the page feeling locked: the outer arrows worked, but the
 * gesture every other page answers to did nothing. Nothing here decides anything — Preview has no
 * judgment to make — so a swipe is a page turn, exactly as it is on a file.
 *
 * **Horizontal only.** The detector waits for horizontal slop and leaves vertical drags to the
 * card's own scroll, so a long Prompt can still be read. Review does not use this wrapper: there a
 * forward swipe is a commit, and a choice is taken by tapping its option.
 */
@Composable
internal fun ContentPageSwipe(
    canSwipeForward: Boolean,
    canSwipeBack: Boolean,
    onSwipeForward: () -> Unit,
    onSwipeBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val threshold = with(LocalDensity.current) { SWIPE_THRESHOLD.roundToPx() }
    var travelled by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = modifier.pointerInput(canSwipeForward, canSwipeBack) {
            detectHorizontalDragGestures(
                onDragStart = { travelled = 0f },
                onDragCancel = { travelled = 0f },
                onDragEnd = {
                    val dx = travelled
                    travelled = 0f
                    when {
                        dx <= -threshold && canSwipeForward -> onSwipeForward()
                        dx >= threshold && canSwipeBack -> onSwipeBack()
                    }
                },
                onHorizontalDrag = { _, dragAmount -> travelled += dragAmount }
            )
        }
    ) {
        content()
    }
}

/**
 * How far a drag travels before it is a page turn rather than a stray touch.
 *
 * Larger than the tap slop and smaller than the swipe threshold the media card uses, because this
 * page is read rather than flipped at speed: a deliberate flick should turn it, a scroll should not.
 */
private val SWIPE_THRESHOLD = 72.dp
