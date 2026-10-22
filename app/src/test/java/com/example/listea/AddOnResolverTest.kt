package com.example.listea

import com.example.listea.data.AddOnEntity
import com.example.listea.data.AddOnVersionEntity
import com.example.listea.domain.ADD_ON_QUESTIONS_VERSION
import com.example.listea.domain.AddOnAnswer
import com.example.listea.domain.AddOnAnswerValue
import com.example.listea.domain.AddOnId
import com.example.listea.domain.AddOnQuestion
import com.example.listea.domain.AddOnQuestionType
import com.example.listea.domain.AddOnVersionRef
import com.example.listea.domain.encodeAddOnQuestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two add-on tables as one lookup: what a Task's reference resolves to, and what may be attached.
 *
 * The template resolver's rules, in a smaller vocabulary — and the same failing-closed rule: a
 * version whose payload this build cannot decode is absent entirely, so a Task attached to it draws
 * no page rather than a page of half a questionnaire.
 */
class AddOnResolverTest {

    // --- what a reference resolves to ---------------------------------------------------------------

    @Test
    fun `a reference resolves to the version it names, and nothing else does`() {
        val resolver = resolver(
            lineage("a", "Review") to listOf(
                version("a", 1, question("q1", "Rating one")),
                version("a", 2, question("q2", "Rating two"))
            )
        )

        assertEquals(
            listOf(question("q2", "Rating two")),
            resolver.versionOf(AddOnVersionRef(AddOnId("a"), 2))?.questions
        )
        assertNull(resolver.versionOf(AddOnVersionRef(AddOnId("a"), 3)))
        assertNull(resolver.versionOf(AddOnVersionRef(AddOnId("gone"), 1)))
        assertNull(resolver.versionOf(null))
    }

    @Test
    fun `an attached column is read through the same reference the resolver uses`() {
        val resolver = resolver(
            lineage("a", "Review") to listOf(version("a", 1, question("q1", "Score")))
        )

        assertEquals(
            "Review",
            resolver.versionFor(encodeAddOnAttachment(AddOnVersionRef(AddOnId("a"), 1)))?.name
        )
        // Empty and unreadable answer the same thing: no page. A caller comparing raw strings would
        // have said *attached* and then drawn nothing at all.
        assertNull(resolver.versionFor(""))
        assertNull(resolver.versionFor("a\t9"))
        assertNull(resolver.versionFor("nonsense"))
    }

    @Test
    fun `a version a later build wrote is skipped rather than half-read`() {
        val resolver = resolver(
            lineage("a", "Review") to listOf(
                version("a", 1, question("q1", "Score")),
                version("a", 2, question("q2", "New"), questionsVersion = ADD_ON_QUESTIONS_VERSION + 1)
            )
        )

        assertEquals(1, resolver.versionOf(AddOnVersionRef(AddOnId("a"), 1))?.ref?.version)
        assertNull(resolver.versionOf(AddOnVersionRef(AddOnId("a"), 2)))
    }

    @Test
    fun `an unreadable payload is skipped even under a version this build knows`() {
        // The other half of failing closed: the version number is fine and the text is not, so the
        // row is unreadable for a reason the number cannot tell apart.
        val resolver = resolver(
            lineage("a", "Review") to listOf(
                version("a", 1, question("q1", "Score")),
                version("a", 2, raw = "q2\tcolour\tNot a type", questionsVersion = ADD_ON_QUESTIONS_VERSION)
            )
        )

        assertNull(resolver.versionOf(AddOnVersionRef(AddOnId("a"), 2)))
        assertEquals(1, resolver.attachable.single().ref.version)
    }

    @Test
    fun `a version with no lineage has no name and is not offered`() {
        // The foreign key makes this unreachable in a real database; the resolver still refuses it
        // rather than listing a questionnaire under a name it does not have.
        val resolver = AddOnResolver.of(
            addOns = emptyList(),
            versions = listOf(version("a", 1, question("q1", "Score")))
        )

        assertNull(resolver.versionOf(AddOnVersionRef(AddOnId("a"), 1)))
        assertEquals(0, resolver.attachable.size)
    }

    // --- what there is to attach --------------------------------------------------------------------

    @Test
    fun `one thing is offered per lineage, at the highest version this build can read`() {
        val resolver = resolver(
            lineage("a", "Alpha") to listOf(
                version("a", 1, question("q1", "One")),
                version("a", 2, question("q1", "Two"))
            ),
            lineage("b", "Beta") to listOf(version("b", 1, question("q1", "Only"))),
            // Every version unreadable, so there is nothing to attach and nothing to draw.
            lineage("c", "Charlie") to listOf(
                version("c", 1, question("q1", "Lost"), questionsVersion = ADD_ON_QUESTIONS_VERSION + 1)
            )
        )

        assertEquals(listOf("Alpha", "Beta"), resolver.attachable.map { it.name })
        assertEquals(2, resolver.attachable.first().ref.version)
        // Sorted by name, so the picker does not reorder itself between reads.
        assertEquals(
            resolver.attachable.map { it.name },
            resolver.attachable.map { it.name }.sorted()
        )
    }

    @Test
    fun `a version a later build wrote does not hide the readable one beneath it`() {
        val resolver = resolver(
            lineage("a", "Alpha") to listOf(
                version("a", 1, question("q1", "One")),
                version("a", 2, question("q1", "Two"), questionsVersion = ADD_ON_QUESTIONS_VERSION + 1)
            )
        )

        assertEquals(1, resolver.attachable.single().ref.version)
    }

    // --- explaining a hidden answer -----------------------------------------------------------------

    @Test
    fun `a question is read from the version that asked it, not from the current one`() {
        // Info's use: the current version removed the question, and the answer should still be
        // shown in the words the reviewer answered.
        val resolver = resolver(
            lineage("a", "Review") to listOf(
                version("a", 1, question("old", "What did you think?"), question("kept", "Score")),
                version("a", 2, question("kept", "Score"))
            )
        )
        val answer = AddOnAnswer(
            questionId = "old",
            type = AddOnQuestionType.TEXT,
            value = AddOnAnswerValue.Text("fine"),
            authoredAddOnId = AddOnId("a"),
            authoredAddOnVersion = 1,
            createdAt = 0,
            updatedAt = 0
        )

        assertEquals("What did you think?", resolver.questionOf(answer)?.prompt)
        // And an authored version this build cannot read simply has no prompt to show.
        assertNull(resolver.questionOf(answer.copy(authoredAddOnVersion = 9)))
    }

    // --- the fixture --------------------------------------------------------------------------------

    private fun question(id: String, prompt: String, type: AddOnQuestionType = AddOnQuestionType.RATING) =
        AddOnQuestion(id, type, prompt)

    private fun lineage(id: String, name: String) = AddOnEntity(id = id, name = name, createdAt = 0)

    private fun version(
        id: String,
        version: Int,
        vararg questions: AddOnQuestion,
        raw: String = encodeAddOnQuestions(questions.toList()),
        questionsVersion: Int = ADD_ON_QUESTIONS_VERSION
    ) = AddOnVersionEntity(
        addOnId = id,
        version = version,
        questions = raw,
        questionsVersion = questionsVersion,
        createdAt = 0
    )

    private fun resolver(
        vararg rows: Pair<AddOnEntity, List<AddOnVersionEntity>>
    ): AddOnResolver = AddOnResolver.of(
        addOns = rows.map { it.first },
        versions = rows.flatMap { it.second }
    )
}
