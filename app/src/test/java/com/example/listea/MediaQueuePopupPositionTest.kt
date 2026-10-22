package com.example.listea

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the queue list lands under the position control.
 *
 * Checked without a screen because the rule is pure arithmetic, and the two edge cases it exists
 * for — a control at the right edge and a list taller than the room below it — are exactly the ones
 * a happy-path screenshot never shows. A popup that hangs off the window loses the very item names
 * the list was opened to read.
 */
class MediaQueuePopupPositionTest {

    private val window = IntSize(width = 1080, height = 2400)
    private val gap = 24

    @Test
    fun `the list's trailing edge sits on the anchor's trailing edge`() {
        val anchor = IntRect(left = 900, top = 40, right = 1040, bottom = 160)

        assertEquals(
            IntOffset(x = 760, y = 184),
            position(anchor, popup = IntSize(width = 280, height = 600), LayoutDirection.Ltr)
        )
    }

    @Test
    fun `in a right-to-left layout the trailing edge is the left one`() {
        val anchor = IntRect(left = 40, top = 40, right = 180, bottom = 160)

        assertEquals(
            IntOffset(x = 40, y = 184),
            position(anchor, popup = IntSize(width = 280, height = 600), LayoutDirection.Rtl)
        )
    }

    @Test
    fun `an anchor too close to the left edge pushes the list inward`() {
        // A wide list under a narrow anchor: aligned to the anchor's right, it would start at -100.
        val anchor = IntRect(left = 10, top = 40, right = 180, bottom = 160)

        assertEquals(
            IntOffset(x = 0, y = 184),
            position(anchor, popup = IntSize(width = 280, height = 600), LayoutDirection.Ltr)
        )
    }

    @Test
    fun `a list wider than the window starts at its left edge`() {
        val anchor = IntRect(left = 20, top = 40, right = 100, bottom = 160)

        assertEquals(
            IntOffset(x = 0, y = 184),
            position(anchor, popup = IntSize(width = 1200, height = 600), LayoutDirection.Ltr)
        )
    }

    @Test
    fun `a list taller than the room below the bar is pulled up`() {
        val anchor = IntRect(left = 900, top = 2000, right = 1040, bottom = 2120)

        assertEquals(
            IntOffset(x = 760, y = 1800),
            position(anchor, popup = IntSize(width = 280, height = 600), LayoutDirection.Ltr)
        )
    }

    @Test
    fun `the gap is measured from the anchor's bottom`() {
        val anchor = IntRect(left = 500, top = 10, right = 600, bottom = 70)

        assertEquals(
            IntOffset(x = 320, y = 94),
            position(anchor, popup = IntSize(width = 280, height = 300), LayoutDirection.Ltr)
        )
    }

    private fun position(
        anchor: IntRect,
        popup: IntSize,
        layoutDirection: LayoutDirection
    ): IntOffset = MediaQueuePopupPositionProvider(gap).calculatePosition(
        anchorBounds = anchor,
        windowSize = window,
        layoutDirection = layoutDirection,
        popupContentSize = popup
    )
}
