package com.example.listea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quote a selection hands to the rest of the app: its text, the words either side of it, and
 * the renderer hint an anchor will carry.
 *
 * The context is what tells one repeated "the" from another, so the newlines between lines are
 * load-bearing rather than cosmetic: a suffix that reads "betatwo" where the page reads
 * "alphaone\nbetatwo" describes a different page position, and the anchor built from it would be
 * looked for one character too early. That is the failure this file pins.
 */
class PdfQuoteTest {

    @Test
    fun `a quote on the first line carries the newline before the next line`() {
        val order = order()

        val quote = quoteFor(order, pageIndex = 0, selection = 0..7)

        assertEquals("alphaone", quote.quote)
        assertEquals("", quote.prefix)
        assertEquals("\nbetatwo", quote.suffix)
        assertEquals(0, quote.startChar)
        assertEquals(7, quote.endChar)
    }

    @Test
    fun `a quote on the last line carries the newline after the first`() {
        val order = order()

        // "betatwo" is seven characters, so the second line occupies reading positions 8..14.
        val quote = quoteFor(order, pageIndex = 0, selection = 8..14)

        assertEquals("betatwo", quote.quote)
        // The newline belongs to the text before the quote: the quote starts the second line.
        assertEquals("alphaone\n", quote.prefix)
        assertEquals("", quote.suffix)
        assertEquals(8, quote.startChar)
        assertEquals(14, quote.endChar)
    }

    @Test
    fun `a quote inside one line takes its context from that line alone`() {
        val order = order()

        val quote = quoteFor(order, pageIndex = 0, selection = 2..5)

        assertEquals("phao", quote.quote)
        assertEquals("al", quote.prefix)
        assertEquals("ne\nbetatwo", quote.suffix)
    }

    @Test
    fun `a quote across two lines keeps the newline in its text`() {
        val order = order()

        val quote = quoteFor(order, pageIndex = 0, selection = 5..10)

        assertEquals("one\nbet", quote.quote)
        assertEquals("alpha", quote.prefix)
        assertEquals("atwo", quote.suffix)
    }

    @Test
    fun `context is capped so an anchor stays small`() {
        val long = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWX"
        val chars = line(long, top = 500f, firstId = 0)
        val order = PdfReadingOrder.of(page(chars))

        val quote = quoteFor(order, pageIndex = 0, selection = 30..32)

        assertEquals(AnchorContextChars, quote.prefix.length)
        assertEquals(AnchorContextChars, quote.suffix.length)
    }

    @Test
    fun `a quote on a gap alone cannot carry a mark`() {
        // `a press on whitespace selects only that character`, so a long press landing between two
        // words hands the menu a one-space quote. The resolver normalises a quote down to its
        // words, and a space normalises to nothing — a mark made here would be reported not found
        // on the document it was made in, forever. The chips have to be withheld at creation.
        val order = PdfReadingOrder.of(page(line("alpha beta", top = 500f, firstId = 0)))

        val quote = quoteFor(order, pageIndex = 0, selection = 5..5)

        assertEquals(" ", quote.quote)
        assertFalse(quote.canCarryMark())
    }

    @Test
    fun `a quote holding any word can carry a mark`() {
        // The gate is about the absence of text, not about whitespace inside a quote: a phrase
        // with gaps in it is ordinary, and a word at either end is enough to search for.
        val order = PdfReadingOrder.of(page(line("alpha beta", top = 500f, firstId = 0)))

        assertTrue(quoteFor(order, pageIndex = 0, selection = 0..4).canCarryMark())
        assertTrue(quoteFor(order, pageIndex = 0, selection = 4..6).canCarryMark())
    }

    // --- fixture ---------------------------------------------------------------------------------

    private fun order(): PdfReadingOrder = PdfReadingOrder.of(
        page(
            line("alphaone", top = 500f, firstId = 0) +
                line("betatwo", top = 480f, firstId = 8)
        )
    )

    private fun line(text: String, top: Float, firstId: Int, width: Float = 6f): List<PdfChar> =
        text.mapIndexed { index, character ->
            PdfChar(
                id = firstId + index,
                codepoint = character.code,
                left = 40f + index * width,
                right = 40f + (index + 1) * width,
                bottom = top - 10f,
                top = top
            )
        }

    private fun page(chars: List<PdfChar>, width: Float = 400f, height: Float = 600f) =
        PdfPageText(pageIndex = 0, widthPoints = width, heightPoints = height, chars = chars)
}
