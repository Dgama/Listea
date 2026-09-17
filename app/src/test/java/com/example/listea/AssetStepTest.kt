package com.example.listea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a horizontal swipe does inside a Task, which is the whole of what a page step is.
 *
 * The Task is the container and the Asset is the page, so a swipe has two possible outcomes and
 * this decides between them: move a page, or leave the Task. It says *that* a swipe leaves and
 * never what leaving means — completing the Task in Review, opening the next item in the list
 * viewer — because that is the caller's business and the two modes are supposed to differ there.
 *
 * The property worth pinning hardest is the null. It is the only thing that makes a one-Asset Task
 * behave exactly as it did before there was anything to page: no interior page, so every swipe
 * leaves, and the built-in template's reading of a swipe is reached unchanged. A regression here
 * would be silent on every single-file Task in the app, which is nearly all of them.
 */
class AssetStepTest {

    // --- a Task with somewhere to go ---------------------------------------------------------------

    @Test
    fun `forward on the first of three pages moves to the second`() {
        assertEquals(1, assetStep(assetCount = 3, index = 0, forward = true))
    }

    @Test
    fun `back on the last of three pages moves to the second`() {
        assertEquals(1, assetStep(assetCount = 3, index = 2, forward = false))
    }

    @Test
    fun `the middle of a run moves one page either way`() {
        // Adjacency, not order: repeated steps walk the run rather than jumping around it, which
        // is what makes paging feel like paging.
        assertEquals(2, assetStep(assetCount = 5, index = 1, forward = true))
        assertEquals(0, assetStep(assetCount = 5, index = 1, forward = false))
    }

    // --- the ends, which are where a swipe leaves the Task ------------------------------------------

    @Test
    fun `forward off the last page leaves the Task`() {
        // In Review this is the submission, in the list viewer the next item. Neither is this
        // function's decision, which is exactly why it returns nothing rather than a page.
        assertNull(assetStep(assetCount = 3, index = 2, forward = true))
    }

    @Test
    fun `back off the first page leaves the Task`() {
        assertNull(assetStep(assetCount = 3, index = 0, forward = false))
    }

    // --- the single-Asset Task, which is nearly all of them ------------------------------------------

    @Test
    fun `a one-Asset Task has no interior page, so both swipes leave`() {
        assertNull(assetStep(assetCount = 1, index = 0, forward = true))
        assertNull(assetStep(assetCount = 1, index = 0, forward = false))
    }

    @Test
    fun `a Task with no Asset at all leaves in both directions`() {
        // A manual item. The surface renders a placeholder for it, and it is still swipeable and
        // completable exactly like a file's.
        assertNull(assetStep(assetCount = 0, index = 0, forward = true))
        assertNull(assetStep(assetCount = 0, index = 0, forward = false))
    }

    // --- a selection the Task no longer has ----------------------------------------------------------

    @Test
    fun `an index outside the run goes nowhere rather than to a page that is not there`() {
        // Reachable when a Task's Assets change under a selection that was already made — a
        // re-sync can take a file away while the screen it was on is still up. The honest answer
        // is that there is no page here, not a step to whatever the index now lands on.
        assertNull(assetStep(assetCount = 3, index = 3, forward = false))
        assertNull(assetStep(assetCount = 3, index = -1, forward = true))
    }
}
