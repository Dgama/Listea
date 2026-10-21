package com.example.listea

import com.example.listea.data.ListItemEntity
import com.example.listea.data.PayloadAssetRow
import com.example.listea.data.PayloadMarkRow
import com.example.listea.data.PayloadTaskRow
import com.example.listea.data.TemplateEntity
import com.example.listea.data.TemplateVersionEntity
import com.example.listea.domain.AssetKind
import com.example.listea.domain.ButtonType
import com.example.listea.domain.MarkKind
import com.example.listea.domain.TEMPLATE_BUTTONS_COMMENT_VERSION
import com.example.listea.domain.TemplateButton
import com.example.listea.domain.TemplateId
import com.example.listea.domain.TemplateScope
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.encodeTemplateButtons
import com.example.listea.domain.encodeTemplateScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a PDF's stored passages become on the wire.
 *
 * The filters are the feature: a mark that the current template no longer offers, that has changed
 * kind, or whose document is not the one its anchor was verified against must be **absent**, not
 * sent with a stale value. A receiver that trusts `marks` is being told these sentences are in this
 * file; anything this function cannot stand behind is not a sentence it may say.
 *
 * A tag is the interesting one. Its stored value records what the tool sent when the mark was made,
 * but the tool's value is configuration and the mark means *this passage is funny* — so the current
 * value is what leaves. A comment has no such indirection: its text is what the reviewer wrote.
 */
class ExportMarksTest {

    @Test
    fun `a comment and a tag export with their own values in creation order`() {
        val asset = view(
            marks = listOf(
                mark(id = "m-2", toolId = "tag-1", kind = "tag", value = "stored-old", createdAt = 20),
                mark(id = "m-1", toolId = "comment-1", kind = "comment", value = "I think so", createdAt = 10)
            )
        )

        assertEquals(listOf("m-1", "m-2"), asset.marks.map { it.id })
        assertEquals(MarkKind.COMMENT, asset.marks[0].kind)
        assertEquals("I think so", asset.marks[0].value)
        assertEquals("comment-1", asset.marks[0].toolId)
        assertEquals(3, asset.marks[0].pageIndex)
        assertEquals("the passage", asset.marks[0].text)
        assertEquals(MarkKind.TAG, asset.marks[1].kind)
        assertEquals("funny_sentence", asset.marks[1].value)
    }

    @Test
    fun `a tag sends the current tool value rather than the stored one`() {
        val asset = view(
            marks = listOf(
                mark(id = "m-1", toolId = "tag-1", kind = "tag", value = "at-creation")
            )
        )

        assertEquals("funny_sentence", asset.marks.single().value)
    }

    @Test
    fun `a mark whose tool the current template no longer offers is omitted`() {
        val asset = view(
            marks = listOf(
                mark(id = "m-1", toolId = "comment-1", kind = "comment"),
                mark(id = "m-2", toolId = "gone", kind = "tag")
            )
        )

        assertEquals(listOf("m-1"), asset.marks.map { it.id })
    }

    @Test
    fun `a tool that changed kind no longer exports its old marks`() {
        val asset = view(
            marks = listOf(
                mark(id = "m-1", toolId = "comment-1", kind = "tag")
            )
        )

        assertTrue(asset.marks.isEmpty())
    }

    @Test
    fun `a mark written against different bytes is omitted`() {
        val asset = view(
            marks = listOf(mark(id = "m-1", toolId = "comment-1", kind = "comment", fingerprint = "old")),
            fingerprint = "new"
        )

        assertTrue(asset.marks.isEmpty())
    }

    @Test
    fun `when the file could not be hashed every mark is omitted`() {
        val asset = view(
            marks = listOf(mark(id = "m-1", toolId = "comment-1", kind = "comment")),
            fingerprint = null
        )

        assertTrue(asset.marks.isEmpty())
    }

    @Test
    fun `an unknown stored kind is skipped rather than guessed`() {
        val asset = view(
            marks = listOf(
                mark(id = "m-1", toolId = "comment-1", kind = "highlight"),
                mark(id = "m-2", toolId = "comment-1", kind = "comment")
            )
        )

        assertEquals(listOf("m-2"), asset.marks.map { it.id })
    }

    @Test
    fun `a PDF's file-level actions stay empty`() {
        val asset = view(
            marks = listOf(mark(id = "m-1", toolId = "comment-1", kind = "comment"))
        )

        assertTrue("passage tools must not be read as file-level actions", asset.actions.isEmpty())
    }

    // --- fixture ---------------------------------------------------------------------------------

    private fun view(
        marks: List<PayloadMarkRow>,
        fingerprint: String? = "fp"
    ): ExportAssetView {
        val row = ListItemEntity(
            id = 1,
            publicId = "ROW-1",
            listId = 7,
            taskId = TASK_ID,
            title = "doc.pdf",
            isCompleted = true,
            sortOrder = 0,
            createdAt = 1,
            sourceUri = "content://root/papers/doc.pdf",
            sourceRelativePath = "papers/doc.pdf",
            rootRelativePath = "papers/doc.pdf",
            selections = ""
        )
        val asset = PayloadAssetRow(
            taskId = TASK_ID,
            assetId = "a-1",
            publicId = "ASSET-1",
            relativePath = "papers/doc.pdf",
            sourceMissing = false,
            sortOrder = 0,
            selections = ""
        )
        val tasks = mapOf(TASK_ID to PayloadTaskRow(TASK_ID, "TASK-1", null, ATTACHMENT))
        return exportTaskViews(
            rows = listOf(row),
            tasks = tasks,
            assetsByTask = mapOf(TASK_ID to listOf(asset)),
            resolver = resolver(),
            marksByAsset = mapOf("a-1" to marks),
            fingerprintByAsset = fingerprint?.let { mapOf("a-1" to it) }.orEmpty()
        ).single().assets.single()
    }

    private fun mark(
        id: String,
        toolId: String,
        kind: String,
        value: String = "value",
        fingerprint: String = "fp",
        createdAt: Long = 0
    ) = PayloadMarkRow(
        id = id,
        assetId = "a-1",
        toolId = toolId,
        kind = kind,
        value = value,
        pageIndex = 3,
        exactText = "the passage",
        documentFingerprint = fingerprint,
        createdAt = createdAt
    )

    private fun resolver(): TemplateResolver = TemplateResolver.of(
        listOf(TemplateEntity(id = LINEAGE, name = "PDF review", createdAt = 0)),
        listOf(
            TemplateVersionEntity(
                templateId = LINEAGE,
                version = 1,
                scope = encodeTemplateScope(TemplateScope.ofKinds(setOf(AssetKind.PDF))),
                buttons = encodeTemplateButtons(
                    listOf(
                        TemplateButton("comment-1", "Comment", ButtonType.COMMENT, ""),
                        TemplateButton("tag-1", "Funny", ButtonType.TAG, "funny_sentence")
                    )
                ),
                buttonsVersion = TEMPLATE_BUTTONS_COMMENT_VERSION,
                createdAt = 0
            )
        )
    )

    private companion object {
        const val TASK_ID = "t-1"
        const val LINEAGE = "tpl.pdf"
        val ATTACHMENT = encodeTemplateAttachments(
            listOf(TemplateVersionRef(TemplateId(LINEAGE), 1))
        )
    }
}
