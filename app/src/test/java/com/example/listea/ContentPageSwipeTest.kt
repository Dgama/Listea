package com.example.listea

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The boundary rule behind a read-only page swipe.
 *
 * The detector now reads the page's flags live rather than capturing them when it was installed —
 * that was the seven-Task preview that stuck, replaying one Task's callbacks after the second page.
 * These tests pin the decision the live flags feed: a drag past the threshold turns only in a
 * direction the current page actually has.
 */
class ContentPageSwipeTest {

    private val threshold = 72f

    @Test
    fun `a left drag past the threshold turns forward when there is a page ahead`() {
        assertEquals(SwipeTurn.FORWARD, swipeTurn(-threshold - 1f, threshold, true, true))
    }

    @Test
    fun `a right drag past the threshold turns back when there is a page behind`() {
        assertEquals(SwipeTurn.BACK, swipeTurn(threshold + 1f, threshold, true, true))
    }

    @Test
    fun `a drag toward an edge the page does not have decides nothing`() {
        assertEquals(SwipeTurn.NONE, swipeTurn(-threshold - 1f, threshold, false, true))
        assertEquals(SwipeTurn.NONE, swipeTurn(threshold + 1f, threshold, true, false))
    }

    @Test
    fun `a drag shorter than the threshold is not a page turn`() {
        assertEquals(SwipeTurn.NONE, swipeTurn(-threshold / 2f, threshold, true, true))
        assertEquals(SwipeTurn.NONE, swipeTurn(threshold / 2f, threshold, true, true))
    }

    @Test
    fun `exactly the threshold is a turn, because the gesture is deliberate either way`() {
        assertEquals(SwipeTurn.FORWARD, swipeTurn(-threshold, threshold, true, true))
        assertEquals(SwipeTurn.BACK, swipeTurn(threshold, threshold, true, true))
    }
}
