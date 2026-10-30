package com.example.listea.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Applicability is derived, ordered, and fail-closed.
 *
 * The table here is D2 of the v0.8.5 design: no condition means active, a closed branch propagates
 * down the chain, an unanswered source waits, and an answer that does not carry the named field
 * closes the branch rather than leaving it open forever. A missing source is inactive, because the
 * import has already marked that part unavailable and an orphaned condition must not activate it.
 */
class ApplicabilityTest {

    private fun formAnswer(vararg answers: Pair<String, String>): String =
        ReviewResponse.StructuredReview(
            completed = true,
            fields = answers.map { (key, value) ->
                FieldAnswer(fieldKey = key, type = "choice.single", value = value)
            }
        ).encode()

    private fun choiceAnswer(optionId: String): String =
        ReviewResponse.PrimaryReview(
            completed = true,
            events = listOf(ViewerEvent.Choice(optionId = optionId, label = optionId))
        ).encode()

    private fun gate(
        response: String? = null,
        position: Int = 0,
        key: String = "gate"
    ) = ApplicabilityPart(
        partKey = key,
        position = position,
        kind = "form",
        response = response
    )

    private fun target(
        condition: PartCondition?,
        position: Int = 1,
        key: String = "menu",
        kind: String = "media",
        response: String? = null
    ) = ApplicabilityPart(
        partKey = key,
        position = position,
        condition = condition,
        kind = kind,
        response = response
    )

    private fun branch(field: String = "ate", value: String = "yes") = PartCondition(
        sourcePartKey = "gate",
        field = field,
        match = PartConditionMatch.Equals,
        values = listOf(value)
    )

    @Test
    fun `a part without a condition is active`() {
        val result = evaluateApplicability(listOf(target(condition = null)))
        assertEquals(PartApplicability.Active, result["menu"])
    }

    @Test
    fun `an unanswered source leaves the target pending`() {
        assertEquals(
            PartApplicability.Pending,
            evaluateApplicability(listOf(gate(), target(branch())))["menu"]
        )
        assertEquals(
            PartApplicability.Pending,
            evaluateApplicability(listOf(gate(response = ""), target(branch())))["menu"]
        )
    }

    @Test
    fun `the source answer decides active and inactive`() {
        val yes = formAnswer("ate" to "yes")
        val no = formAnswer("ate" to "no")
        assertEquals(
            PartApplicability.Active,
            evaluateApplicability(listOf(gate(yes), target(branch())))["menu"]
        )
        assertEquals(
            PartApplicability.Inactive,
            evaluateApplicability(listOf(gate(no), target(branch())))["menu"]
        )
    }

    @Test
    fun `in matches any declared value`() {
        val condition = PartCondition(
            sourcePartKey = "gate",
            field = "ate",
            match = PartConditionMatch.In,
            values = listOf("yes", "maybe")
        )
        assertEquals(
            PartApplicability.Active,
            evaluateApplicability(
                listOf(gate(formAnswer("ate" to "maybe")), target(condition))
            )["menu"]
        )
        assertEquals(
            PartApplicability.Inactive,
            evaluateApplicability(
                listOf(gate(formAnswer("ate" to "no")), target(condition))
            )["menu"]
        )
    }

    @Test
    fun `an answered source without the named field is inactive`() {
        assertEquals(
            PartApplicability.Inactive,
            evaluateApplicability(
                listOf(gate(formAnswer("other" to "yes")), target(branch()))
            )["menu"]
        )
    }

    @Test
    fun `an unreadable answer is not an answer to the field`() {
        assertEquals(
            PartApplicability.Inactive,
            evaluateApplicability(
                listOf(gate(response = "garbage"), target(branch()))
            )["menu"]
        )
    }

    @Test
    fun `a missing or later source is inactive`() {
        assertEquals(
            PartApplicability.Inactive,
            evaluateApplicability(listOf(target(branch())))["menu"]
        )
        assertEquals(
            PartApplicability.Inactive,
            evaluateApplicability(
                listOf(
                    target(branch(), position = 0),
                    gate(formAnswer("ate" to "yes"), position = 1)
                )
            )["menu"]
        )
    }

    @Test
    fun `inactivity propagates even when the source holds an old answer`() {
        val ate = formAnswer("ate" to "no")
        val menu = target(branch(), kind = "form", response = formAnswer("note" to "old"))
        val note = target(
            PartCondition("menu", "note", PartConditionMatch.Equals, listOf("old")),
            position = 2,
            key = "note",
            kind = "form"
        )
        val result = evaluateApplicability(listOf(gate(ate), menu, note))
        assertEquals(PartApplicability.Inactive, result["menu"])
        assertEquals(PartApplicability.Inactive, result["note"])
    }

    @Test
    fun `pending propagates down a chain`() {
        val menu = target(branch(), kind = "form")
        val note = target(
            PartCondition("menu", "note", PartConditionMatch.Equals, listOf("old")),
            position = 2,
            key = "note",
            kind = "form"
        )
        val result = evaluateApplicability(listOf(gate(), menu, note))
        assertEquals(PartApplicability.Pending, result["menu"])
        assertEquals(PartApplicability.Pending, result["note"])
    }

    @Test
    fun `a choice source exposes the chosen option as choice`() {
        val source = ApplicabilityPart(
            partKey = "gate",
            position = 0,
            kind = "vocabulary",
            response = choiceAnswer("know")
        )
        val condition = PartCondition(
            sourcePartKey = "gate",
            field = CONDITION_FIELD_CHOICE,
            match = PartConditionMatch.Equals,
            values = listOf("know")
        )
        assertEquals(
            PartApplicability.Active,
            evaluateApplicability(listOf(source, target(condition)))["menu"]
        )
    }

    @Test
    fun `a detached part is not in the result`() {
        val detached = target(branch()).copy(present = false)
        val result = evaluateApplicability(listOf(gate(formAnswer("ate" to "yes")), detached))
        assertEquals(emptyMap<String, PartApplicability>(), result.filterKeys { it == "menu" })
    }
}
