package com.example.listea

import com.example.listea.domain.AddOnId
import com.example.listea.domain.AddOnQuestion
import com.example.listea.domain.AddOnQuestionType
import com.example.listea.domain.AddOnVersionRef
import com.example.listea.domain.StoredAddOnVersion
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentence a Refresh Import shows when it would move the questionnaire.
 *
 * The dialog reads the notice from the draft it is holding rather than from the Todo's row, and
 * that is the rule these tests are about: unticking the add-on withdraws the warning together with
 * the write it describes, and a refresh that would attach the version already held says nothing at
 * all. A notice that outlived its draft would be the dialog claiming a change the run will not make.
 */
class AddOnRefreshNoticeTest {

    @Test
    fun `a refresh that attaches the version already held says nothing`() {
        assertNull(
            refreshAddOnNotice(
                held = ref("a", 2),
                offered = listOf(stored("a", version = 2))
            )
        )
    }

    @Test
    fun `unticking the add-on withdraws the notice`() {
        val choice = stored("a", name = "Task review", version = 2)
        val held = ref("a", 1)

        // What the dialog does with the radio: the chosen lineage leaves the draft.
        assertNotNull(refreshAddOnNotice(held, listOf(choice)))
        assertNull(refreshAddOnNotice(withAddOn(held, choice, on = false), listOf(choice)))
    }

    @Test
    fun `a moved version is named, and the run it describes says nothing`() {
        val choice = stored("a", name = "Task review", version = 2)
        val held = ref("a", 1)

        val notice = refreshAddOnNotice(held, listOf(choice))
        assertNotNull(notice)
        assertTrue(notice!!.contains("Task review"))

        // The reference the refresh would submit, and the notice that reference would draw: now
        // that the upgrade has happened there is no move left to warn about.
        val submitted = upgradeAddOn(held, listOf(choice))
        assertNull(refreshAddOnNotice(submitted, listOf(choice)))
    }

    @Test
    fun `a held lineage the picker no longer offers says nothing`() {
        // A deleted lineage cannot be a move; the refresh keeps the held reference untouched and
        // the dialog has no new version to describe.
        assertNull(refreshAddOnNotice(ref("gone", 1), listOf(stored("a", version = 2))))
    }

    // --- the fixture ------------------------------------------------------------------------------

    private fun ref(id: String, version: Int) = AddOnVersionRef(AddOnId(id), version)

    private fun stored(
        id: String,
        name: String = id,
        version: Int
    ) = StoredAddOnVersion(
        ref = ref(id, version),
        name = name,
        questions = listOf(AddOnQuestion("q1", AddOnQuestionType.RATING, "Overall rating"))
    )
}
