package com.example.listea.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which fields a person can see, and which answers a submission carries.
 *
 * A branch is a property of the answers so far, not of the body's shape: choosing "No" hides the
 * follow-up, and changing back must not leave the old answer in the submission. Hidden fields are
 * neither required nor sent, which is what keeps a conditional questionnaire one part instead of
 * one part per path.
 */
class ConditionalVisibilityTest {

    private val form = parseFormPrompt(
        """
        {
          "title": "Meal",
          "fields": [
            {"key": "ate", "type": "choice.single", "label": "Did you eat?",
             "options": [{"id": "yes", "label": "Yes"}, {"id": "no", "label": "No"}]},
            {"key": "what", "type": "text", "label": "What?",
             "when": {"field": "ate", "equals": "yes"}},
            {"key": "why", "type": "text", "label": "Why not?", "required": false,
             "when": {"field": "ate", "in": ["no", "maybe"]}}
          ]
        }
        """.trimIndent()
    )!!

    @Test
    fun `an unanswered gate hides its dependants`() {
        assertEquals(listOf("ate"), form.visibleFields(emptyMap()).map { it.key })
    }

    @Test
    fun `choosing yes shows the follow-up and hides the other branch`() {
        val answers = mapOf("ate" to "yes")
        assertEquals(listOf("ate", "what"), form.visibleFields(answers).map { it.key })
        assertTrue(form.isComplete(answers + ("what" to "Soup")))
    }

    @Test
    fun `choosing no shows the other branch, whose optional field does not block`() {
        val answers = mapOf("ate" to "no")
        assertEquals(listOf("ate", "why"), form.visibleFields(answers).map { it.key })
        assertTrue(form.isComplete(answers))
    }

    @Test
    fun `a visible required field with no answer is incomplete`() {
        assertFalse(form.isComplete(mapOf("ate" to "yes")))
        assertFalse(form.isComplete(mapOf("ate" to "yes", "what" to "   ")))
    }

    @Test
    fun `a hidden field's answer is not submitted`() {
        val answers = mapOf("ate" to "no", "what" to "Soup", "why" to "Not hungry")
        assertEquals(
            listOf(FieldAnswer("ate", "choice.single", "no"), FieldAnswer("why", "text", "Not hungry")),
            form.answeredFields(answers)
        )
    }

    @Test
    fun `changing the branch drops the answer from the other path`() {
        val onYes = form.answeredFields(mapOf("ate" to "yes", "what" to "Soup"))
        val thenNo = form.answeredFields(mapOf("ate" to "no", "what" to "Soup"))
        assertEquals(listOf("ate", "what"), onYes.map { it.fieldKey })
        assertEquals(listOf("ate"), thenNo.map { it.fieldKey })
    }

    @Test
    fun `an option id outside the declaration is still a text answer`() {
        // Visibility is a rule about the answer value, not a validation of it: the Viewer's
        // controls only produce declared ids, and a stored value is carried as it was recorded.
        assertTrue(form.isComplete(mapOf("ate" to "maybe", "why" to "Because")))
    }
}
