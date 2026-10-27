package com.example.listea.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one decoder every Task surface uses.
 *
 * A kind/schema this build declares is handed to that Viewer's own parser; anything else is null,
 * even when the body happens to look like a card. The envelope decides which Viewer may read the
 * body, not the body's shape.
 */
class PromptContentTest {

    @Test
    fun `a profile envelope decodes to a profile card`() {
        val content = decodePrompt("profile", 1, PROFILE)

        assertTrue(content is ProfilePrompt)
        assertEquals("Meet Alice?", content!!.stem)
        assertEquals(listOf("like", "pass"), content.options.map { it.id })
    }

    @Test
    fun `a vocabulary envelope decodes to a vocabulary card in declared order`() {
        val content = decodePrompt("vocabulary", 1, VOCABULARY)

        assertTrue(content is VocabularyPrompt)
        assertEquals("serendipity", (content as VocabularyPrompt).word)
        assertEquals(listOf("c", "b", "a"), content.options.map { it.id })
    }

    @Test
    fun `an unknown kind, schema, or envelope is unreadable`() {
        assertNull(decodePrompt("videogame", 1, VOCABULARY))
        assertNull(decodePrompt("vocabulary", 2, VOCABULARY))
        assertNull(decodePrompt(null, 1, VOCABULARY))
        assertNull(decodePrompt("vocabulary", 1, null))
        assertNull(decodePrompt("vocabulary", 1, "not json"))
    }

    @Test
    fun `a declared kind with a body its Viewer refuses is unreadable`() {
        assertNull(decodePrompt("vocabulary", 1, PROFILE))
        assertNull(decodePrompt("profile", 1, VOCABULARY))
    }

    private companion object {
        const val PROFILE =
            """{"options":[{"id":"like","label":"Like"},{"id":"pass","label":"Pass"}],""" +
                """"profile":{"name":"Alice"},"stem":"Meet Alice?"}"""

        const val VOCABULARY =
            """{"options":[{"id":"c","label":"C"},{"id":"b","label":"B"},""" +
                """{"id":"a","label":"A"}],"stem":"Choose.","word":"serendipity"}"""
    }
}
