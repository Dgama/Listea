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
 *
 * **v0.5.4 changed the field names, and the golden strings below are the record of it.**
 * `favorite=…;actions=…` became one `selections=…`, because `★` is a button with the id `favorite`
 * and a snapshot holding two fields about one button is a snapshot that can contradict itself. What
 * the format is *for* is unchanged, which is why the other tests here only needed their field name
 * moved: a response still carries ids rather than wire values, still canonicalises, and still says
 * `selections=` rather than nothing when the set is empty.
 */
class ReviewResponseTest {

    private fun response(
        completed: Boolean = false,
        selections: Set<String> = emptySet()
    ) = ReviewResponse.MediaReview(completed, selections)

    @Test
    fun `a response is exactly this text`() {
        assertEquals(
            "completed=true;selections=custom1",
            response(completed = true, selections = setOf("custom1")).encode()
        )
    }

    @Test
    fun `the same response always writes the same bytes`() {
        val tappedOneWay = response(selections = setOf("custom2", "custom1"))
        val tappedTheOther = response(selections = setOf("custom1", "custom2"))

        // Storage that varies with the order somebody happened to tap things looks changed when
        // nothing has, and every reader of the column then has to normalise before comparing.
        assertEquals(tappedOneWay.encode(), tappedTheOther.encode())
        assertEquals("completed=false;selections=custom1,custom2", tappedOneWay.encode())
    }

    @Test
    fun `a response carries button ids, not what settings call them on the wire`() {
        val encoded = response(selections = setOf("custom1")).encode()

        // "custom1" is the id the Task is marked with, and it is permanent. "cust1" is what the
        // built-in template currently sends that button as — configuration, renameable at any time,
        // and what the webhook body is assembled from. A stored response that mentioned it would
        // mean renaming a button rewrote what past reviews said.
        assertTrue(encoded.contains("custom1"))
        assertFalse(encoded.contains("cust1"))
    }

    @Test
    fun `the star is one id in the set rather than a field beside it`() {
        // The change of the release, asserted where its consequences are visible: a response marked
        // with `★` and a response marked with a tag differ in *which ids* they hold, not in which of
        // two fields is set. Until v0.5.4 the two could disagree about `★`, and every reader had to
        // pick a field to believe.
        assertEquals(
            "completed=false;selections=favorite",
            response(selections = setOf(Templates.FAVOURITE_BUTTON_ID)).encode()
        )
        assertEquals(
            "completed=false;selections=custom1",
            response(selections = setOf("custom1")).encode()
        )
        // And both together are one sorted list, which is what makes the format able to say so at
        // all.
        assertEquals(
            "completed=false;selections=custom1,favorite",
            response(selections = setOf("custom1", Templates.FAVOURITE_BUTTON_ID)).encode()
        )
    }

    @Test
    fun `an empty id is not a button`() {
        // A row written by hand, or a button deleted out from under a marked item, must decode to a
        // response about one button rather than about a nameless second one.
        assertEquals(
            "completed=false;selections=custom1",
            response(selections = setOf("custom1", "")).encode()
        )
    }

    @Test
    fun `a response with no buttons says so, rather than saying nothing`() {
        // The field is present and empty, so a reader can tell "marked with nothing" from "this
        // format does not record markings".
        assertEquals("completed=false;selections=", response().encode())
    }

    @Test
    fun `completion and the markings are independent`() {
        // Completing a Task does not mark it, and marking it does not complete it. Both travel in
        // every submission, and neither is derived from the other on the way in or out.
        val done = response(completed = true)
        val marked = response(selections = setOf("custom1"))

        assertTrue(done.encode().contains("selections="))
        assertEquals("completed=false;selections=custom1", marked.encode())
    }
}
