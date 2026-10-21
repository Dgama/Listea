package com.example.listea

import com.example.listea.domain.MarkKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the Info sheet says about a PDF's marks.
 *
 * The sheet is now the only place a mark is accounted for: there is no banner, nothing pops up, and
 * a mark whose passage this document no longer contains is not drawn. So the wording here is the
 * whole report — a placed mark has to name its tool, its kind and its page; a missing one has to
 * say so where a person reading the file will see it, without claiming the comment was lost.
 */
class PdfMarkRowsTest {

    private fun mark(
        tool: String = "C1",
        kind: MarkKind = MarkKind.COMMENT,
        quote: String = "the sentence",
        value: String = "my note",
        page: Int? = 0
    ) = PdfMarkInfo(tool, kind, quote, value, page)

    @Test
    fun `a document with no marks says so in one row`() {
        assertEquals(listOf(InfoField("Marks", "None")), pdfMarkRows(emptyList()))
    }

    @Test
    fun `a placed mark names its tool, kind and page above its quote and value`() {
        val rows = pdfMarkRows(listOf(mark(tool = "C1", kind = MarkKind.COMMENT, page = 2)))

        assertEquals(InfoField("Marks", "1 in this document"), rows[0])
        assertEquals("C1 · Comment · Page 3", rows[1].label)
        assertEquals("“the sentence”\nmy note", rows[1].value)
    }

    @Test
    fun `the quote is what was stored, and a tag's value is the configured tag`() {
        val rows = pdfMarkRows(
            listOf(mark(tool = "T1", kind = MarkKind.TAG, quote = "alpha beta", value = "funny"))
        )

        assertEquals("T1 · Tag · Page 1", rows[1].label)
        assertEquals("“alpha beta”\nfunny", rows[1].value)
    }

    @Test
    fun `a missing mark is listed with its own wording and counted in the summary`() {
        val rows = pdfMarkRows(
            listOf(
                mark(tool = "C1", page = 0),
                mark(tool = "C2", quote = "gone", value = "old note", page = null)
            )
        )

        assertEquals(InfoField("Marks", "2 · 1 not found in this document"), rows[0])
        assertEquals("C2 · Comment · Not in this document", rows[2].label)
        // The stored quote is still shown: it is how the reviewer recognises which mark this is.
        assertEquals("“gone”\nold note", rows[2].value)
    }

    @Test
    fun `every mark is listed in stored order`() {
        val rows = pdfMarkRows(
            listOf(mark(tool = "first"), mark(tool = "second"), mark(tool = "third"))
        )

        assertEquals(
            listOf("first · Comment · Page 1", "second · Comment · Page 1", "third · Comment · Page 1"),
            rows.drop(1).map { it.label }
        )
    }
}
