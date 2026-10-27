package com.example.listea

import com.example.listea.data.DecisionTarget
import com.example.listea.data.ReviewAsset
import com.example.listea.data.ReviewItem
import com.example.listea.data.ReviewPart
import com.example.listea.domain.AddOnId
import com.example.listea.domain.AddOnVersionRef
import com.example.listea.domain.StoredAddOnVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape that must not move: a Task of files and nothing else.
 *
 * v0.8.4 made the page list typed and part-scoped, and the risk of such a change is paid by the
 * ordinary Tasks that were already correct. Before parts, the count was
 * [taskPageCount]`(assetCount, hasAddOn)` and the Asset index was the page index; a file-only Task
 * must still report exactly those numbers, page for page, so a user sees no change at all.
 *
 * The assertions below are deliberately written against the old arithmetic rather than against
 * literals: if someone changes both the old function and the new list together, the literals would
 * follow them and this test would still pass. Asking the two to agree is the regression claim.
 */
class MediaRegressionPagesTest {

    @Test
    fun `a media Task's page count is the old count for every file and either add-on state`() {
        for (assetCount in 0..4) {
            for (hasAddOn in listOf(false, true)) {
                val item = mediaItem(assetCount)
                val addOn = if (hasAddOn) addOn("Notes") else null

                assertEquals(
                    "assets=$assetCount addOn=$hasAddOn",
                    taskPageCount(assetCount = assetCount, hasAddOn = hasAddOn),
                    taskPageState(item, addOn, requestedPage = 0).pageCount
                )
            }
        }
    }

    @Test
    fun `every media page is its Asset, in file order`() {
        val item = mediaItem(3)

        assertEquals(
            listOf(
                ReviewPage.Asset("local:0", asset("a.jpg"), 0),
                ReviewPage.Asset("local:0", asset("b.jpg"), 1),
                ReviewPage.Asset("local:0", asset("c.jpg"), 2)
            ),
            reviewPages(item, hasAddOn = false)
        )
    }

    @Test
    fun `a media Task never gains a Content page`() {
        val item = mediaItem(3)

        assertFalse(reviewPages(item, hasAddOn = true).any { it is ReviewPage.Content })
        assertFalse(taskPageState(item, addOn("Notes"), requestedPage = 0).hasContentPage)
    }

    @Test
    fun `the Asset index on a media Task is still the page index`() {
        val item = mediaItem(3)

        for (page in 0..2) {
            val state = taskPageState(item, addOn = null, requestedPage = page)
            assertEquals(page, state.assetIndex)
            assertEquals(page, state.pageOfAssetIndex(page))
        }
        // The add-on page keeps the sentinel the old AssetRun used: the number of Assets.
        val withAddOn = taskPageState(item, addOn("Notes"), requestedPage = 3)
        assertEquals(3, withAddOn.assetIndex)
        assertTrue(withAddOn.onAddOnPage)
    }

    @Test
    fun `a one-file media Task is unchanged from a one-page Task`() {
        val state = taskPageState(mediaItem(1), addOn = null, requestedPage = 0)

        assertEquals(1, state.pageCount)
        assertEquals(0, state.assetIndex)
        assertFalse(state.hasPageLevel)
        assertEquals(ReviewPage.Asset("local:0", asset("a.jpg"), 0), state.current)
    }

    @Test
    fun `a media Task with no files still has only its questionnaire`() {
        // Not a real media Task, but the arithmetic edge the old count had to answer and the typed
        // list must answer the same way.
        val state = taskPageState(mediaItem(0), addOn("Notes"), requestedPage = 0)

        assertEquals(1, state.pageCount)
        assertEquals(ReviewPage.AddOn, state.current)
        assertEquals(0, state.assetIndex)
    }

    // --- fixtures ---------------------------------------------------------------------------------

    /** One degenerate part with [count] files, exactly what a file/folder import writes. */
    private fun mediaItem(count: Int) = ReviewItem(
        id = 0L,
        taskId = "task-media",
        publicId = "pub-media",
        title = "a.jpg",
        sourceUri = null,
        relativePath = "a.jpg",
        sourceMissing = false,
        target = DecisionTarget.File(rootUri = "content://root", relativePath = "a.jpg"),
        parts = listOf(
            ReviewPart(
                partKey = "local:0",
                position = 0,
                assets = listOf("a.jpg", "b.jpg", "c.jpg", "d.jpg").take(count).map { asset(it) }
            )
        )
    )

    private fun asset(name: String) = ReviewAsset(
        sourceUri = "content://fixture/$name",
        name = name,
        sourceMissing = false
    )

    private fun addOn(name: String) = StoredAddOnVersion(
        ref = AddOnVersionRef(AddOnId("add-on-fixture"), version = 1),
        name = name,
        questions = emptyList()
    )
}
