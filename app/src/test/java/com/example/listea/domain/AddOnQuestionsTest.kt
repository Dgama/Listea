package com.example.listea.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The questionnaire payload: what a version asks, as one string, and what it takes to read it back.
 *
 * **Strictness is the subject, not an edge case.** The button payload next door is tolerant — a line
 * it cannot read costs its own chip — but a questionnaire that silently dropped a question would
 * keep the answer somebody gave to it and lose the words they were answering. So every malformed
 * payload here is expected to make the *whole version* unreadable, and each test names the one thing
 * a more forgiving decoder would have quietly accepted.
 */
class AddOnQuestionsTest {

    // --- round trip ---------------------------------------------------------------------------------

    @Test
    fun `all three types survive a round trip, in the order they were written`() {
        val questions = listOf(
            AddOnQuestion("q1", AddOnQuestionType.RATING, "How good are these?"),
            AddOnQuestion("q2", AddOnQuestionType.YES_NO, "Is there green?"),
            AddOnQuestion("q3", AddOnQuestionType.TEXT, "Anything to say?")
        )

        val encoded = encodeAddOnQuestions(questions)

        assertEquals(questions, decodeAddOnQuestions(encoded, ADD_ON_QUESTIONS_VERSION))
        // One question per line, and the order is the questionnaire's — a page that reordered its
        // questions between two reads would look like it had changed.
        assertEquals(3, encoded.lines().size)
        assertEquals(questions, decodeAddOnQuestions(encoded, ADD_ON_QUESTIONS_VERSION))
    }

    @Test
    fun `a prompt containing a tab is kept as one prompt rather than read as a fourth field`() {
        // The sanitiser keeps tabs out of typing, but a database written by something else may hold
        // one, and splitting on every tab would turn the tail of a prompt into a field nobody wrote.
        val question = AddOnQuestion("q1", AddOnQuestionType.TEXT, "A\tB")

        assertEquals(
            listOf(AddOnQuestion("q1", AddOnQuestionType.TEXT, "A\tB")),
            decodeAddOnQuestions(encodeAddOnQuestions(listOf(question)), 1)
        )
    }

    // --- unreadable versions ------------------------------------------------------------------------

    @Test
    fun `a version this build has never written is unreadable rather than guessed at`() {
        val text = encodeAddOnQuestions(listOf(AddOnQuestion("q1", AddOnQuestionType.RATING, "Score")))

        assertNull(decodeAddOnQuestions(text, 0))
        assertNull(decodeAddOnQuestions(text, 2))
        assertNull(decodeAddOnQuestions(text, -1))
    }

    @Test
    fun `an empty payload is no questionnaire, not an empty one`() {
        // The difference matters: an add-on with no questions is refused when it is created, so a
        // row that says it has one is corrupt. `emptyList` would draw a blank page and invite the
        // reader to believe that is what the version asked.
        assertNull(decodeAddOnQuestions("", 1))
        assertNull(decodeAddOnQuestions("   \n  ", 1))
    }

    @Test
    fun `a line missing a field makes the whole version unreadable`() {
        // Not just the line. Keeping the readable lines would show a questionnaire that is not the
        // one on record, and every answer to the missing question would become unexplainable.
        assertNull(decodeAddOnQuestions("q1\trating", 1))
        assertNull(decodeAddOnQuestions("q1\trating\tFine\nq2\tyes-no", 1))
    }

    @Test
    fun `an unknown type makes the whole version unreadable`() {
        assertNull(decodeAddOnQuestions("q1\tcolour\tWhich colour?", 1))
        assertNull(decodeAddOnQuestions("q1\tRATING\tScore", 1))
    }

    @Test
    fun `a blank id or a blank prompt makes the whole version unreadable`() {
        // A blank id is a question no answer could ever name; a blank prompt is a line in a form
        // with nothing to answer.
        assertNull(decodeAddOnQuestions("\trating\tScore", 1))
        assertNull(decodeAddOnQuestions("q1\trating\t   ", 1))
    }

    @Test
    fun `a duplicate id makes the whole version unreadable`() {
        // Two questions claiming one answer row is a table that cannot say which was answered.
        assertNull(decodeAddOnQuestions("q1\trating\tOne\nq1\ttext\tTwo", 1))
    }

    @Test
    fun `a good version is not refused because a neighbour is bad`() {
        // The check is per payload, not per store: one unreadable version must not make the readable
        // ones beside it unreadable too.
        val good = encodeAddOnQuestions(listOf(AddOnQuestion("q1", AddOnQuestionType.RATING, "Score")))

        assertEquals(1, decodeAddOnQuestions(good, 1)?.size)
        assertEquals(1, decodeAddOnQuestions(good, ADD_ON_QUESTIONS_VERSION)?.size)
        assertNull(decodeAddOnQuestions("q1\tunknown\tScore", 1))
    }
}
