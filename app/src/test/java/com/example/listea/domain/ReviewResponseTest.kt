package com.example.listea.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The form a submitted response is stored in.
 *
 * Worth pinning character for character, for the same reason the webhook body is: it is written to
 * a column now and read by a future release that will not have this code to consult. A golden
 * expectation makes a change to it something somebody wrote down deliberately rather than
 * something that happened.
 *
 * The other half is what the format deliberately is *not*. §8 freezes the wire format and §5 puts
 * the stored snapshot on the domain side of the boundary; if the two ever became each other, a
 * settings change would rewrite stored history.
 */
class ReviewResponseTest {

    private fun response(
        completed: Boolean = false,
        favorite: Boolean = false,
        actions: Set<String> = emptySet()
    ) = ReviewResponse.MediaReview(completed, favorite, actions)

    @Test
    fun `a response is exactly this text`() {
        assertEquals(
            "completed=true;favorite=true;actions=custom1",
            response(completed = true, favorite = true, actions = setOf("custom1")).encode()
        )
    }

    @Test
    fun `the same response always writes the same bytes`() {
        val tappedOneWay = response(actions = setOf("custom2", "custom1"))
        val tappedTheOther = response(actions = setOf("custom1", "custom2"))

        // Storage that varies with the order somebody happened to tap things looks changed when
        // nothing has, and every reader of the column then has to normalise before comparing.
        assertEquals(tappedOneWay.encode(), tappedTheOther.encode())
        assertEquals("completed=false;favorite=false;actions=custom1,custom2", tappedOneWay.encode())
    }

    @Test
    fun `a response carries action ids, not what settings call them on the wire`() {
        val encoded = response(actions = setOf("custom1")).encode()

        // "custom1" is the id the Task is marked with, and it is permanent. "cust1" is what the
        // default settings currently send that action as — configuration, renameable at any time,
        // and what the webhook body is assembled from. A stored response that mentioned it would
        // mean renaming an action rewrote what past reviews said.
        assertTrue(encoded.contains("custom1"))
        assertFalse(encoded.contains("cust1"))
    }

    @Test
    fun `an empty id is not an action`() {
        // A row written by hand, or an action deleted out from under a marked item, must decode to
        // a response about one action rather than about a nameless second one.
        assertEquals(
            "completed=false;favorite=false;actions=custom1",
            response(actions = setOf("custom1", "")).encode()
        )
    }

    @Test
    fun `a response with no actions says so, rather than saying nothing`() {
        // The field is present and empty, so a reader can tell "marked with nothing" from "this
        // format does not record actions".
        assertEquals("completed=false;favorite=false;actions=", response().encode())
    }

    @Test
    fun `completion and the markings are independent`() {
        // Completing a Task does not mark it, and marking it does not complete it. Both travel in
        // every submission, and neither is derived from the other on the way in or out.
        val done = response(completed = true)
        val marked = response(favorite = true, actions = setOf("custom1"))

        assertTrue(done.encode().contains("actions="))
        assertEquals("completed=false;favorite=true;actions=custom1", marked.encode())
    }
}
