package com.example.listea

import com.example.listea.domain.ANCHOR_FORMAT_VERSION
import com.example.listea.domain.AssetMark
import com.example.listea.domain.MarkKind
import com.example.listea.domain.PDF_CHAR_PARSER
import com.example.listea.domain.QuoteAnchor
import com.example.listea.domain.TemplateButton
import com.example.listea.domain.ButtonType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding a stored passage in a document, without a renderer.
 *
 * The anchor is text plus neighbours, with a parser's index as a shortcut; these tests pin the three
 * answers a reader can give — the hinted range is right, the passage is somewhere else, or the
 * passage is not uniquely here — and the one thing that must never happen, which is a mark drawn
 * over text it does not quote.
 *
 * The pages are built from boxes, as [PdfReadingOrderTest]'s are, so the positions are known and a
 * failure says *where* the resolver went rather than that a fixture moved.
 */
class AssetMarkAnchorTest {

    // --- the fast path ---------------------------------------------------------------------------

    @Test
    fun `a hint that verifies resolves without a search and without a rewrite`() = runBlocking {
        val page = pageOf("alphaone", "betatwo")
        val session = FakeSession("same-bytes", listOf(page))
        val anchor = anchorAt(page, orderOf(page), 0..7, session.fingerprint)

        val resolved = resolveMarks(listOf(mark(anchor)), session, commentTool()).single()

        assertEquals(0, resolved.pageIndex)
        assertNull("an unchanged document should not rewrite its anchors", resolved.relocated)
        assertEquals(1, resolved.rects.size)
        assertEquals(40f, resolved.rects.single().left, 0.001f)
        assertEquals(88f, resolved.rects.single().right, 0.001f)
    }

    @Test
    fun `a hint whose text disagrees is not trusted`() = runBlocking {
        val page = pageOf("alphaone", "betatwo")
        val session = FakeSession("same-bytes", listOf(page))
        // The fingerprint matches, so a reader that trusted the numbers would take this range; its
        // text is a different word, which is exactly what the verification of the hint exists for.
        val wrongHint = anchorAt(page, orderOf(page), 8..14, session.fingerprint)
            .copy(exactText = "alphaone")

        val resolved = resolveMarks(listOf(mark(wrongHint)), session, commentTool()).single()

        assertEquals("the authoritative text should win over the hint", 0, resolved.pageIndex)
        assertNotNull("the anchor should move to where the text was found", resolved.relocated)
        assertEquals("alphaone", resolved.relocated!!.exactText)
        assertEquals(40f, resolved.rects.single().left, 0.001f)
    }

    // --- searching -------------------------------------------------------------------------------

    @Test
    fun `a repeated word is disambiguated by its neighbours`() = runBlocking {
        val page = pageOf("the cat", "the dog")
        val session = FakeSession("same-bytes", listOf(page))
        val order = orderOf(page)
        val second = order.text(0, order.size - 1).lastIndexOf("the")
        val positions = second until second + 3
        val anchor = anchorAt(page, order, positions, session.fingerprint)
            .copy(documentFingerprint = "old-bytes")

        val resolved = resolveMarks(listOf(mark(anchor)), session, commentTool()).single()

        assertEquals(0, resolved.pageIndex)
        // The second line's box starts at 40 and the first line's does too; the distinguishing fact
        // is the y, and the second occurrence is the lower line.
        assertEquals(480f, resolved.rects.single().top, 0.001f)
        assertEquals(" dog", resolved.relocated!!.suffix)
    }

    @Test
    fun `two equally good matches detach rather than guess`() = runBlocking {
        val page = pageOf("the", "the")
        val session = FakeSession("same-bytes", listOf(page))
        val anchor = QuoteAnchor(
            version = ANCHOR_FORMAT_VERSION,
            documentFingerprint = "old-bytes",
            pageIndex = 0,
            exactText = "the",
            prefix = "",
            suffix = "",
            charStart = -1,
            charEnd = -1,
            charParser = PDF_CHAR_PARSER
        )

        val resolved = resolveMarks(listOf(mark(anchor)), session, commentTool()).single()

        assertTrue("a word with no context cannot be placed", resolved.detached)
        assertTrue(resolved.rects.isEmpty())
        assertNull(resolved.relocated)
    }

    @Test
    fun `a passage that moved to another page is found where it moved to`() = runBlocking {
        val first = pageOf("alphaone")
        val second = pageOf("betatwo").copy(pageIndex = 1)
        val session = FakeSession("same-bytes", listOf(first, second))
        val onSecond = anchorAt(second, orderOf(second), 0..6, session.fingerprint)
        val anchor = onSecond.copy(documentFingerprint = "old-bytes", pageIndex = 0)

        val resolved = resolveMarks(listOf(mark(anchor)), session, commentTool()).single()

        assertEquals(1, resolved.pageIndex)
        assertEquals(1, resolved.relocated!!.pageIndex)
        assertEquals(40f, resolved.rects.single().left, 0.001f)
    }

    @Test
    fun `a changed document rewrites the anchor against the new bytes`() = runBlocking {
        val page = pageOf("alphaone")
        val session = FakeSession("new-bytes", listOf(page))
        val anchor = anchorAt(page, orderOf(page), 0..7, "old-bytes")

        val resolved = resolveMarks(listOf(mark(anchor)), session, commentTool()).single()

        assertEquals(0, resolved.pageIndex)
        val relocated = resolved.relocated!!
        assertEquals("new-bytes", relocated.documentFingerprint)
        assertEquals(PDF_CHAR_PARSER, relocated.charParser)
        assertEquals(ANCHOR_FORMAT_VERSION, relocated.version)
    }

    @Test
    fun `text this document does not contain detaches and is drawn nowhere`() = runBlocking {
        val page = pageOf("alphaone")
        val session = FakeSession("same-bytes", listOf(page))
        val anchor = anchorAt(page, orderOf(page), 0..7, session.fingerprint)
            .copy(exactText = "not on this page")

        val resolved = resolveMarks(listOf(mark(anchor)), session, commentTool()).single()

        assertTrue(resolved.detached)
        assertTrue("a detached mark has no geometry", resolved.rects.isEmpty())
        assertNull(resolved.relocated)
    }

    @Test
    fun `an anchor version this build does not know is not searched`() = runBlocking {
        val page = pageOf("alphaone")
        val session = FakeSession("same-bytes", listOf(page))
        val anchor = anchorAt(page, orderOf(page), 0..7, session.fingerprint)
            .copy(version = ANCHOR_FORMAT_VERSION + 1)

        val resolved = resolveMarks(listOf(mark(anchor)), session, commentTool()).single()

        assertTrue(resolved.detached)
    }

    // --- anchors ---------------------------------------------------------------------------------

    @Test
    fun `an anchor records the reading-order quote, its context and the parser`() {
        val page = pageOf("alphaone", "betatwo")
        val order = orderOf(page)
        val quote = quoteFor(order, 0, 0..7)

        val anchor = anchorFrom(quote, page, order, "sha")

        assertEquals("alphaone", anchor.exactText)
        assertEquals("", anchor.prefix)
        assertTrue("the suffix should carry the next line's newline", anchor.suffix.startsWith("\n"))
        assertEquals(0, anchor.charStart)
        assertEquals(7, anchor.charEnd)
        assertEquals(PDF_CHAR_PARSER, anchor.charParser)
        assertEquals(ANCHOR_FORMAT_VERSION, anchor.version)
    }

    // --- fixture ---------------------------------------------------------------------------------

    private fun commentTool() = listOf(
        TemplateButton(id = "tool-comment", name = "Comment", type = ButtonType.COMMENT, recordValue = "")
    )

    private fun mark(anchor: QuoteAnchor) = AssetMark(
        id = "m-1",
        assetId = "a-1",
        toolId = "tool-comment",
        authoredTemplateId = "tpl",
        authoredTemplateVersion = 1,
        kind = MarkKind.COMMENT,
        value = "note",
        anchor = anchor,
        createdAt = 1,
        updatedAt = 1
    )

    private fun pageOf(vararg lines: String): PdfPageText {
        val chars = ArrayList<PdfChar>()
        var id = 0
        lines.forEachIndexed { row, text ->
            val top = 500f - row * 20f
            text.forEachIndexed { index, character ->
                chars += PdfChar(
                    id = id++,
                    codepoint = character.code,
                    left = 40f + index * 6f,
                    right = 40f + (index + 1) * 6f,
                    bottom = top - 10f,
                    top = top
                )
            }
        }
        return PdfPageText(0, 400f, 600f, chars)
    }

    private fun orderOf(page: PdfPageText) = PdfReadingOrder.of(page)

    /** The anchor a selection over [range] would write, in the same page's own words. */
    private fun anchorAt(
        page: PdfPageText,
        order: PdfReadingOrder,
        range: IntRange,
        fingerprint: String
    ): QuoteAnchor = anchorFrom(quoteFor(order, page.pageIndex, range), page, order, fingerprint)

    private class FakeSession(
        override val fingerprint: String,
        private val pages: List<PdfPageText>
    ) : PdfDocumentSession {
        override val pageCount: Int get() = pages.size

        override suspend fun pageSize(pageIndex: Int): PdfPageSize? =
            pages.getOrNull(pageIndex)?.let { PdfPageSize(it.widthPoints, it.heightPoints) }

        override suspend fun pageText(pageIndex: Int): PdfPageText? =
            pages.getOrNull(pageIndex)?.takeIf { it.chars.isNotEmpty() }
    }
}
