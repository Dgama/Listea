package com.example.listea

import com.example.listea.domain.ANCHOR_FORMAT_VERSION
import com.example.listea.domain.AssetMark
import com.example.listea.domain.ButtonType
import com.example.listea.domain.MarkKind
import com.example.listea.domain.PDF_CHAR_PARSER
import com.example.listea.domain.QuoteAnchor
import com.example.listea.domain.TemplateButton
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a stored mark is shown, decided without a database and without the document changing.
 *
 * One rule, asked from five directions: a mark is visible when the control governing the Asset
 * *now* offers a passage tool with the mark's id, of the mark's kind. The template version recorded
 * on the mark is history and is never consulted — that is what makes a version upgrade keep its
 * marks while a removed tool hides them, and it is the difference between "hidden" and "deleted"
 * the feature promises.
 */
class AssetMarkVisibilityTest {

    @Test
    fun `a mark whose tool is still offered is visible`() = runBlocking {
        val marks = listOf(mark(toolId = "note", kind = MarkKind.COMMENT))

        val resolved = resolveMarks(marks, session(), listOf(comment("note")))

        assertEquals(1, resolved.size)
        assertFalse(resolved.single().detached)
    }

    @Test
    fun `a mark whose tool was removed from the template is hidden`() = runBlocking {
        val marks = listOf(mark(toolId = "note", kind = MarkKind.COMMENT))

        assertTrue(resolveMarks(marks, session(), listOf(comment("other"))).isEmpty())
    }

    @Test
    fun `no governing template at all hides every mark`() = runBlocking {
        val marks = listOf(
            mark(toolId = "note", kind = MarkKind.COMMENT),
            mark(toolId = "tag", kind = MarkKind.TAG)
        )

        assertTrue(resolveMarks(marks, session(), emptyList()).isEmpty())
    }

    @Test
    fun `a tool that changed kind no longer governs its old marks`() = runBlocking {
        // The same id, now a tag. The stored comment was authored under a comment tool; a tag tool
        // with that id is a different statement, and drawing the comment under it would be claiming
        // a tag nobody made.
        val marks = listOf(mark(toolId = "note", kind = MarkKind.COMMENT))

        assertTrue(resolveMarks(marks, session(), listOf(tag("note"))).isEmpty())
    }

    @Test
    fun `a boolean tool can never govern a passage mark`() = runBlocking {
        val marks = listOf(mark(toolId = "star", kind = MarkKind.COMMENT))

        assertTrue(resolveMarks(marks, session(), listOf(boolean("star"))).isEmpty())
    }

    @Test
    fun `a version upgrade with the same tool id keeps the marks`() = runBlocking {
        // The mark records that version 1 authored it. Nothing reads that number here: version 2
        // still offers the tool, so the mark is still about a passage this template can name.
        val marks = listOf(mark(toolId = "note", kind = MarkKind.COMMENT))

        val resolved = resolveMarks(marks, session(), listOf(comment("note")))

        assertEquals(1, resolved.size)
    }

    @Test
    fun `the authored template is history and never decides visibility`() = runBlocking {
        val marks = listOf(
            mark(toolId = "note", kind = MarkKind.COMMENT, authoredTemplateId = "deleted-template")
        )

        val resolved = resolveMarks(marks, session(), listOf(comment("note")))

        assertEquals(1, resolved.size)
        assertEquals("deleted-template", resolved.single().mark.authoredTemplateId)
    }

    @Test
    fun `the surviving marks keep the order they were stored in`() = runBlocking {
        val marks = listOf(
            mark(id = "m-1", toolId = "note", kind = MarkKind.COMMENT),
            mark(id = "m-2", toolId = "gone", kind = MarkKind.COMMENT),
            mark(id = "m-3", toolId = "tag", kind = MarkKind.TAG)
        )

        val resolved = resolveMarks(marks, session(), listOf(comment("note"), tag("tag")))

        assertEquals(listOf("m-1", "m-3"), resolved.map { it.mark.id })
    }

    // --- fixture ---------------------------------------------------------------------------------

    private fun session() = object : PdfDocumentSession {
        override val fingerprint: String = "sha"
        override val pageCount: Int = 1
        override suspend fun pageSize(pageIndex: Int) = PdfPageSize(400f, 600f)
        override suspend fun pageText(pageIndex: Int) = PdfPageText(
            pageIndex = 0,
            widthPoints = 400f,
            heightPoints = 600f,
            chars = "alphaone".mapIndexed { index, character ->
                PdfChar(
                    id = index,
                    codepoint = character.code,
                    left = 40f + index * 6f,
                    right = 40f + (index + 1) * 6f,
                    bottom = 490f,
                    top = 500f
                )
            }
        )
    }

    private fun comment(id: String) =
        TemplateButton(id, "Comment", ButtonType.COMMENT, "")

    private fun tag(id: String) =
        TemplateButton(id, "Tag", ButtonType.TAG, "funny")

    private fun boolean(id: String) =
        TemplateButton(id, "Star", ButtonType.BOOLEAN, "favorite")

    private fun mark(
        id: String = "m-1",
        toolId: String,
        kind: MarkKind,
        authoredTemplateId: String = "tpl"
    ) = AssetMark(
        id = id,
        assetId = "a-1",
        toolId = toolId,
        authoredTemplateId = authoredTemplateId,
        authoredTemplateVersion = 1,
        kind = kind,
        value = if (kind == MarkKind.COMMENT) "note" else "funny",
        anchor = QuoteAnchor(
            version = ANCHOR_FORMAT_VERSION,
            documentFingerprint = "sha",
            pageIndex = 0,
            exactText = "alphaone",
            prefix = "",
            suffix = "",
            charStart = 0,
            charEnd = 7,
            charParser = PDF_CHAR_PARSER
        ),
        createdAt = 1,
        updatedAt = 1
    )
}
