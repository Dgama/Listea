package com.example.listea

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The navigator's width budget on the phone's own bar, with the counter now a control.
 *
 * The queue position is a control, and the bar it sits in is already the tightest row in the app:
 * five controls, the counter, and the item's name sharing what is left. The counter is the text
 * alone — the position is the affordance, with no chevron spending width to say so — but its box
 * is tappable at the arrows' height, and text measurement is still the part a Dp sum cannot
 * predict. This is a width fact, not a gesture, so it is pinned here rather than in a device walk —
 * and it is measured on a real composition.
 *
 * The shape under test is the worst one that fits: a two-level counter (`6(1/6)/7`) and both arrow
 * pairs. At 360dp the file's name is below the bar's [NavigatorSubtitleMinWidth] and must not be
 * drawn — it is what used to push the last arrow off-screen — so the item's name takes the
 * leftover width. A `Row` measures its fixed children in order and hands the last ones whatever is
 * left, so the assertion is that the final arrow still has a width and still ends inside the bar.
 * A control measured to zero looks like a missing control, and the right-hand arrows are the way
 * forward through the Task.
 *
 * Large-font widths are deliberately not asserted: on this bar the fixed 120dp title cap and the
 * four arrows already exceed 360dp at double the font scale. That budget needs its own answer, not
 * a narrower test.
 */
@RunWith(AndroidJUnit4::class)
class MediaNavigatorBarWidthTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theRightmostArrowFitsThePhoneBar() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                Box(Modifier.width(360.dp)) {
                    MediaShell(
                        navigator = MediaNavigator(
                            title = "Whatsapp",
                            onBack = {},
                            subtitle = "IMG_20261022_121212.jpg",
                            counter = "6(1/6)/7",
                            queueJump = MediaQueueJump(
                                entries = listOf(MediaQueueEntry("a"), MediaQueueEntry("b")),
                                current = 0,
                                onJump = { _, _ -> }
                            ),
                            onPrevious = {},
                            onNext = {},
                            onPreviousAsset = {},
                            onNextAsset = {},
                            hasAssetSteps = true
                        ),
                        taskBar = {},
                        media = {}
                    )
                }
            }
        }

        rule.onNodeWithText("6(1/6)/7").assertExists()
        // The file's name is the bar's first casualty at this width; drawing it is what crowded
        // the queue's last arrow off-screen. The Info sheet and the queue guide still name it.
        rule.onNodeWithText("IMG_20261022_121212.jpg").assertDoesNotExist()

        val lastArrow = rule.onNodeWithContentDescription("Next task")
        lastArrow.assertExists()
        val bounds = lastArrow.getUnclippedBoundsInRoot()

        val arrowWidth = bounds.right - bounds.left
        assertTrue("The next-task arrow was squeezed to $arrowWidth", arrowWidth > 0.dp)
        assertTrue("The next-task arrow ends at ${bounds.right}, past the bar", bounds.right <= 360.5.dp)
    }
}
