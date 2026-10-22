package com.example.listea

import com.example.listea.domain.AddOnAnswer
import com.example.listea.domain.AddOnAnswerValue
import com.example.listea.domain.AddOnId
import com.example.listea.domain.AddOnQuestion
import com.example.listea.domain.AddOnQuestionType
import com.example.listea.domain.AddOnVersionRef
import com.example.listea.domain.StoredAddOnVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The add-on section of the Info sheet.
 *
 * Info is where an answer goes when it is no longer on the page: the hidden-not-deleted promise is
 * only real if somebody can read the kept answer, so the wording of that row — *no longer asked*,
 * with the prompt it was given when the authored version can still be read — is the promise.
 */
class AddOnInfoTest {

    @Test
    fun `there is no section for a Task with no readable add-on`() {
        assertNull(addOnInfoOf(null, listOf(answer("q1", AddOnAnswerValue.Rating(3))), resolver()))
    }

    @Test
    fun `visible answers are listed in question order and read as typed values`() {
        val info = addOnInfoOf(current, answers, resolver())!!

        val rows = addOnInfoRows(info)

        assertEquals("Add-on", rows.first().label)
        assertEquals("Task review · version 2 · 2 answers", rows.first().value)
        // The questionnaire's order, not the order the rows were written.
        assertEquals(listOf("Rating", "Notes"), rows.drop(1).map { it.label })
        assertEquals(listOf("4 / 5", "good light"), rows.drop(1).map { it.value })
    }

    @Test
    fun `a hidden answer keeps its value and says why it is not on the page`() {
        val removed = answer("q-old", AddOnAnswerValue.YesNo(true), version = 1)

        val info = addOnInfoOf(current, listOf(answer("q1", AddOnAnswerValue.Rating(4)), removed), resolver())!!

        val hidden = addOnInfoRows(info).last()
        // The prompt comes from version 1, the version that asked — the current one has no such
        // question to quote.
        assertEquals("Still a life photo?", hidden.label)
        assertEquals("Yes · no longer asked", hidden.value)
    }

    @Test
    fun `a hidden answer whose version is gone shows the value without inventing a prompt`() {
        val removed = answer("q-old", AddOnAnswerValue.Text("kept"), version = 7)

        val info = addOnInfoOf(current, listOf(removed), resolver())!!

        val row = addOnInfoRows(info).last()
        assertEquals("A question this version no longer asks", row.label)
        assertEquals("kept · no longer asked", row.value)
    }

    // --- the fixture ------------------------------------------------------------------------------

    private val current = StoredAddOnVersion(
        ref = AddOnVersionRef(AddOnId("addon"), 2),
        name = "Task review",
        questions = listOf(
            AddOnQuestion("q1", AddOnQuestionType.RATING, "Rating"),
            AddOnQuestion("q2", AddOnQuestionType.TEXT, "Notes")
        )
    )

    private fun answer(
        questionId: String,
        value: AddOnAnswerValue,
        version: Int = 2
    ) = AddOnAnswer(
        questionId = questionId,
        type = when (value) {
            is AddOnAnswerValue.Rating -> AddOnQuestionType.RATING
            is AddOnAnswerValue.YesNo -> AddOnQuestionType.YES_NO
            is AddOnAnswerValue.Text -> AddOnQuestionType.TEXT
        },
        value = value,
        authoredAddOnId = AddOnId("addon"),
        authoredAddOnVersion = version,
        createdAt = 0,
        updatedAt = 0
    )

    private val answers = listOf(
        // Written notes first: the page's order comes from the questions, not from the table.
        answer("q2", AddOnAnswerValue.Text("good light")),
        answer("q1", AddOnAnswerValue.Rating(4))
    )

    private fun resolver(): AddOnResolver = AddOnResolver.of(
        addOns = listOf(com.example.listea.data.AddOnEntity("addon", "Task review", 0)),
        versions = listOf(
            com.example.listea.data.AddOnVersionEntity(
                addOnId = "addon",
                version = 1,
                questions = "q1\trating\tRating\nq-old\tyes-no\tStill a life photo?",
                questionsVersion = 1,
                createdAt = 0
            ),
            com.example.listea.data.AddOnVersionEntity(
                addOnId = "addon",
                version = 2,
                questions = "q1\trating\tRating\nq2\ttext\tNotes",
                questionsVersion = 1,
                createdAt = 1
            )
        )
    )
}
