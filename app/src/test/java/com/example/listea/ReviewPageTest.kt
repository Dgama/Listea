package com.example.listea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The run a Task's pages form, and where a swipe goes inside it.
 *
 * The Task is the container and a page is either one of its Assets or the questionnaire that
 * follows them. These two functions are the whole of that arithmetic: the composables read the
 * count to draw the navigator and the step to decide whether a swipe is an interior move or a
 * leave. Keeping them pure is what lets the edges — an empty Task, a one-page Task, the add-on page
 * itself — be pinned without a renderer.
 *
 * **The forward slot at the end is the leave gate, not this function's business.** `pageStep`
 * answers null past the last page, and the card then offers the Task-level action if the caller
 * allows one; a blocked Task's forward stays inert on the add-on page for exactly the same reason it
 * does on its last file, which is that a blocked Task's leave gate is closed.
 */
class ReviewPageTest {

    @Test
    fun `a Task without an add-on has exactly its files`() {
        assertEquals(0, taskPageCount(assetCount = 0, hasAddOn = false))
        assertEquals(1, taskPageCount(assetCount = 1, hasAddOn = false))
        assertEquals(4, taskPageCount(assetCount = 4, hasAddOn = false))
    }

    @Test
    fun `an add-on adds one page after the last file`() {
        assertEquals(1, taskPageCount(assetCount = 0, hasAddOn = true))
        assertEquals(2, taskPageCount(assetCount = 1, hasAddOn = true))
        assertEquals(5, taskPageCount(assetCount = 4, hasAddOn = true))
    }

    @Test
    fun `every page before the last steps forward`() {
        assertEquals(1, pageStep(pageCount = 3, index = 0, forward = true))
        assertEquals(2, pageStep(pageCount = 3, index = 1, forward = true))
    }

    @Test
    fun `the add-on page is the end of the run`() {
        // Null here is what hands the swipe to `onLeaveForward`, which completes the Task. It is
        // also why a blocked Task cannot be swiped into a judgment from this page: the leave gate
        // is closed, so there is nowhere for the gesture to go.
        assertNull(pageStep(pageCount = 3, index = 2, forward = true))
        // And back is the last file, an interior move: leaving the questionnaire is not leaving
        // the Task.
        assertEquals(1, pageStep(pageCount = 3, index = 2, forward = false))
    }

    @Test
    fun `the first page is the start of the run`() {
        assertNull(pageStep(pageCount = 3, index = 0, forward = false))
        assertEquals(0, pageStep(pageCount = 2, index = 1, forward = false))
    }

    @Test
    fun `a one-page run has no interior step in either direction`() {
        assertNull(pageStep(pageCount = 1, index = 0, forward = true))
        assertNull(pageStep(pageCount = 1, index = 0, forward = false))
    }

    @Test
    fun `an index outside the run has nowhere to go`() {
        // Defensive: a re-sync can take a file away under a selection that was already made, and the
        // caller clamps before asking. Answering null rather than wrapping keeps a stale index from
        // turning a swipe into a page nobody selected.
        assertNull(pageStep(pageCount = 2, index = 2, forward = true))
        assertNull(pageStep(pageCount = 2, index = 2, forward = false))
        assertNull(pageStep(pageCount = 0, index = 0, forward = true))
        assertNull(pageStep(pageCount = 3, index = -1, forward = true))
    }
}
