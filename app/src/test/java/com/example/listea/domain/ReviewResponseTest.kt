package com.example.listea.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two grammars one stored response column holds.
 *
 * The legacy form is pinned unchanged: every response written before v0.8.1 is this shape, and a
 * reader that moved with a new writer would make old judgments unreadable for no reason. The primary
 * form is pinned strictly for the opposite reason — it records what somebody decided, so a line this
 * build cannot read makes the whole response unreadable rather than being dropped.
 */
class ReviewResponseTest {

    private fun parse(text: String) = ReviewResponse.parse(text)

    // --- the legacy grammar, exactly as it was ------------------------------------------------

    @Test
    fun `a media review encodes as it always did`() {
        assertEquals(
            "completed=true;selections=a,b",
            ReviewResponse.MediaReview(true, setOf("b", "a")).encode()
        )
        // Blank ids are dropped and the set is sorted, so the same judgment is the same bytes.
        assertEquals(
            "completed=false;selections=",
            ReviewResponse.MediaReview(false, setOf("")).encode()
        )
    }

    @Test
    fun `a media review decodes as it always did`() {
        assertEquals(
            ReviewResponse.MediaReview(true, setOf("a", "b")),
            parse("completed=true;selections=a,b")
        )
    }

    @Test
    fun `an older mark spelling is read for the half the two agree on`() {
        // v0.5.4's predecessors wrote `favorite` and `actions`; their meaning is not recoverable as
        // a selection set, and the reader says so by not claiming to have recovered it.
        assertEquals(
            ReviewResponse.MediaReview(true, emptySet()),
            parse("completed=true;favorite=true;actions=a,b")
        )
    }

    @Test
    fun `a legacy response with no completed flag is unreadable`() {
        assertNull(parse("selections=a"))
    }

    // --- the primary grammar --------------------------------------------------------------------

    @Test
    fun `a primary review round-trips, four decimals and all`() {
        val response = ReviewResponse.PrimaryReview(
            completed = true,
            events = listOf(
                ViewerEvent.ImagePoint(0.4213, 0.8137, "#aabbcc", "2026-07-11/a.jpg")
            )
        )
        val encoded = response.encode()

        assertEquals(
            listOf(
                "primary=2",
                "completed=true",
                "event=image.point\t0.4213\t0.8137\t#AABBCC\t2026-07-11/a.jpg"
            ).joinToString("\n"),
            encoded
        )
        // The reader returns the canonical spelling the envelope carries; a lower-case colour is
        // the same fact written two ways, and only one of them is allowed on the record.
        assertEquals(
            ReviewResponse.PrimaryReview(
                completed = true,
                events = listOf(
                    ViewerEvent.ImagePoint(0.4213, 0.8137, "#AABBCC", "2026-07-11/a.jpg")
                )
            ),
            parse(encoded)
        )
    }

    @Test
    fun `a point is written with four fixed decimals`() {
        val encoded = ReviewResponse.PrimaryReview(
            completed = false,
            events = listOf(ViewerEvent.ImagePoint(0.1, 1.0, "#000000"))
        ).encode()

        assertEquals(
            "event=image.point\t0.1000\t1.0000\t#000000\t",
            encoded.split('\n')[2]
        )
    }

    @Test
    fun `a commit with no events is a legal value of the type`() {
        // The refusal for a definition that promised events lives at the write boundary; the type
        // itself has to be able to say *I submitted and there was nothing to submit*.
        val response = ReviewResponse.PrimaryReview(completed = true, events = emptyList())
        assertEquals(response, parse(response.encode()))
    }

    @Test
    fun `a point outside the picture makes the response unreadable`() {
        assertNull(
            parse(
                "primary=2\ncompleted=true\n" +
                    "event=image.point\t1.5000\t0.5000\t#AABBCC\tpath"
            )
        )
    }

    @Test
    fun `an unknown event type makes the response unreadable`() {
        assertNull(
            parse(
                "primary=2\ncompleted=true\n" +
                    "event=choice\t0.5000\t0.5000\t#AABBCC\tpath"
            )
        )
    }

    @Test
    fun `a malformed colour makes the response unreadable`() {
        for (color in listOf("#ABC", "#AABBC", "#AABBCG", "AABBCC")) {
            assertNull(
                "colour $color",
                parse(
                    "primary=2\ncompleted=true\n" +
                        "event=image.point\t0.5000\t0.5000\t$color\tpath"
                )
            )
        }
    }

    @Test
    fun `a later envelope is unreadable rather than read as this one`() {
        assertNull(parse("primary=3\ncompleted=true"))
    }

    @Test
    fun `a duplicate flag makes the response unreadable`() {
        assertNull(parse("primary=2\ncompleted=true\ncompleted=false"))
    }

    @Test
    fun `an unknown line makes the response unreadable`() {
        assertNull(parse("primary=2\ncompleted=true\nnote=anything"))
    }

    @Test
    fun `a missing completed flag makes the response unreadable`() {
        assertNull(parse("primary=2"))
    }
}
