package com.example.listea.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `vocabulary`/`1` reader.
 *
 * Two promises are pinned here. The first is the shared fail-closed rule: a body this Viewer cannot
 * fully understand is a Prompt it will not draw. The second is specific to a drill — the declared
 * option order is the card's contract. The fixture uses `c, b, a`, which is neither alphabetical
 * nor the order the correct answer would suggest, so a reader that sorted or shuffled is caught.
 */
class VocabularyPromptTest {

    @Test
    fun `a schema 1 body is read and keeps the declared option order`() {
        val prompt = parseVocabularyPrompt(BODY)

        requireNotNull(prompt)
        assertEquals("Choose the meaning of the word.", prompt.stem)
        assertEquals("serendipity", prompt.word)
        assertEquals("/ˌserənˈdɪpɪti/", prompt.phonetic)
        assertEquals("a happy accident", prompt.definition)
        assertEquals("a fortunate stroke of serendipity", prompt.example)
        assertEquals("word-audio", prompt.audioResource)
        assertEquals("b", prompt.answerOptionId)
        assertEquals(
            listOf(
                PromptOption("c", "a formal apology"),
                PromptOption("b", "a happy accident"),
                PromptOption("a", "a long journey")
            ),
            prompt.options
        )
    }

    @Test
    fun `the optional lines and pronunciation may be absent`() {
        val prompt = parseVocabularyPrompt(
            """
            {
              "stem": "Choose.",
              "word": "serendipity",
              "options": [
                {"id": "a", "label": "A"},
                {"id": "b", "label": "B"}
              ]
            }
            """.trimIndent()
        )

        requireNotNull(prompt)
        assertNull(prompt.phonetic)
        assertNull(prompt.definition)
        assertNull(prompt.example)
        assertNull(prompt.audioResource)
        assertNull(prompt.answerOptionId)
    }

    @Test
    fun `only the single choice contract is this Viewer's`() {
        assertNull(parseVocabularyPrompt(BODY, ResponseContract.MULTIPLE))
        assertNull(parseVocabularyPrompt(BODY, ResponseContract.FREEFORM))
    }

    @Test
    fun `the reader refuses bodies it does not fully understand`() {
        val optionArray = Regex(""""options":\s*\[.*?]""", RegexOption.DOT_MATCHES_ALL)
        val refusals = listOf(
            // Not JSON, not an object.
            "not json",
            "[]",
            // An unknown field: a later schema is not guessed at.
            BODY.replaceFirst("{", """{"extra": 1, """),
            // No stem, blank stem, no word, blank word.
            BODY.replaceFirst(""""stem": "Choose the meaning of the word.",""", ""),
            BODY.replaceFirst(""""word": "serendipity"""", """"word": """"),
            BODY.replaceFirst(""""word": "serendipity",""", ""),
            // An optional line that is not a string.
            BODY.replaceFirst(""""phonetic": "/ˌserənˈdɪpɪti/"""", """"phonetic": 1"""),
            // A pronunciation key that is not protocol-shaped.
            BODY.replaceFirst(""""audio": "word-audio"""", """"audio": "../word.mp3""""),
            // An answer id that is not declared by the options.
            BODY.replaceFirst(""""answer": "b"""", """"answer": "missing""""),
            // No options, empty options.
            BODY.replaceFirst(Regex(""",\s*"options":\s*\[.*?]""", RegexOption.DOT_MATCHES_ALL), ""),
            BODY.replace(optionArray, """"options": []"""),
            // An option with an extra key, a bad id, a missing label, a duplicate id.
            BODY.replaceFirst(
                """{"id": "c", "label": "a formal apology"}""",
                """{"id": "c", "label": "a formal apology", "weight": 1}"""
            ),
            BODY.replaceFirst(""""id": "c"""", """"id": "C""""),
            BODY.replaceFirst("""{"id": "c", "label": "a formal apology"}""", """{"id": "c"}"""),
            BODY.replaceFirst(""""id": "a"""", """"id": "b"""")
        )

        refusals.forEach { refusal ->
            assertNull("should have been refused: $refusal", parseVocabularyPrompt(refusal))
        }
    }

    @Test
    fun `the Viewer table answers vocabulary by kind schema and response`() {
        assertEquals(Viewers.Vocabulary, Viewers.forPayload("vocabulary", 1))
        assertNull(Viewers.forPayload("vocabulary", 2))
        assertNull(Viewers.forPayload("video", 1))
        assertTrue(Viewers.supports("vocabulary", 1))

        assertEquals(
            Viewers.Vocabulary,
            Viewers.forPrompt("vocabulary", 1, ResponseContract.SINGLE)
        )
        assertNull(Viewers.forPrompt("vocabulary", 1, ResponseContract.MULTIPLE))
    }

    @Test
    fun `the capability decodes its own body`() {
        val content = Viewers.Vocabulary.parse(BODY)

        assertNotNull(content)
        assertTrue(content is VocabularyPrompt)
        assertEquals(listOf("c", "b", "a"), content!!.options.map { it.id })
    }

    private companion object {
        const val BODY = """
            {
              "stem": "Choose the meaning of the word.",
              "word": "serendipity",
              "phonetic": "/ˌserənˈdɪpɪti/",
              "definition": "a happy accident",
              "example": "a fortunate stroke of serendipity",
              "audio": "word-audio",
              "answer": "b",
              "options": [
                {"id": "c", "label": "a formal apology"},
                {"id": "b", "label": "a happy accident"},
                {"id": "a", "label": "a long journey"}
              ]
            }
        """
    }
}
