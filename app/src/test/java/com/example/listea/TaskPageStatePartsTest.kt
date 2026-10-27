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
 * Where a surface stands on a multi-part Task's page list.
 *
 * [ReviewPagesTest] pins the single-part conversion. The claim here is the one a second part made
 * necessary: the Asset index is **read off the page**, so a Task of three questions and two files
 * reports the right index to every surface without any of them knowing how many parts exist.
 *
 * The three derived values are the ones three surfaces consume and used to compute for themselves:
 * - the inner counter's page number and count;
 * - the Asset index AssetRun needs (`-1` on a question, the Asset page count on the add-on);
 * - the page an Asset index names back, for a file chosen from the run.
 *
 * The add-on sentinel is the total Asset page count, not the part count — a Task whose parts have
 * no files still has zero Assets, and AssetRun recognises the questionnaire by exactly that.
 */
class TaskPageStatePartsTest {

    @Test
    fun `each page of a three-part Task reports its own kind and index`() {
        val state = { page: Int -> taskPageState(item(), addOn("Notes"), requestedPage = page) }

        assertEquals(6, state(0).pageCount)
        assertEquals(ReviewPage.Content("task-1", "q1"), state(0).current)
        assertEquals(-1, state(0).assetIndex)
        assertTrue(state(0).onContentPage)

        assertEquals(ReviewPage.Asset("q1", asset("a.jpg"), 0), state(1).current)
        assertEquals(0, state(1).assetIndex)
        assertEquals(ReviewPage.Asset("q1", asset("b.jpg"), 1), state(2).current)
        assertEquals(1, state(2).assetIndex)

        assertEquals(ReviewPage.Content("task-1", "q2"), state(3).current)
        assertEquals(-1, state(3).assetIndex)
        assertEquals(ReviewPage.Content("task-1", "q3"), state(4).current)
        assertEquals(-1, state(4).assetIndex)

        assertEquals(ReviewPage.AddOn, state(5).current)
        assertTrue(state(5).onAddOnPage)
        assertEquals(2, state(5).assetIndex)
    }

    @Test
    fun `the page an Asset index names is found across parts`() {
        val at = { page: Int -> taskPageState(item(), addOn("Notes"), requestedPage = page) }

        // Both Asset pages belong to the first part; the second and third have none.
        assertEquals(1, at(0).pageOfAssetIndex(0))
        assertEquals(2, at(0).pageOfAssetIndex(1))
        // An index no page holds leaves the caller where it was rather than jumping somewhere
        // unrelated — the defensive answer a stale selection relies on.
        assertEquals(3, at(3).pageOfAssetIndex(7))
        // The add-on is not an Asset page, so it does not resolve to one either.
        assertEquals(5, at(5).pageOfAssetIndex(2))
    }

    @Test
    fun `the Asset index round-trips through the page list for every page`() {
        val at = { page: Int -> taskPageState(item(), addOn("Notes"), requestedPage = page) }

        for (page in 0..5) {
            val state = at(page)
            val resolved = state.pageOfAssetIndex(state.assetIndex)
            assertEquals(
                "page $page should resolve to itself through its own Asset index",
                page,
                resolved
            )
        }
    }

    @Test
    fun `the run steps by Asset index only between two Asset pages`() {
        // [C1, a, b, C2, C3, add-on]. The run is what a swipe moves, so its own step must be
        // refused exactly where the neighbouring page is not an Asset: stepping by Asset index
        // there resolves to no page and the swipe would be swallowed. The caller turns those
        // pages by page index instead.
        val at = { page: Int -> taskPageState(item(), addOn("Notes"), requestedPage = page) }

        assertTrue(at(1).assetRunSteps(forward = true))    // a → b
        assertFalse(at(2).assetRunSteps(forward = true))   // b → C2
        assertFalse(at(4).assetRunSteps(forward = true))   // C3 → add-on
        assertFalse(at(5).assetRunSteps(forward = true))   // add-on → off the list

        assertTrue(at(2).assetRunSteps(forward = false))   // b → a
        assertFalse(at(1).assetRunSteps(forward = false))  // a → C1
        assertFalse(at(5).assetRunSteps(forward = false))  // add-on → C3, not an Asset
    }

    @Test
    fun `a media Task's run steps between its files and stops at the end`() {
        // No Content pages: every neighbour inside the list is an Asset, so a media Task pages
        // exactly as it did before there were unified pages at all.
        val item = item(
            part(key = "local", position = 0, assets = listOf(asset("a.jpg"), asset("b.jpg")))
        )
        val at = { page: Int -> taskPageState(item, addOn = null, requestedPage = page) }

        assertTrue(at(0).assetRunSteps(forward = true))
        assertTrue(at(1).assetRunSteps(forward = false))
        assertFalse(at(1).assetRunSteps(forward = true))
        assertFalse(at(0).assetRunSteps(forward = false))
    }

    @Test
    fun `a request past the last page clamps to the questionnaire`() {
        // A resync can remove a part under a selection; the caller asks with its old page and must
        // land on a page that exists, not on an index into nothing.
        assertEquals(5, taskPageState(item(), addOn("Notes"), requestedPage = 99).pageIndex)
        assertEquals(0, taskPageState(item(), addOn("Notes"), requestedPage = -1).pageIndex)
    }

    @Test
    fun `a part with no body but with files contributes Asset pages only`() {
        // The local file/folder shape: one part, no Content page, however many files it holds.
        val item = item(
            part(key = "local", position = 0, assets = listOf(asset("a.jpg"), asset("b.jpg")))
        )

        val state = taskPageState(item, addOn = null, requestedPage = 0)

        assertEquals(2, state.pageCount)
        assertFalse(state.hasContentPage)
        assertEquals(0, state.assetIndex)
        assertEquals(1, taskPageState(item, addOn = null, requestedPage = 1).assetIndex)
    }

    @Test
    fun `a Task whose parts have no files reports zero as the add-on sentinel`() {
        val item = item(
            part(key = "q1", position = 0, payloadKind = "form"),
            part(key = "q2", position = 1, payloadKind = "form")
        )

        val state = taskPageState(item, addOn("Notes"), requestedPage = 2)

        assertEquals(3, state.pageCount)
        assertEquals(0, state.assetPageCount)
        assertEquals(0, state.assetIndex)
        assertTrue(state.onAddOnPage)
    }

    @Test
    fun `a multi-part Task still reports whether it has a content page and a page level`() {
        val mediaOnly = taskPageState(
            item(part(key = "local", position = 0, assets = listOf(asset("a.jpg")))),
            addOn = null,
            requestedPage = 0
        )
        assertFalse(mediaOnly.hasContentPage)
        assertFalse(mediaOnly.hasPageLevel)

        val questions = taskPageState(item(), addOn = null, requestedPage = 0)
        assertTrue(questions.hasContentPage)
        assertTrue(questions.hasPageLevel)
        assertFalse(questions.hasAddOnPage)
    }

    // --- fixtures ---------------------------------------------------------------------------------

    /** q1 with two files, then q2 and q3 with none: [C1, a, b, C2, C3]. */
    private fun item() = ReviewItem(
        id = 0L,
        taskId = "task-1",
        publicId = "",
        title = "a section Task",
        sourceUri = null,
        relativePath = null,
        sourceMissing = false,
        target = DecisionTarget.File(rootUri = "content://root", relativePath = "a.jpg"),
        parts = listOf(
            part(
                key = "q1",
                position = 0,
                payloadKind = "form",
                assets = listOf(asset("a.jpg"), asset("b.jpg"))
            ),
            part(key = "q2", position = 1, payloadKind = "choice.single"),
            part(key = "q3", position = 2, payloadKind = "vocabulary")
        )
    )

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
        assets: List<ReviewAsset> = emptyList()
    ) = ReviewPart(
        partKey = key,
        position = position,
        promptKey = payloadKind?.let { key },
        payloadKind = payloadKind,
        payloadSchema = payloadKind?.let { 1 },
        payloadRevision = payloadKind?.let { 1 },
        assets = assets
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
