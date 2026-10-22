package com.example.listea

import com.example.listea.domain.AddOnQuestion
import com.example.listea.domain.AddOnQuestionType
import com.example.listea.domain.decodeAddOnDraftQuestions
import com.example.listea.domain.decodeAddOnQuestions
import com.example.listea.domain.encodeAddOnQuestions
import com.example.listea.domain.restorableAddOnQuestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the add-on editor accepts, and when it says a draft has changed.
 *
 * The store's refusals in the page's words. The page is where the user is told *which* row is wrong,
 * so these rules have to exist before a write is attempted — and they are checked here without a
 * screen, because a rule that only exists inside a composable is a rule that only a device can
 * confirm.
 */
class AddOnDraftTest {

    @Test
    fun `a named add-on with answered prompts is writable`() {
        assertNull(
            addOnDraftProblem(
                "Task review",
                listOf(
                    question("q1", "How good are these?", AddOnQuestionType.RATING),
                    question("q2", "Is there green?", AddOnQuestionType.YES_NO),
                    question("q3", "Anything to say?", AddOnQuestionType.TEXT)
                )
            )
        )
    }

    @Test
    fun `a blank or whitespace-only name is refused`() {
        val questions = listOf(question("q1", "Score"))

        assertEquals(AddOnDraftProblem.NameRequired, addOnDraftProblem("", questions))
        assertEquals(AddOnDraftProblem.NameRequired, addOnDraftProblem("   \t ", questions))
    }

    @Test
    fun `an add-on with no questions is refused`() {
        // The same rule the store enforces: a questionnaire with nothing to ask draws a blank page
        // on every Task that attaches it.
        assertEquals(AddOnDraftProblem.NoQuestions, addOnDraftProblem("Review", emptyList()))
    }

    @Test
    fun `a blank prompt names the row that needs one`() {
        val problem = addOnDraftProblem(
            "Review",
            listOf(question("q1", "Fine"), question("q2", "   \r\n"))
        )

        // The index, zero-based, because both the error field and the message are decided from it;
        // the message itself names the row from one.
        assertEquals(AddOnDraftProblem.PromptRequired(1), problem)
        assertEquals("Question 2 needs a prompt.", addOnDraftMessage(problem!!))
    }

    @Test
    fun `two questions with one id are refused`() {
        assertEquals(
            AddOnDraftProblem.DuplicateQuestion,
            addOnDraftProblem("Review", listOf(question("q1", "One"), question("q1", "Two")))
        )
    }

    @Test
    fun `the first problem is the one named`() {
        // One line in the bar, and the first thing to fix is the useful thing to say.
        assertEquals(
            AddOnDraftProblem.NameRequired,
            addOnDraftProblem("  ", listOf(question("q1", "   ")))
        )
        assertEquals(
            AddOnDraftProblem.NoQuestions,
            addOnDraftProblem("Review", emptyList())
        )
    }

    // --- when Update is live ------------------------------------------------------------------------

    @Test
    fun `an unchanged draft has not changed, and a rename alone has`() {
        val stored = encodeAddOnQuestions(listOf(question("q1", "Score")))

        assertFalse(addOnDraftChanged("Review", stored, "Review", stored))
        assertTrue(addOnDraftChanged("Renamed", stored, "Review", stored))
    }

    @Test
    fun `a retyped or reworded question is a change, and so is a new one`() {
        val rating = encodeAddOnQuestions(listOf(question("q1", "Score", AddOnQuestionType.RATING)))
        val text = encodeAddOnQuestions(listOf(question("q1", "Score", AddOnQuestionType.TEXT)))
        val reworded = encodeAddOnQuestions(listOf(question("q1", "How good?", AddOnQuestionType.RATING)))
        val added = encodeAddOnQuestions(
            listOf(
                question("q1", "Score", AddOnQuestionType.RATING),
                question("q2", "Anything?", AddOnQuestionType.TEXT)
            )
        )

        // A type change is a change even though the id and the words are the same: it is a different
        // question to the reviewer, and it hides any answer given in the old vocabulary.
        assertTrue(addOnDraftChanged("Review", text, "Review", rating))
        assertTrue(addOnDraftChanged("Review", reworded, "Review", rating))
        assertTrue(addOnDraftChanged("Review", added, "Review", rating))
    }

    @Test
    fun `removing the last question is not a draft Update can write`() {
        // The page can reach this — remove every row — and the save bar disables rather than letting
        // the store refuse silently.
        assertEquals(AddOnDraftProblem.NoQuestions, addOnDraftProblem("Review", emptyList()))
    }

    // --- reading the draft back on every keystroke --------------------------------------------------

    @Test
    fun `a draft survives a prompt being cleared to retype it`() {
        // The page reads its question list back from the encoded draft on every keystroke. A prompt
        // on its way to being retyped is blank for a moment; the stored reading calls that payload
        // unreadable, and the whole questionnaire would vanish from under the cursor. The draft
        // reading keeps the row, and validation is what says it still needs words.
        val draft = encodeAddOnQuestions(
            listOf(question("q1", "Score"), question("q2", ""))
        )

        assertNull(decodeAddOnQuestions(draft, 1))
        assertEquals(
            listOf(question("q1", "Score"), question("q2", "")),
            decodeAddOnDraftQuestions(draft)
        )
        assertEquals(
            AddOnDraftProblem.PromptRequired(1),
            addOnDraftProblem("Review", decodeAddOnDraftQuestions(draft))
        )
    }

    @Test
    fun `a draft with every row removed decodes to no questions`() {
        assertEquals(emptyList<AddOnQuestion>(), decodeAddOnDraftQuestions(""))
    }

    @Test
    fun `the draft reading agrees with the stored reading on a written payload`() {
        val stored = encodeAddOnQuestions(
            listOf(
                question("q1", "How good are these?", AddOnQuestionType.RATING),
                question("q2", "Is there green?", AddOnQuestionType.YES_NO),
                question("q3", "Anything to say?", AddOnQuestionType.TEXT)
            )
        )

        assertEquals(decodeAddOnQuestions(stored, 1), decodeAddOnDraftQuestions(stored))
    }

    @Test
    fun `a prompt is kept as typed while it is being written`() {
        // Trimming in the draft would delete the space the user has just typed between two words.
        val draft = encodeAddOnQuestions(listOf(question("q1", "Contains green ")))

        assertEquals(listOf(question("q1", "Contains green ")), decodeAddOnDraftQuestions(draft))
    }

    @Test
    fun `a removed question is offered back with the id its answers already name`() {
        // The trap this closes: *Add question* mints a fresh id, so the editor used to promise a
        // restore its own button could not make. The list is built from earlier versions, which is
        // the only place the old id still exists.
        val earlier = listOf(
            listOf(
                question("q1", "Overall rating"),
                question("q2", "Is there green?", AddOnQuestionType.YES_NO)
            )
        )
        val kept = listOf(question("q1", "Overall rating"))

        assertEquals(
            listOf(question("q2", "Is there green?", AddOnQuestionType.YES_NO)),
            restorableAddOnQuestions(earlier, kept)
        )
        assertEquals(
            emptyList<AddOnQuestion>(),
            restorableAddOnQuestions(earlier, kept + question("q2", "Is there green?", AddOnQuestionType.YES_NO))
        )
    }

    @Test
    fun `a restored question carries the newest earlier wording and keeps its place in the list`() {
        // Removed in version 2, reworded once before that, and one question that was never removed.
        val earlier = listOf(
            listOf(
                question("q2", "Is there green?", AddOnQuestionType.YES_NO),
                question("q1", "Overall rating")
            ),
            listOf(
                question("q1", "Overall rating, 1 to 5"),
                question("q2", "Any green at all?", AddOnQuestionType.YES_NO),
                question("q3", "Anything to say?", AddOnQuestionType.TEXT)
            )
        )

        val restorable = restorableAddOnQuestions(earlier, listOf(question("q1", "Overall rating, 1 to 5")))

        // q2 first (it appeared first), q3 second; q1 is still kept.
        assertEquals(listOf("q2", "q3"), restorable.map { it.id })
        assertEquals("Any green at all?", restorable.single { it.id == "q2" }.prompt)
    }

    // --- the fixture --------------------------------------------------------------------------------

    private fun question(
        id: String,
        prompt: String,
        type: AddOnQuestionType = AddOnQuestionType.RATING
    ) = AddOnQuestion(id, type, prompt)
}
