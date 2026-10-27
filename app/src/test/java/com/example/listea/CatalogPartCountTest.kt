package com.example.listea

import com.example.listea.data.CatalogRow
import com.example.listea.data.ReviewAsset
import com.example.listea.data.ReviewBlock
import com.example.listea.data.ReviewPart
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a catalog row reports about a Task's content.
 *
 * The row asks two different questions — how many decisions, and how many files — and the rule is
 * that a count is printed when it would otherwise be invisible or ambiguous. A media Task keeps
 * "4 Assets"; a question set says "3 questions, 2 Assets"; a vocabulary Task says "1 question,
 * 0 Assets" so the zero file count has an explanation beside it. No row ever calls a decision an
 * Asset, and no row calls the add-on a file.
 */
class CatalogPartCountTest {

    @Test
    fun `a media Task reports only its files`() {
        assertEquals("4 Assets", detail(row(parts = 1, assets = 4)))
        assertEquals("1 Asset", detail(row(parts = 1, assets = 1)))
    }

    @Test
    fun `a question set reports questions and files`() {
        assertEquals("3 questions, 2 Assets", detail(row(parts = 3, assets = 2)))
    }

    @Test
    fun `a vocabulary Task reports its question beside zero files`() {
        assertEquals("1 question, 0 Assets", detail(row(parts = 1, assets = 0)))
    }

    @Test
    fun `a missing file is named after the counts`() {
        assertEquals(
            "3 questions, 2 Assets, 1 missing",
            detail(row(parts = 3, assets = 2, missing = 1))
        )
    }

    @Test
    fun `an add-on is a page, not a file`() {
        assertEquals(
            "3 questions, 1 Asset, 1 add-on",
            detail(row(parts = 3, assets = 1), hasAddOn = true)
        )
        assertEquals("1 Asset, 1 add-on", detail(row(parts = 1, assets = 1), hasAddOn = true))
    }

    @Test
    fun `a blocking reason follows the counts`() {
        assertEquals(
            "2 Assets · Source missing",
            detail(row(parts = 1, assets = 2), block = ReviewBlock.MISSING)
        )
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private fun detail(
        row: CatalogRow,
        block: ReviewBlock? = null,
        hasAddOn: Boolean = false
    ): String = catalogTaskDetail(row, block, hasAddOn)

    /** A Task with [parts] decision units and [assets] files, spread over its parts. */
    private fun row(parts: Int, assets: Int, missing: Int = 0): CatalogRow {
        val assetsPerPart = if (parts == 0) emptyList() else (0 until parts).map { index ->
            if (index < assets) listOf(asset(index)) else emptyList()
        }
        return CatalogRow(
            taskId = "t",
            groupPath = "g",
            displayName = "Task",
            isCompleted = false,
            assetCount = assets,
            missingAssets = missing,
            sizeBytes = null,
            lastModified = null,
            hasUnviewableAsset = false,
            parts = (0 until parts).map { index ->
                ReviewPart(
                    partKey = "p$index",
                    position = index,
                    promptKey = if (parts > 1 || assets == 0) "p$index" else null,
                    payloadKind = if (parts > 1 || assets == 0) "profile" else null,
                    payloadSchema = if (parts > 1 || assets == 0) 1 else null,
                    payloadRevision = if (parts > 1 || assets == 0) 1 else null,
                    assets = assetsPerPart.getOrElse(index) { emptyList() }
                )
            }
        )
    }

    private fun asset(index: Int): ReviewAsset = ReviewAsset(
        assetId = "a$index",
        sourceUri = "file:///a$index",
        name = "a$index.jpg",
        sourceMissing = false
    )
}
