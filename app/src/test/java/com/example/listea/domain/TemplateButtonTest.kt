package com.example.listea.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The form a template version's buttons are stored in.
 *
 * Worth pinning character for character, for the reason the dissolved history's encoding is: it is
 * written to a column now and read by a release that will not have this code to consult. A golden
 * expectation makes a change to it something somebody wrote down deliberately rather than something
 * that happened.
 *
 * The other half is what the decoder does with what it cannot read. It is tolerant in the same shape
 * as the decoders around it — a line costs itself and never the payload — with one exception worth
 * stating: an unknown *version* is `null` rather than a best-effort read, because a payload whose
 * shape has moved is not a payload with a few bad lines in it.
 */
class TemplateButtonTest {

    private val c1 = TemplateButton("custom1", "C1", ButtonType.TAG, "cust1")
    private val star = TemplateButton("favorite", "★", ButtonType.BOOLEAN, "favorite")
    private val note = TemplateButton("note1", "Comment", ButtonType.COMMENT, "")

    private fun decode(text: String) = decodeTemplateButtons(text, TEMPLATE_BUTTONS_VERSION)

    @Test
    fun `a bar is exactly this text`() {
        assertEquals(
            "custom1\tC1\ttag\tcust1\nfavorite\t★\tboolean\tfavorite",
            encodeTemplateButtons(listOf(c1, star))
        )
    }

    @Test
    fun `the bar keeps the order it was written in`() {
        // Nothing is sorted here, unlike the asset-control configuration this replaces. A map has no
        // order of its own to preserve and a control row does — a bar whose chips shuffled between
        // two reads would be a bar that looked like it had changed.
        assertEquals(
            "favorite\t★\tboolean\tfavorite\ncustom1\tC1\ttag\tcust1",
            encodeTemplateButtons(listOf(star, c1))
        )
    }

    @Test
    fun `a bar through the encoder and back is the same bar`() {
        val buttons = listOf(c1, star)
        assertEquals(buttons, decode(encodeTemplateButtons(buttons)))
    }

    @Test
    fun `a comment is written in version 2 and reads back`() {
        val text = encodeTemplateButtons(listOf(c1, note))
        assertEquals("custom1\tC1\ttag\tcust1\nnote1\tComment\tcomment\t", text)
        assertEquals(
            listOf(c1, note),
            decodeTemplateButtons(text, TEMPLATE_BUTTONS_COMMENT_VERSION)
        )
        assertEquals(
            TEMPLATE_BUTTONS_COMMENT_VERSION,
            buttonsVersionFor(listOf(c1, note))
        )
    }

    @Test
    fun `a comment-free bar keeps version 1`() {
        // It must stay readable by a build that has never heard of version 2, so the writer does
        // not move the whole format forward just because it can.
        assertEquals(TEMPLATE_BUTTONS_VERSION, buttonsVersionFor(listOf(c1, star)))
        assertEquals(TEMPLATE_BUTTONS_VERSION, buttonsVersionFor(emptyList()))
    }

    @Test
    fun `a comment line inside version 1 is dropped`() {
        // Version 1 could not have written one; a row that holds one was hand-edited or corrupted.
        // Offering free text from a payload that never promised it is the guess the version gate
        // exists to refuse, so the line goes and its neighbours stay.
        assertEquals(
            listOf(c1, star),
            decode("custom1\tC1\ttag\tcust1\nnote1\tComment\tcomment\t\nfavorite\t★\tboolean\tfavorite")
        )
    }

    @Test
    fun `a draft that became a comment is read back at version 2`() {
        // The editor's whole sequence: a version-1 row is opened, one of its buttons is changed to
        // Comment, and the draft is re-encoded. Reading that draft back at the stored version drops
        // the comment line — it is version-2 vocabulary — so the button vanishes from the page and
        // the next Update writes the template without it. The draft's version, not the row's, is
        // what has to read it.
        val stored = encodeTemplateButtons(listOf(c1, star))
        val draft = encodeTemplateButtons(listOf(c1, note))

        assertEquals(
            "the stored row's version cannot read the row the edit added",
            listOf(c1),
            decodeTemplateButtons(draft, TEMPLATE_BUTTONS_VERSION)
        )
        assertEquals(
            TEMPLATE_BUTTONS_COMMENT_VERSION,
            templateDraftVersion(stored, TEMPLATE_BUTTONS_VERSION, draft)
        )
        assertEquals(
            listOf(c1, note),
            decodeTemplateButtons(
                draft,
                templateDraftVersion(stored, TEMPLATE_BUTTONS_VERSION, draft)
            )
        )
    }

    @Test
    fun `an untouched draft stays readable at its stored version`() {
        // The other half of the rule: a version-1 row holding a corrupt comment line is not given
        // version-2 meaning merely because the page could now write one. It is still the stored
        // row, and the line stays dropped until the user actually edits.
        val stored = "custom1\tC1\ttag\tcust1\nnote1\tComment\tcomment\t"

        assertEquals(
            TEMPLATE_BUTTONS_VERSION,
            templateDraftVersion(stored, TEMPLATE_BUTTONS_VERSION, stored)
        )
        assertEquals(
            TEMPLATE_BUTTONS_COMMENT_VERSION,
            templateDraftVersion(stored, TEMPLATE_BUTTONS_VERSION, "$stored\nfavorite\t★\tboolean\tfavorite")
        )
    }

    @Test
    fun `a version this build does not know is refused, not guessed at`() {
        assertNull(decodeTemplateButtons("custom1\tC1\ttag\tcust1", TEMPLATE_BUTTONS_COMMENT_VERSION + 1))
    }

    @Test
    fun `a type this build does not know costs its own line`() {
        // `option` is a button the model is shaped for and this build does not ship, so the chip it
        // would draw cannot be drawn and it goes. The two around it stay, which is the whole
        // difference between a tolerant decoder and a brittle one.
        val text = "custom1\tC1\ttag\tcust1\ns1\tSize\toption\tsize\nfavorite\t★\tboolean\tfavorite"
        assertEquals(listOf(c1, star), decode(text))
    }

    @Test
    fun `an id carrying a comma is not an id`() {
        // A comma is what the *record* joins ids with, so an id holding one could never be read back
        // out of an Asset's marks. Refusing it here is what keeps the two encodings agreeing about
        // what an id is.
        assertEquals(listOf(star), decode("a,b\tNope\ttag\tn\nfavorite\t★\tboolean\tfavorite"))
    }

    @Test
    fun `no buttons is an empty bar, not an unreadable one`() {
        // A template that offers nothing is a fact the payload recorded, and it must not be
        // conflated with a payload this build could not read.
        assertEquals(emptyList<TemplateButton>(), decode(""))
        assertNull(decodeTemplateButtons("", TEMPLATE_BUTTONS_COMMENT_VERSION + 1))
    }
}
