package com.example.listea

import com.example.listea.domain.ANCHOR_FORMAT_VERSION
import com.example.listea.domain.AssetMark
import com.example.listea.domain.ButtonType
import com.example.listea.domain.MarkKind
import com.example.listea.domain.PDF_CHAR_PARSER
import com.example.listea.domain.QuoteAnchor
import com.example.listea.domain.TemplateButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a finger on a highlight lands on, decided from the rectangles the page draws.
 *
 * Two marks over one sentence are ordinary — a comment and a tag — and the answer is both, not a
 * choice. A detached mark has no rectangle, so no point can find it: that is what makes the sheet's
 * two entries ("under the finger" and "not in this document") mutually exclusive.
 */
class PdfMarkGeometryTest {

    @Test
    fun `overlapping marks are both returned in stored order`() {
        val comment = resolved("m-1", MarkKind.COMMENT, rect(10f, 500f, 110f, 520f))
        val tag = resolved("m-2", MarkKind.TAG, rect(50f, 490f, 160f, 515f))

        val found = marksAt(listOf(comment, tag), x = 60f, y = 505f)

        assertEquals(listOf("m-1", "m-2"), found.map { it.mark.id })
    }

    @Test
    fun `a point outside every rectangle finds nothing`() {
        val mark = resolved("m-1", MarkKind.COMMENT, rect(10f, 500f, 110f, 520f))

        assertTrue(marksAt(listOf(mark), x = 200f, y = 505f).isEmpty())
        assertTrue(marksAt(listOf(mark), x = 60f, y = 400f).isEmpty())
    }

    @Test
    fun `a detached mark is never under a finger`() {
        val detached = resolved(
            "m-1",
            MarkKind.COMMENT,
            rect = null,
            pageIndex = null
        )

        assertTrue(marksAt(listOf(detached), x = 0f, y = 0f).isEmpty())
    }

    private fun rect(left: Float, bottom: Float, right: Float, top: Float) =
        PdfMarkRect(left = left, bottom = bottom, right = right, top = top)

    private fun resolved(
        id: String,
        kind: MarkKind,
        rect: PdfMarkRect?,
        pageIndex: Int? = 0
    ) = ResolvedMark(
        mark = AssetMark(
            id = id,
            assetId = "a-1",
            toolId = "tool",
            authoredTemplateId = "tpl",
            authoredTemplateVersion = 1,
            kind = kind,
            value = "value",
            anchor = QuoteAnchor(
                version = ANCHOR_FORMAT_VERSION,
                documentFingerprint = "sha",
                pageIndex = 0,
                exactText = "the passage",
                prefix = "",
                suffix = "",
                charStart = 0,
                charEnd = 10,
                charParser = PDF_CHAR_PARSER
            ),
            createdAt = 1,
            updatedAt = 1
        ),
        tool = TemplateButton(
            id = "tool",
            name = if (kind == MarkKind.COMMENT) "Comment" else "Tag",
            type = if (kind == MarkKind.COMMENT) ButtonType.COMMENT else ButtonType.TAG,
            recordValue = "value"
        ),
        pageIndex = pageIndex,
        rects = listOfNotNull(rect)
    )
}
