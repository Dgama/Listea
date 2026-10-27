package com.example.listea

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Which way one finished horizontal drag turns the page.
 *
 * Pure so the boundary rule can be tested without a gesture: a drag past the threshold in a
 * direction the surface cannot go is [SwipeTurn.NONE], not a turn that silently does nothing. The
 * boundary is a property of the *current* page, which is why the caller has to pass it in rather
 * than let the gesture handler remember what it was when the detector was installed.
 */
internal enum class SwipeTurn { NONE, FORWARD, BACK }

internal fun swipeTurn(
    dx: Float,
    threshold: Float,
    canSwipeForward: Boolean,
    canSwipeBack: Boolean
): SwipeTurn = when {
    dx <= -threshold && canSwipeForward -> SwipeTurn.FORWARD
    dx >= threshold && canSwipeBack -> SwipeTurn.BACK
    else -> SwipeTurn.NONE
}

/**
 * Horizontal paging for a structured Content page on the **read-only** viewing surfaces.
 *
 * A Prompt's page is a vertically scrolling card, not a picture, so it never went through
 * [AssetRun]'s swipe. On device that left the page feeling locked: the outer arrows worked, but the
 * gesture every other page answers to did nothing. Nothing here decides anything — Preview has no
 * judgment to make — so a swipe is a page turn, exactly as it is on a file.
 *
 * **Horizontal only.** The detector waits for horizontal slop and leaves vertical drags to the
 * card's own scroll, so a long Prompt can still be read.
 *
 * **Review uses it only on a page that cannot be judged.** There a forward swipe off a Task's last
 * page is a judgment, and an interactive Like/Dislike card takes its judgment by tapping an option;
 * a swipe on that card is deliberately silent. A Prompt this build cannot answer — no Task Review
 * in force catches a choice, the body is unreadable, or a required file is missing — has no
 * judgment to make, so its page becomes an ordinary page in the run: a swipe turns it, and at the
 * run's ends it crosses Tasks as navigation. The same wrapper then adds the tap that reveals and
 * hides the asset band, which a reading surface has always had and a scrollable card otherwise
 * swallowed.
 *
 * **The gesture reads live state, never a capture.** This used to key `pointerInput` on the two
 * booleans, which restarted the detector whenever they changed — and left it holding the callbacks
 * from whenever it last started. In a run of seven Tasks the booleans become `(true, true)` at the
 * second Task and never change again, so every later swipe replayed the callbacks of that moment:
 * the preview kept rewriting the same Task and looked stuck. v0.8.2f installs the detector once
 * (`pointerInput(Unit)`) and reads the current flags, callbacks and threshold through
 * [rememberUpdatedState], so each finished drag is judged against the page actually on screen.
 */
@Composable
internal fun ContentPageSwipe(
    canSwipeForward: Boolean,
    canSwipeBack: Boolean,
    onSwipeForward: () -> Unit,
    onSwipeBack: () -> Unit,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val threshold = rememberUpdatedState(
        with(LocalDensity.current) { SWIPE_THRESHOLD.roundToPx().toFloat() }
    )
    val forward = rememberUpdatedState(canSwipeForward)
    val back = rememberUpdatedState(canSwipeBack)
    val onForward = rememberUpdatedState(onSwipeForward)
    val onBack = rememberUpdatedState(onSwipeBack)
    val onTapState = rememberUpdatedState(onTap)
    Box(
        modifier = modifier
            .then(
                if (onTap != null) {
                    Modifier.pointerInput(Unit) {
                        // The card's own controls — an option pill, a text field — are children and
                        // see the press first. `requireUnconsumed = true` is the whole rule: a
                        // clickable child consumes its down, so this detector never sees it, and a
                        // tap on the page itself (nothing consumed) toggles the asset band. Compose's
                        // stock `detectTapGestures` consumes the down itself and so fired on an
                        // option pill as well, which turned a Like into a chrome toggle.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)
                            down.consume()
                            val up = waitForUpOrCancellation()
                            if (up != null) {
                                up.consume()
                                onTapState.value?.invoke()
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
            .pointerInput(Unit) {
                // Local to this gesture detector rather than Compose state: the distance is read
                // once, in `onDragEnd`, and never needs to trigger a recomposition while the finger
                // moves.
                var travelled = 0f
                detectHorizontalDragGestures(
                    onDragStart = { travelled = 0f },
                    onDragCancel = { travelled = 0f },
                    onDragEnd = {
                        val dx = travelled
                        travelled = 0f
                        when (swipeTurn(dx, threshold.value, forward.value, back.value)) {
                            SwipeTurn.FORWARD -> onForward.value()
                            SwipeTurn.BACK -> onBack.value()
                            SwipeTurn.NONE -> Unit
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
