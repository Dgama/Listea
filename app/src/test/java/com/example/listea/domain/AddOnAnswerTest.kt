package com.example.listea.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * One answer's stored text, and the vocabulary each question type allows.
 *
 * Two directions, and they are deliberately not symmetric: encoding is total — every [AddOnAnswerValue]
 * has a text — while parsing can fail, because a row may have been written by a build with a wider
 * vocabulary or by something that is not this app. A value that does not fit is `null`, which the
 * caller reads as *omit*, never as a value to interpret.
 */
class AddOnAnswerTest {

    // --- rating -------------------------------------------------------------------------------------

    @Test
    fun `a rating is a number from one to five, and nothing else`() {
        assertEquals("3", encodeAddOnAnswer(AddOnAnswerValue.Rating(3)))
        assertEquals(AddOnAnswerValue.Rating(3), parseAddOnAnswer(AddOnQuestionType.RATING, "3"))
        assertEquals(AddOnAnswerValue.Rating(5), parseAddOnAnswer(AddOnQuestionType.RATING, " 5 "))

        // Not scores: zero and six are outside the five buttons the page draws, and non-numbers are
        // not a review that took another form.
        assertNull(parseAddOnAnswer(AddOnQuestionType.RATING, "0"))
        assertNull(parseAddOnAnswer(AddOnQuestionType.RATING, "6"))
        assertNull(parseAddOnAnswer(AddOnQuestionType.RATING, "-1"))
        assertNull(parseAddOnAnswer(AddOnQuestionType.RATING, "three"))
        assertNull(parseAddOnAnswer(AddOnQuestionType.RATING, ""))
    }

    // --- yes / no -----------------------------------------------------------------------------------

    @Test
    fun `yes and no are the two words, and any other word is not an answer`() {
        assertEquals("yes", encodeAddOnAnswer(AddOnAnswerValue.YesNo(true)))
        assertEquals("no", encodeAddOnAnswer(AddOnAnswerValue.YesNo(false)))
        assertEquals(AddOnAnswerValue.YesNo(true), parseAddOnAnswer(AddOnQuestionType.YES_NO, "yes"))
        assertEquals(AddOnAnswerValue.YesNo(false), parseAddOnAnswer(AddOnQuestionType.YES_NO, " no "))

        // A value read under the wrong type is the case this guard exists for: `3` is a rating, and
        // `true` is a boolean from some other format, not this one's Yes.
        assertNull(parseAddOnAnswer(AddOnQuestionType.YES_NO, "true"))
        assertNull(parseAddOnAnswer(AddOnQuestionType.YES_NO, "maybe"))
        assertNull(parseAddOnAnswer(AddOnQuestionType.YES_NO, "1"))
        assertNull(parseAddOnAnswer(AddOnQuestionType.YES_NO, ""))
    }

    // --- text ---------------------------------------------------------------------------------------

    @Test
    fun `text is trimmed at both ends, and blank text is no answer`() {
        assertEquals("felt good", encodeAddOnAnswer(AddOnAnswerValue.Text("  felt good  ")))
        assertEquals(
            AddOnAnswerValue.Text("felt good"),
            parseAddOnAnswer(AddOnQuestionType.TEXT, "  felt good  ")
        )

        // The same rule the page applies when the field loses focus: clearing the box deletes the
        // answer rather than storing a row that says nothing.
        assertNull(parseAddOnAnswer(AddOnQuestionType.TEXT, ""))
        assertNull(parseAddOnAnswer(AddOnQuestionType.TEXT, "   "))
    }

    @Test
    fun `text that reads like a number or a boolean stays text`() {
        // The type is carried beside the value rather than inferred from it, so a one-word answer
        // that happens to spell `yes` is still the reviewer's words under a Text question.
        assertEquals(
            AddOnAnswerValue.Text("yes"),
            parseAddOnAnswer(AddOnQuestionType.TEXT, "yes")
        )
        assertEquals(
            AddOnAnswerValue.Text("3"),
            parseAddOnAnswer(AddOnQuestionType.TEXT, "3")
        )
    }
}
