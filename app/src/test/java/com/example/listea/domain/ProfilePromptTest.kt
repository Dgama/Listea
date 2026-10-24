package com.example.listea.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `profile`/`1` reader, which is the whole of what the first structured Viewer knows.
 *
 * The reader is fail-closed on purpose: a body it cannot fully understand is a body it will not
 * draw, because drawing a Prompt nobody wrote is worse than saying the Prompt cannot be shown. The
 * tests below are therefore mostly refusals. The one shape test that matters positively is the
 * field order — stored JSON is canonical and key-sorted, which would put age above name, and a
 * dating card that reads "Age 29, Bio …, Name Alice" is a card drawn for a hasher, not a person.
 */
class ProfilePromptTest {

    @Test
    fun `a schema 1 body is read with the fields in reading order`() {
        val prompt = parseProfilePrompt(
            """
            {
              "stem": "Would you like to meet Alice?",
              "profile": {"name": "Alice", "age": 29, "bio": "Likes long walks", "city": "Leeds"},
              "photo": "alice-photo",
              "options": [
                {"id": "like", "label": "Like"},
                {"id": "pass", "label": "Pass"}
              ]
            }
            """.trimIndent()
        )

        requireNotNull(prompt)
        assertEquals("Would you like to meet Alice?", prompt.stem)
        assertEquals(
            listOf("Name", "Age", "Bio", "City"),
            prompt.fields.map { it.label }
        )
        assertEquals("29", prompt.fields.single { it.label == "Age" }.value)
        assertEquals("alice-photo", prompt.photoResource)
        assertEquals(
            listOf(PromptOption("like", "Like"), PromptOption("pass", "Pass")),
            prompt.options
        )
    }

    @Test
    fun `a body without a photo is readable`() {
        val prompt = parseProfilePrompt(body(photo = null))

        requireNotNull(prompt)
        assertNull(prompt.photoResource)
    }

    @Test
    fun `only the single choice contract is this Viewer's`() {
        assertNull(parseProfilePrompt(body(), ResponseContract.MULTIPLE))
        assertNull(parseProfilePrompt(body(), ResponseContract.FREEFORM))
    }

    @Test
    fun `the reader refuses bodies it does not fully understand`() {
        val refusals = listOf(
            // Not JSON, not an object.
            "not json",
            "[]",
            // An unknown top-level field: a later schema is not guessed at.
            body().replaceFirst("{", """{"extra": 1,"""),
            // No stem, or a blank one.
            body().replaceFirst(""""stem":"Hi",""", ""),
            // A profile that is not an object, is empty, or holds a nested value.
            body().replaceFirst(""""profile":{"name":"Alice"}""", """"profile":[]"""),
            body().replaceFirst(""""profile":{"name":"Alice"}""", """"profile":{}"""),
            body().replaceFirst(""""profile":{"name":"Alice"}""", """"profile":{"name":["Alice"]}"""),
            // A field name that is not protocol-shaped.
            body().replaceFirst(""""name":"Alice"""", """"Name":"Alice""""),
            // An unsafe photo key.
            body().replaceFirst(""""photo":"p"""", """"photo":"../p""""),
            // No options, or an empty list.
            body().replaceFirst(""","options":[{"id":"a","label":"A"}]""", ""),
            body().replaceFirst("""[{"id":"a","label":"A"}]""", "[]"),
            // An option with an extra key, a missing label, a bad id, or a duplicate id.
            body().replaceFirst(
                """{"id":"a","label":"A"}""",
                """{"id":"a","label":"A","weight":1}"""
            ),
            body().replaceFirst("""{"id":"a","label":"A"}""", """{"id":"a"}"""),
            body().replaceFirst(""""id":"a"""", """"id":"A""""),
            body().replaceFirst("""[{"id":"a","label":"A"}]""", """[{"id":"a","label":"A"},{"id":"a","label":"B"}]""")
        )

        refusals.forEach { refusal ->
            assertNull("should have been refused: $refusal", parseProfilePrompt(refusal))
        }
    }

    @Test
    fun `the Viewer table answers compatibility by kind schema and response`() {
        assertEquals(Viewers.Profile, Viewers.forPayload("profile", 1))
        assertNull(Viewers.forPayload("profile", 2))
        assertNull(Viewers.forPayload("video", 1))
        assertNull(Viewers.forPayload(null, 1))
        assertTrue(Viewers.supports("profile", 1))
        assertFalse(Viewers.supports("profile", 2))

        assertEquals(
            Viewers.Profile,
            Viewers.forPrompt("profile", 1, ResponseContract.SINGLE)
        )
        assertNull(Viewers.forPrompt("profile", 1, ResponseContract.MULTIPLE))
    }

    private fun body(
        photo: String? = "p"
    ): String = buildString {
        append("""{"stem":"Hi","profile":{"name":"Alice"}""")
        photo?.let { append(""","photo":"$it"""") }
        append(""","options":[{"id":"a","label":"A"}]}""")
    }
}
