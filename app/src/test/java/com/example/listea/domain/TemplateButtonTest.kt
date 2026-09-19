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
    fun `a version this build does not know is refused, not guessed at`() {
        assertNull(decodeTemplateButtons("custom1\tC1\ttag\tcust1", TEMPLATE_BUTTONS_VERSION + 1))
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
        assertNull(decodeTemplateButtons("", TEMPLATE_BUTTONS_VERSION + 1))
    }
}
