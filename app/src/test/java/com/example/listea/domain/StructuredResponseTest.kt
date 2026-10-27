package com.example.listea.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The stored shape of a structured answer.
 *
 * A form's judgment is a set of `(fieldKey, type, value)` triples, not viewer events, and it is
 * canonical: the same answers produce the same bytes whatever order the controls were filled in.
 * The reader is fail-closed — an unknown line, a duplicate field, or a later envelope version is
 * unreadable rather than guessed, because this text is a permanent record of what somebody wrote.
 */
class StructuredResponseTest {

    private fun answer(key: String, type: String = "text", value: String) =
        FieldAnswer(fieldKey = key, type = type, value = value)

    private val meal = ReviewResponse.StructuredReview(
        completed = false,
        fields = listOf(
            answer("what", value = "Soup"),
            answer("ate", type = "choice.single", value = "yes")
        )
    )

    @Test
    fun `the encoded form is canonical and ordered by field key`() {
        assertEquals(
            listOf(
                "structured=1",
                "completed=false",
                """answers=[{"key":"ate","type":"choice.single","value":"yes"},""" +
                    """{"key":"what","type":"text","value":"Soup"}]"""
            ).joinToString("\n"),
            meal.encode()
        )
    }

    @Test
    fun `the same answers in another order encode the same`() {
        val reordered = meal.copy(fields = meal.fields.reversed())
        assertEquals(meal.encode(), reordered.encode())
    }

    @Test
    fun `a structured response round-trips through parse`() {
        assertEquals(meal.copy(fields = meal.fields.sortedBy { it.fieldKey }), ReviewResponse.parse(meal.encode()))
    }

    @Test
    fun `free text may hold newlines tabs and quotes`() {
        val tricky = ReviewResponse.StructuredReview(
            completed = true,
            fields = listOf(answer("note", value = "line one\nline two\t\"quoted\" \\ done"))
        )
        assertEquals(tricky, ReviewResponse.parse(tricky.encode()))
    }

    @Test
    fun `an answer with no type is refused`() {
        val text = meal.encode().replace("\"type\":\"text\"", "\"type\":\"\"")
        assertNull(ReviewResponse.parse(text))
    }

    @Test
    fun `a duplicate field key makes the response unreadable`() {
        val text = "structured=1\ncompleted=false\n" +
            "answers=[{\"key\":\"a\",\"type\":\"text\",\"value\":\"1\"}," +
            "{\"key\":\"a\",\"type\":\"text\",\"value\":\"2\"}]"
        assertNull(ReviewResponse.parse(text))
    }

    @Test
    fun `an unknown line is refused`() {
        assertNull(ReviewResponse.parse(meal.encode() + "\nnote=anything"))
    }

    @Test
    fun `a version this build does not know is refused`() {
        assertNull(ReviewResponse.parse(meal.encode().replaceFirst("structured=1", "structured=2")))
    }

    @Test
    fun `a missing completed line is refused rather than defaulted`() {
        val text = meal.encode().split('\n').filterNot { it.startsWith("completed=") }.joinToString("\n")
        assertNull(ReviewResponse.parse(text))
    }

    @Test
    fun `no answers line reads as a structured response with no fields`() {
        assertEquals(
            ReviewResponse.StructuredReview(completed = false, fields = emptyList()),
            ReviewResponse.parse("structured=1\ncompleted=false")
        )
    }

    @Test
    fun `the structured grammar is not confused with the legacy one`() {
        assertEquals(1, STRUCTURED_RESPONSE_VERSION)
        assertNull(ReviewResponse.parse("structured=1;completed=false"))
    }
}
