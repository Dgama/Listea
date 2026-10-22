package com.example.listea

import com.example.listea.domain.AddOnId
import com.example.listea.domain.AddOnQuestion
import com.example.listea.domain.AddOnQuestionType
import com.example.listea.domain.AddOnVersionRef
import com.example.listea.domain.StoredAddOnVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a Todo or a Task is attached to, and how a run carries that forward.
 *
 * The template attachment's single-valued sibling. The shape is smaller — one reference, never a
 * list — and every rule here follows from that: *turning one on replaces*, *an upgrade never adds*,
 * and a column naming two lineages has to be read down to one rather than tolerated as a list.
 */
class AddOnAttachmentTest {

    // --- the column ---------------------------------------------------------------------------------

    @Test
    fun `one reference survives a round trip, and nothing at all is the empty string`() {
        assertEquals("", encodeAddOnAttachment(null))
        assertNull(decodeAddOnAttachment(""))

        val ref = AddOnVersionRef(AddOnId("a"), 2)
        assertEquals("a\t2", encodeAddOnAttachment(ref))
        assertEquals(ref, decodeAddOnAttachment(encodeAddOnAttachment(ref)))
    }

    @Test
    fun `a line this build cannot read costs the column nothing it could otherwise answer`() {
        // The same tolerance the template decoder has, narrowed to one answer: a bad line is skipped
        // and a good one may still supply the reference. The good line is last, so an over-eager
        // parse cannot be mistaken for tolerance.
        val text = listOf(
            "only-an-id",
            "\t1",
            "a\tnot-a-number",
            "a\t0",
            "a\t-2",
            "a\t2"
        ).joinToString("\n")

        assertEquals(AddOnVersionRef(AddOnId("a"), 2), decodeAddOnAttachment(text))
        assertNull(decodeAddOnAttachment("nothing here"))
        assertNull(decodeAddOnAttachment("\t\t"))
    }

    @Test
    fun `the first lineage a column names is the one it means`() {
        // These columns are written with one line. A column with two is a contradiction, and the
        // repair is to read the first rather than to depend on how the writer scrambled them.
        assertEquals(
            AddOnVersionRef(AddOnId("a"), 1),
            decodeAddOnAttachment("a\t1\nb\t1")
        )
        assertEquals(
            AddOnVersionRef(AddOnId("a"), 1),
            decodeAddOnAttachment("\na\t1\nb\t3")
        )
    }

    @Test
    fun `a lineage named twice keeps its highest version`() {
        // The rule the template column follows, and for the same reason: a reference beside a
        // reference is a record of how the add-on stood when a run happened, not a choice between
        // two versions somebody is making.
        assertEquals(
            AddOnVersionRef(AddOnId("a"), 2),
            decodeAddOnAttachment("a\t1\na\t2")
        )
        assertEquals(
            AddOnVersionRef(AddOnId("a"), 3),
            decodeAddOnAttachment("a\t3\na\t1")
        )
    }

    // --- the picker ---------------------------------------------------------------------------------

    @Test
    fun `choosing one replaces what was held, because there is one slot`() {
        val held = AddOnVersionRef(AddOnId("a"), 1)
        val choice = stored("b", version = 2)

        assertEquals(choice.ref, withAddOn(held, choice, on = true))
        assertEquals(choice.ref, withAddOn(null, choice, on = true))
        // Choosing the same lineage at a newer version also replaces rather than sitting beside it.
        assertEquals(
            AddOnVersionRef(AddOnId("a"), 2),
            withAddOn(held, stored("a", version = 2), on = true)
        )
    }

    @Test
    fun `turning off the held add-on clears it, and turning off another row does not`() {
        val held = AddOnVersionRef(AddOnId("a"), 1)

        assertNull(withAddOn(held, stored("a"), on = false))
        // A stale tap on a row that is not the one held is not a decision to remove what is.
        assertEquals(held, withAddOn(held, stored("b"), on = false))
        assertNull(withAddOn(null, stored("a"), on = false))
    }

    // --- the refresh that carries a Todo forward ----------------------------------------------------

    @Test
    fun `an upgrade moves the held lineage to the offered version`() {
        val held = AddOnVersionRef(AddOnId("a"), 1)

        assertEquals(
            AddOnVersionRef(AddOnId("a"), 2),
            upgradeAddOn(held, listOf(stored("a", version = 2), stored("b", version = 1)))
        )
    }

    @Test
    fun `an upgrade never adds an add-on and never drops one it cannot name`() {
        // A refresh is a refresh of what the Todo holds, not a re-choosing. A lineage missing from
        // the offered list is one this build cannot read; the reference already recorded is the
        // better answer than an unreadable add-on that looks like a decision to remove one.
        val held = AddOnVersionRef(AddOnId("gone"), 2)

        assertNull(upgradeAddOn(null, listOf(stored("a", version = 2))))
        assertEquals(held, upgradeAddOn(held, listOf(stored("a", version = 2))))
        assertEquals(held, upgradeAddOn(held, emptyList()))
    }

    @Test
    fun `an unchanged version is not a move`() {
        val held = AddOnVersionRef(AddOnId("a"), 2)

        assertEquals(held, upgradeAddOn(held, listOf(stored("a", version = 2))))
        assertNull(refreshAddOnNotice(held, listOf(stored("a", version = 2))))
        assertNull(refreshAddOnNotice(held, emptyList()))
        assertNull(refreshAddOnNotice(null, listOf(stored("a", version = 2))))
    }

    @Test
    fun `the refresh notice names the add-on and says what happens to answers`() {
        val notice = refreshAddOnNotice(
            AddOnVersionRef(AddOnId("a"), 1),
            listOf(stored("a", name = "Task review", version = 2))
        )

        assertNotNull(notice)
        assertTrue(notice!!.contains("Task review"))
        assertTrue(notice.contains("read under the new version"))
        assertTrue(notice.contains("kept on the Task but no longer shown"))
        assertTrue(notice.contains("starts unanswered"))
    }

    // --- the fixture --------------------------------------------------------------------------------

    private fun stored(
        id: String,
        name: String = id,
        version: Int = 1
    ) = StoredAddOnVersion(
        ref = AddOnVersionRef(AddOnId(id), version),
        name = name,
        questions = listOf(AddOnQuestion("q1", AddOnQuestionType.RATING, "Overall rating"))
    )
}
