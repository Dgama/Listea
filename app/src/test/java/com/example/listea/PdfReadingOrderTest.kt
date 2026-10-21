package com.example.listea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reading order a selection is dragged across, pinned on synthetic pages.
 *
 * Every fixture here is built by hand from boxes, because the thing under test is a heuristic:
 * a real PDF proves that *a* document reads correctly, and a hand-built page proves *why*. The two
 * failures this guards are the ones a viewer cannot recover from — a two-column page that selects
 * across the gutter, and an RTL line that copies out backwards — plus word snapping, without which
 * a quote starts mid-word.
 *
 * Coordinates are PDF points with the origin at the bottom-left, exactly as the renderer reports
 * them, so a line lower on the page has a smaller `top`.
 */
class PdfReadingOrderTest {

    // --- prose -----------------------------------------------------------------------------------

    @Test
    fun `two prose lines read top to bottom and are joined by a newline`() {
        val chars = line("Hello world", left = 40f, top = 500f, firstId = 0) +
            line("Second line", left = 40f, top = 480f, firstId = 11)

        val order = PdfReadingOrder.of(page(chars))

        assertEquals("Hello world\nSecond line", order.text(0, order.size - 1))
        assertEquals(2, order.lines.size)
    }

    @Test
    fun `characters emitted out of order are read left to right within a line`() {
        // PDFium's stream order is arbitrary; geometry is what decides. The fixture reverses the
        // ids so a reader that trusted them would produce "dlrow".
        val chars = line("world", left = 40f, top = 500f, firstId = 4)
            .reversed()
            .toList()

        val order = PdfReadingOrder.of(page(chars))

        assertEquals("world", order.text(0, order.size - 1))
    }

    // --- columns ---------------------------------------------------------------------------------

    @Test
    fun `a two-column page is read down the left column before the right`() {
        val chars = listOf(
            line("Left column one", left = 30f, top = 500f, firstId = 0),
            line("Right column one", left = 240f, top = 500f, firstId = 16),
            line("Left column two", left = 30f, top = 480f, firstId = 32),
            line("Right column two", left = 240f, top = 480f, firstId = 48),
            line("Left column three", left = 30f, top = 460f, firstId = 64),
            line("Right column three", left = 240f, top = 460f, firstId = 80)
        ).flatten()

        val order = PdfReadingOrder.of(page(chars, width = 400f))
        val text = order.text(0, order.size - 1)

        assertEquals(
            "Left column one\nLeft column two\nLeft column three\n" +
                "Right column one\nRight column two\nRight column three",
            text
        )
    }

    @Test
    fun `a table is read row by row when its gaps do not form one gutter`() {
        // Three cells per row, so the candidate midpoints cluster around two different x values
        // and no single gutter is found. The fallback — band by band — is what a table wants: a
        // cell is read before the one beside it, not after the whole first column.
        val chars = (0 until 3).flatMap { row ->
            val top = 500f - row * 20f
            listOf(
                line("AAAA", left = 30f, top = top, firstId = row * 100),
                line("BBBB", left = 150f, top = top, firstId = row * 100 + 10),
                line("CCCC", left = 270f, top = top, firstId = row * 100 + 20)
            )
        }.flatten()

        val order = PdfReadingOrder.of(page(chars, width = 400f))

        // One line per cell — a cell is a line to the reading order, and text() puts a newline
        // between lines — but the cells of row 1 come before the cells of row 2.
        assertEquals(
            "AAAA\nBBBB\nCCCC\nAAAA\nBBBB\nCCCC\nAAAA\nBBBB\nCCCC",
            order.text(0, order.size - 1)
        )
    }

    @Test
    fun `characters that overlap vertically form one line, ordered by x`() {
        // A superscript or a font with uneven metrics: the boxes are not on one baseline, and the
        // banding rule (half the smaller height in overlap) is what keeps them in one line.
        val chars = listOf(
            char(id = 0, codepoint = 'A', left = 40f, top = 500f),
            char(id = 1, codepoint = 'B', left = 46f, top = 504f),
            char(id = 2, codepoint = 'C', left = 52f, top = 499f)
        )

        val order = PdfReadingOrder.of(page(chars))

        assertEquals(1, order.lines.size)
        assertEquals("ABC", order.text(0, order.size - 1))
    }

    // --- right to left ---------------------------------------------------------------------------

    @Test
    fun `a Hebrew line is emitted in logical order while its boxes run right to left`() {
        val logical = "שלום"
        // Visual order is the reverse: logical first character is the rightmost box.
        val chars = line(logical.reversed(), left = 40f, top = 500f, firstId = 0)

        val order = PdfReadingOrder.of(page(chars))

        assertEquals(logical, order.text(0, order.size - 1))
        // And the boxes really do run the other way, so this is not a fixture that agrees by luck.
        val first = order.chars.first()
        val last = order.chars.last()
        assertTrue("logical first should be the rightmost box", first.left > last.left)
    }

    @Test
    fun `a mostly-Latin line keeps its left-to-right reading`() {
        // Six Latin characters against four Hebrew ones, so the majority rule keeps the line as
        // written. The rule is a majority and not "any RTL character", because one quoted Hebrew
        // name in an English sentence must not reverse the sentence.
        val chars = line("Hello שלום", left = 40f, top = 500f, firstId = 0)

        val order = PdfReadingOrder.of(page(chars))

        assertEquals("Hello שלום", order.text(0, order.size - 1))
    }

    // --- word snapping ---------------------------------------------------------------------------

    @Test
    fun `a press inside a word snaps to the whole word`() {
        val order = PdfReadingOrder.of(page(line("Hello world again", 40f, 500f, firstId = 0)))

        assertEquals(6..10, order.wordRangeAt(8))
        assertEquals(0..4, order.wordRangeAt(0))
        assertEquals(12..16, order.wordRangeAt(16))
    }

    @Test
    fun `a press on whitespace selects only that character`() {
        val order = PdfReadingOrder.of(page(line("Hello world", 40f, 500f, firstId = 0)))

        assertEquals(5..5, order.wordRangeAt(5))
    }

    @Test
    fun `snapping never leaves its own line`() {
        val chars = line("first", 40f, 500f, firstId = 0) + line("second", 40f, 480f, firstId = 10)
        val order = PdfReadingOrder.of(page(chars))

        val firstLine = order.wordRangeAt(2)
        val secondLine = order.wordRangeAt(order.size - 1)

        assertEquals(0..4, firstLine)
        assertEquals(5..10, secondLine)
        assertNotEquals(firstLine.last, secondLine.first)
    }

    // --- text ------------------------------------------------------------------------------------

    @Test
    fun `a range that stops mid-line still reports the line it ended on`() {
        val chars = line("first", 40f, 500f, firstId = 0) + line("second", 40f, 480f, firstId = 10)
        val order = PdfReadingOrder.of(page(chars))

        assertEquals("st\nsec", order.text(3, 7))
    }

    @Test
    fun `an empty page has no order and no text`() {
        val order = PdfReadingOrder.of(PdfPageText.Empty)

        assertEquals(0, order.size)
        assertEquals("", order.text(0, 10))
        assertTrue(order.lines.isEmpty())
    }

    @Test
    fun `a boxless space is kept, so two words never run together`() {
        // Real extractors do this: words written as separate runs, with the space between them
        // reported at a point rather than a box. The glyph is not what matters here — the word
        // boundary is — and dropping it quotes "alphabeta" for a page that says "alpha beta".
        val chars = line("alpha", 40f, 500f, firstId = 0) +
            PdfChar(id = 5, codepoint = ' '.code, left = 70f, bottom = 490f, right = 70f, top = 500f) +
            line("beta", 70f, 500f, firstId = 6)

        val order = PdfReadingOrder.of(page(chars))

        assertEquals("alpha beta", order.text(0, order.size - 1))
        assertEquals(10, order.size)
    }

    @Test
    fun `a point-sized space joins its line instead of breaking it`() {
        // The extractor's synthetic space has no height at all, so the overlap rule cannot place
        // it. Treated as a band of its own it becomes a line break, and a page's one paragraph
        // quotes as a column of single words.
        val chars = line("alpha", 40f, 500f, firstId = 0) +
            PdfChar(
                id = 5,
                codepoint = ' '.code,
                left = 70f,
                bottom = 490f,
                right = 70f,
                top = 490f
            ) +
            line("beta", 70f, 500f, firstId = 6)

        val order = PdfReadingOrder.of(page(chars))

        assertEquals(1, order.lines.size)
        assertEquals("alpha beta", order.text(0, order.size - 1))
    }

    @Test
    fun `the line break PDFium reports is structure, not a character to quote`() {
        // PDFium's layout emits CR and LF as point-sized characters at the end of every line.
        // Quoted literally they are two extra blank lines between every pair of lines.
        val chars = line("alpha", 40f, 500f, firstId = 0) +
            PdfChar(id = 5, codepoint = '\r'.code, left = 70f, bottom = 490f, right = 70f, top = 490f) +
            PdfChar(id = 6, codepoint = '\n'.code, left = 70f, bottom = 490f, right = 70f, top = 490f) +
            line("beta", 40f, 480f, firstId = 7)

        val order = PdfReadingOrder.of(page(chars))

        assertEquals("alpha\nbeta", order.text(0, order.size - 1))
    }

    @Test
    fun `a boxless glyph that is not a space is still dropped`() {
        val chars = line("alpha", 40f, 500f, firstId = 0) +
            PdfChar(id = 5, codepoint = 'X'.code, left = 80f, bottom = 490f, right = 80f, top = 500f)

        val order = PdfReadingOrder.of(page(chars))

        assertEquals("alpha", order.text(0, order.size - 1))
    }

    @Test
    fun `a zero-height character is kept, because some producers report glyphs that way`() {
        // A Hebrew producer can report a glyph's box as essentially flat. Dropping it would lose
        // selectable text; keeping it means the page has one more line than a human would see, and
        // the line-widening pass is what gives that line a target. What is asserted is that the
        // character survives to be selectable at all.
        val flat = PdfChar(0, 'A'.code, 40f, 500f, 46f, 500f)
        val normal = char(id = 1, codepoint = 'B', left = 46f, top = 500f)

        val order = PdfReadingOrder.of(page(listOf(flat, normal)))

        assertEquals(2, order.size)
        assertEquals(listOf('A'.code, 'B'.code), order.chars.map { it.codepoint })
        assertTrue("a flattened line still needs a hit target", order.lines.all { it.hitTop > it.hitBottom })
    }

    // --- fixture ---------------------------------------------------------------------------------

    /** One line of [text] laid out left to right, [width] points per character. */
    private fun line(
        text: String,
        left: Float,
        top: Float,
        firstId: Int,
        width: Float = 6f,
        height: Float = 10f
    ): List<PdfChar> = text.mapIndexed { index, character ->
        PdfChar(
            id = firstId + index,
            codepoint = character.code,
            left = left + index * width,
            right = left + (index + 1) * width,
            bottom = top - height,
            top = top
        )
    }

    private fun char(id: Int, codepoint: Char, left: Float, top: Float) = PdfChar(
        id = id,
        codepoint = codepoint.code,
        left = left,
        right = left + 6f,
        bottom = top - 10f,
        top = top
    )

    private fun page(chars: List<PdfChar>, width: Float = 400f, height: Float = 600f) =
        PdfPageText(pageIndex = 0, widthPoints = width, heightPoints = height, chars = chars)
}
