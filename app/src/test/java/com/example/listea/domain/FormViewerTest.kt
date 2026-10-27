package com.example.listea.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `form`/`1` body reader.
 *
 * A form is one Prompt and therefore one TaskPart; its fields are internal answers. The reader is
 * fail-closed like every other Viewer: an unknown key, a field type this build does not draw, a
 * condition pointing forward, or two condition spellings at once make the whole body unreadable
 * rather than partly understood.
 */
class FormViewerTest {

    private val body = """
        {
          "title": "Meal",
          "fields": [
            {"key": "ate", "type": "choice.single", "label": "Did you eat?",
             "options": [{"id": "yes", "label": "Yes"}, {"id": "no", "label": "No"}]},
            {"key": "what", "type": "text", "label": "What?", "required": false,
             "when": {"field": "ate", "equals": "yes"}}
          ]
        }
    """.trimIndent()

    @Test
    fun `the meal form parses in declaration order`() {
        val form = parseFormPrompt(body)!!
        assertEquals("Meal", form.stem)
        assertEquals(listOf("ate", "what"), form.fields.map { it.key })
        assertEquals(FormFieldType.ChoiceSingle, form.fields[0].type)
        assertTrue(form.fields[0].required)
        assertEquals(FormFieldType.Text, form.fields[1].type)
        assertFalse(form.fields[1].required)
        assertEquals(FormCondition("ate", listOf("yes")), form.fields[1].condition)
        assertTrue(form.options.isEmpty())
    }

    @Test
    fun `a condition may use in with several values`() {
        val text = body.replace(
            """{"field": "ate", "equals": "yes"}""",
            """{"field": "ate", "in": ["yes", "maybe"]}"""
        )
        val form = parseFormPrompt(text)!!
        assertEquals(listOf("yes", "maybe"), form.fields[1].condition!!.inValues)
    }

    @Test
    fun `a condition pointing forward is refused`() {
        val text = """
            {"title": "T", "fields": [
              {"key": "a", "type": "text", "label": "A", "when": {"field": "b", "equals": "x"}},
              {"key": "b", "type": "text", "label": "B"}
            ]}
        """.trimIndent()
        assertNull(parseFormPrompt(text))
    }

    @Test
    fun `both condition spellings at once is refused`() {
        val text = body.replace(
            """{"field": "ate", "equals": "yes"}""",
            """{"field": "ate", "equals": "yes", "in": ["yes"]}"""
        )
        assertNull(parseFormPrompt(text))
    }

    @Test
    fun `an unknown body or field key is refused`() {
        assertNull(parseFormPrompt("""{"title":"T","fields":[{"key":"a","type":"text","label":"A"}],"extra":1}"""))
        assertNull(parseFormPrompt("""{"title":"T","fields":[{"key":"a","type":"text","label":"A","help":"x"}]}"""))
    }

    @Test
    fun `an unknown field type is refused`() {
        assertNull(
            parseFormPrompt(
                """{"title":"T","fields":[{"key":"a","type":"choice.multiple","label":"A","options":[]}]}"""
            )
        )
    }

    @Test
    fun `a choice field without options is refused and a text field with options is refused`() {
        assertNull(
            parseFormPrompt(
                """{"title":"T","fields":[{"key":"a","type":"choice.single","label":"A"}]}"""
            )
        )
        assertNull(
            parseFormPrompt(
                """{"title":"T","fields":[{"key":"a","type":"text","label":"A","options":[]}]}"""
            )
        )
    }

    @Test
    fun `duplicate field keys are refused`() {
        val text = """
            {"title": "T", "fields": [
              {"key": "a", "type": "text", "label": "A"},
              {"key": "a", "type": "text", "label": "Again"}
            ]}
        """.trimIndent()
        assertNull(parseFormPrompt(text))
    }

    @Test
    fun `a body with no fields is refused`() {
        assertNull(parseFormPrompt("""{"title":"T","fields":[]}"""))
    }

    @Test
    fun `a single-choice contract cannot read a form body`() {
        assertNull(parseFormPrompt(body, ResponseContract.SINGLE))
    }

    @Test
    fun `the viewer registry draws form schema 1 as structured`() {
        val viewer = Viewers.Form
        assertEquals("form", viewer.kind)
        assertEquals(1, viewer.schema)
        assertEquals(setOf(ResponseContract.STRUCTURED), viewer.responses)
        assertTrue(viewer.intents.isEmpty())
    }
}
