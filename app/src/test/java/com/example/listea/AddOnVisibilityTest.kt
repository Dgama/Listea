package com.example.listea

import com.example.listea.domain.AddOnAnswer
import com.example.listea.domain.AddOnAnswerValue
import com.example.listea.domain.AddOnId
import com.example.listea.domain.AddOnQuestion
import com.example.listea.domain.AddOnQuestionType
import com.example.listea.domain.AddOnVersionRef
import com.example.listea.domain.AddOnQuestionType.RATING
import com.example.listea.domain.AddOnQuestionType.TEXT
import com.example.listea.domain.AddOnQuestionType.YES_NO
import com.example.listea.domain.StoredAddOnVersion
import com.example.listea.domain.resolveAddOnAnswers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which stored answers the attached questionnaire still asks.
 *
 * **Hiding is a reading, and that is what these tests are about.** No case here is expected to
 * change a row: an answer that stops being visible is the same answer, kept for the day the question
 * comes back, and every assertion that it is hidden is paired with an assertion that its value is
 * still there. That pairing is the difference between *hidden* and *deleted*, and it is the one the
 * storage was shaped to make possible.
 */
class AddOnVisibilityTest {

    // --- still asked --------------------------------------------------------------------------------

    @Test
    fun `an answer the current version still asks is visible, in the questionnaire's order`() {
        val addOn = stored(
            question("a", RATING, "Score"),
            question("b", YES_NO, "Green?"),
            question("c", TEXT, "Anything?")
        )

        // Answers in the opposite order to the questions, so an implementation that returned the
        // rows in the order it found them would fail rather than be flattered.
        val resolved = resolveAddOnAnswers(
            addOn,
            listOf(
                answer("c", TEXT, AddOnAnswerValue.Text("fine")),
                answer("a", RATING, AddOnAnswerValue.Rating(3)),
                answer("b", YES_NO, AddOnAnswerValue.YesNo(true))
            )
        )

        assertEquals(listOf("a", "b", "c"), resolved.visible.map { it.question.id })
        assertEquals(
            listOf("Score", "Green?", "Anything?"),
            resolved.visible.map { it.question.prompt }
        )
        assertTrue(resolved.hidden.isEmpty())
    }

    @Test
    fun `a question with no answer is simply not in the visible list`() {
        val addOn = stored(question("a", RATING, "Score"), question("b", TEXT, "Anything?"))

        val resolved = resolveAddOnAnswers(addOn, listOf(answer("a", RATING, AddOnAnswerValue.Rating(4))))

        assertEquals(listOf("a"), resolved.visible.map { it.question.id })
        assertTrue(resolved.hidden.isEmpty())
    }

    // --- hidden -------------------------------------------------------------------------------------

    @Test
    fun `a question the new version removed hides its answer, and the answer keeps its words`() {
        val addOn = stored(question("a", RATING, "Score"), version = 2)
        val gone = answer("old", TEXT, AddOnAnswerValue.Text("felt good"))

        val resolved = resolveAddOnAnswers(addOn, listOf(gone))

        assertTrue(resolved.visible.isEmpty())
        assertEquals(listOf("old"), resolved.hidden.map { it.questionId })
        // The row is untouched — same value, same authored version — because removing a question is
        // an edit to the questionnaire and not a decision about what somebody said.
        assertEquals(AddOnAnswerValue.Text("felt good"), resolved.hidden.single().value)
        assertEquals(1, resolved.hidden.single().authoredAddOnVersion)
    }

    @Test
    fun `a question that changed type hides the answer rather than reinterpreting it`() {
        // `3` was a score. After the editor turns the row into a Yes / No, it is not the third Yes
        // in some order — it is a value in a vocabulary the question no longer speaks.
        val addOn = stored(question("a", YES_NO, "Green?"))
        val rating = answer("a", RATING, AddOnAnswerValue.Rating(3))

        val resolved = resolveAddOnAnswers(addOn, listOf(rating))

        assertTrue(resolved.visible.isEmpty())
        assertEquals(AddOnAnswerValue.Rating(3), resolved.hidden.single().value)
    }

    @Test
    fun `an answer another add-on asked is not reused because a question id collided`() {
        // The lineage check. Question ids are opaque but not universally unique — an add-on cloned
        // by hand, an export and re-import, a later build's generator — and an answer belongs to the
        // questionnaire that asked it, not to whichever happens to be attached now.
        val addOn = stored(question("a", RATING, "Score"), lineage = "current")
        val foreign = answer("a", RATING, AddOnAnswerValue.Rating(5), lineage = "other")

        val resolved = resolveAddOnAnswers(addOn, listOf(foreign))

        assertTrue(resolved.visible.isEmpty())
        assertEquals(listOf("a"), resolved.hidden.map { it.questionId })
    }

    @Test
    fun `nothing attached hides every answer rather than dropping it`() {
        val answers = listOf(
            answer("a", RATING, AddOnAnswerValue.Rating(2)),
            answer("b", TEXT, AddOnAnswerValue.Text("note"))
        )

        val resolved = resolveAddOnAnswers(null, answers)

        assertTrue(resolved.visible.isEmpty())
        assertEquals(answers, resolved.hidden)
    }

    // --- restoring ----------------------------------------------------------------------------------

    @Test
    fun `restoring the same question brings the same words back`() {
        // The point of hiding rather than deleting, asserted as a sequence: remove the question,
        // read it as hidden, then put the same id and type back and read it as visible again.
        val before = stored(question("a", TEXT, "Anything?"))
        val answer = answer("a", TEXT, AddOnAnswerValue.Text("felt good"))

        val removed = resolveAddOnAnswers(stored(question("b", RATING, "Score")), listOf(answer))
        val restored = resolveAddOnAnswers(before, listOf(answer))

        assertEquals(listOf("a"), removed.hidden.map { it.questionId })
        assertEquals(AddOnAnswerValue.Text("felt good"), restored.visible.single().answer.value)
        assertTrue(restored.hidden.isEmpty())
    }

    // --- the fixture --------------------------------------------------------------------------------

    private fun question(id: String, type: AddOnQuestionType, prompt: String) =
        AddOnQuestion(id, type, prompt)

    private fun stored(
        vararg questions: AddOnQuestion,
        version: Int = 1,
        lineage: String = "addon"
    ) = StoredAddOnVersion(
        ref = AddOnVersionRef(AddOnId(lineage), version),
        name = "Review",
        questions = questions.toList()
    )

    private fun answer(
        questionId: String,
        type: AddOnQuestionType,
        value: AddOnAnswerValue,
        lineage: String = "addon"
    ) = AddOnAnswer(
        questionId = questionId,
        type = type,
        value = value,
        authoredAddOnId = AddOnId(lineage),
        authoredAddOnVersion = 1,
        createdAt = 0,
        updatedAt = 0
    )
}
