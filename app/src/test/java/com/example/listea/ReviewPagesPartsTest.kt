package com.example.listea

import com.example.listea.data.DecisionTarget
import com.example.listea.data.ReviewAsset
import com.example.listea.data.ReviewItem
import com.example.listea.data.ReviewPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page list of a Task that holds several decisions.
 *
 * A media Task is one part and its pages are exactly its files; [ReviewPagesTest] pins that and the
 * single-part Content case. What v0.8.4 adds is a Task whose parts each present something: the list
 * must keep **composition order**, give each part its own Content page before its own files, and
 * still put the questionnaire last. The order asserted here is the order the arrows, the counter,
 * the queue guide and the resume cursor all walk, because they all read this one list.
 *
 * Absence is the other half: a part the latest Package revision no longer names keeps its history
 * but contributes no page, and its files must not be shown under a question that is not being asked.
 */
class ReviewPagesPartsTest {

    @Test
    fun `a three-part section Task presents each part's pages in composition order`() {
        val item = item(
            part(key = "q1", position = 0, payloadKind = "form", assets = listOf(asset("a.jpg"), asset("b.jpg"))),
            part(key = "q2", position = 1, payloadKind = "choice.single"),
            part(key = "q3", position = 2, payloadKind = "vocabulary")
        )

        assertEquals(
            listOf(
                ReviewPage.Content("task-1", "q1"),
                ReviewPage.Asset("q1", asset("a.jpg"), assetIndex = 0),
                ReviewPage.Asset("q1", asset("b.jpg"), assetIndex = 1),
                ReviewPage.Content("task-1", "q2"),
                ReviewPage.Content("task-1", "q3"),
                ReviewPage.AddOn
            ),
            reviewPages(item, hasAddOn = true)
        )
    }

    @Test
    fun `the Asset index counts the whole Task, not one part's files`() {
        // Each Asset page carries the index AssetRun expects, and AssetRun is shown one Task's file
        // run. Numbering per part would make the second part's first file claim index 0 and collide
        // with the first part's — which is why the page, not the part, owns the index.
        val pages = reviewPages(
            item(
                part(key = "q1", position = 0, payloadKind = "form", assets = listOf(asset("a.jpg"))),
                part(key = "q2", position = 1, payloadKind = "form", assets = listOf(asset("b.jpg")))
            ),
            hasAddOn = false
        )

        assertEquals(
            listOf(0, 1),
            pages.filterIsInstance<ReviewPage.Asset>().map { it.assetIndex }
        )
        assertEquals(listOf("q1", "q2"), pages.filterIsInstance<ReviewPage.Asset>().map { it.partKey })
    }

    @Test
    fun `an absent part contributes neither a question nor its files`() {
        val item = item(
            part(key = "q1", position = 0, payloadKind = "form"),
            part(
                key = "q2",
                position = 1,
                payloadKind = "form",
                present = false,
                assets = listOf(asset("dropped.jpg"))
            ),
            part(key = "q3", position = 2, payloadKind = "form")
        )

        val pages = reviewPages(item, hasAddOn = false)

        assertEquals(
            listOf(
                ReviewPage.Content("task-1", "q1"),
                ReviewPage.Content("task-1", "q3")
            ),
            pages
        )
        assertFalse(pages.any { it is ReviewPage.Asset })
    }

    @Test
    fun `a part with no body and no files draws no page but does not shift the others`() {
        // A degenerate part is not presentable content; it is a stored row, not a question. The
        // following part must keep its own Content page rather than inherit the empty one.
        val pages = reviewPages(
            item(
                part(key = "q1", position = 0, payloadKind = "form"),
                part(key = "local", position = 1),
                part(key = "q2", position = 2, payloadKind = "form")
            ),
            hasAddOn = false
        )

        assertEquals(
            listOf(ReviewPage.Content("task-1", "q1"), ReviewPage.Content("task-1", "q2")),
            pages
        )
    }

    @Test
    fun `the questionnaire follows the last present part`() {
        val withAbsentLast = item(
            part(key = "q1", position = 0, payloadKind = "form"),
            part(key = "q2", position = 1, payloadKind = "form", present = false)
        )

        val pages = reviewPages(withAbsentLast, hasAddOn = true)

        assertEquals(ReviewPage.Content("task-1", "q1"), pages.first())
        assertEquals(ReviewPage.AddOn, pages.last())
        assertTrue(pages.size == 2)
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private fun item(vararg parts: ReviewPart) = ReviewItem(
        id = 0L,
        taskId = "task-1",
        publicId = "",
        title = "a Task",
        sourceUri = null,
        relativePath = null,
        sourceMissing = false,
        target = DecisionTarget.File(rootUri = "content://root", relativePath = "a.jpg"),
        parts = parts.toList()
    )

    private fun part(
        key: String,
        position: Int,
        payloadKind: String? = null,
        present: Boolean = true,
        assets: List<ReviewAsset> = emptyList()
    ) = ReviewPart(
        partKey = key,
        position = position,
        promptKey = payloadKind?.let { key },
        payloadKind = payloadKind,
        payloadSchema = payloadKind?.let { 1 },
        payloadRevision = payloadKind?.let { 1 },
        present = present,
        assets = assets
    )

    private fun asset(name: String) = ReviewAsset(
        sourceUri = "content://fixture/$name",
        name = name,
        sourceMissing = false
    )
}
