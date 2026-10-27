package com.example.listea

import com.example.listea.data.DecisionTarget
import com.example.listea.data.ReviewAsset
import com.example.listea.data.ReviewItem
import com.example.listea.domain.AddOnId
import com.example.listea.domain.AddOnVersionRef
import com.example.listea.domain.StoredAddOnVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The typed page list: Content, then Assets, then the add-on.
 *
 * One list feeds the counter, the arrows, the resume identity and the guide, so the order asserted
 * here is the order every one of those surfaces walks. The Content page exists only when the item
 * carries a payload envelope — a media Task's pages are exactly what they were before v0.8.2 — and
 * the add-on stays last because it is a page of the Task but not a file the source holds.
 */
class ReviewPagesTest {

    @Test
    fun `a media Task's pages are its Assets in order`() {
        val item = item(assets = listOf(asset("a.jpg"), asset("b.jpg")))

        val pages = reviewPages(item, hasAddOn = false)

        assertEquals(
            listOf(ReviewPage.Asset(asset("a.jpg")), ReviewPage.Asset(asset("b.jpg"))),
            pages
        )
        assertFalse(item.hasContentPage())
    }

    @Test
    fun `a Prompt's Content page comes first, even before its Assets`() {
        val item = item(payloadKind = "profile", assets = listOf(asset("a.jpg")))

        val pages = reviewPages(item, hasAddOn = false)

        assertEquals(
            listOf(ReviewPage.Content(item.taskId), ReviewPage.Asset(asset("a.jpg"))),
            pages
        )
        assertTrue(item.hasContentPage())
    }

    @Test
    fun `a Prompt with no Assets is still one page`() {
        val item = item(payloadKind = "profile")

        assertEquals(listOf(ReviewPage.Content(item.taskId)), reviewPages(item, hasAddOn = false))
    }

    @Test
    fun `the add-on page stays last after Content and Assets`() {
        val item = item(payloadKind = "profile", assets = listOf(asset("a.jpg")))

        val pages = reviewPages(item, hasAddOn = true)

        assertEquals(
            listOf(
                ReviewPage.Content(item.taskId),
                ReviewPage.Asset(asset("a.jpg")),
                ReviewPage.AddOn
            ),
            pages
        )
    }

    @Test
    fun `a vocabulary Prompt has exactly the pages of any Prompt`() {
        // The pronunciation is a Resource used by the Content page, not an Asset. A vocabulary
        // Task therefore gets one Content page and then whatever files the Task really has; there
        // is no second page type and no audio page.
        val item = item(payloadKind = "vocabulary", assets = listOf(asset("a.jpg")))

        assertEquals(
            listOf(ReviewPage.Content(item.taskId), ReviewPage.Asset(asset("a.jpg"))),
            reviewPages(item, hasAddOn = false)
        )
        assertEquals(
            3,
            taskPageState(item, addOn("Notes"), requestedPage = 0).pageCount
        )
    }

    @Test
    fun `a media Task with an add-on is unchanged by the page model`() {
        val item = item(assets = listOf(asset("a.jpg"), asset("b.jpg")))

        assertEquals(
            listOf(
                ReviewPage.Asset(asset("a.jpg")),
                ReviewPage.Asset(asset("b.jpg")),
                ReviewPage.AddOn
            ),
            reviewPages(item, hasAddOn = true)
        )
    }

    @Test
    fun `the Asset index is the unified page minus the Content page`() {
        // A Prompt with a body and two files has unified pages 0=Content, 1=a, 2=b. AssetRun counts
        // Assets, so it must be handed 0..1 rather than 1..2.
        assertEquals(0, assetIndexOf(page = 1, hasContent = true))
        assertEquals(1, assetIndexOf(page = 2, hasContent = true))
        // The add-on sentinel is the Asset count, which is the same conversion.
        assertEquals(2, assetIndexOf(page = 3, hasContent = true))
        // A media Task has no offset.
        assertEquals(1, assetIndexOf(page = 1, hasContent = false))
    }

    @Test
    fun `the unified page is the Asset index plus the Content page, and the pair round-trips`() {
        assertEquals(1, pageOfAsset(assetIndex = 0, hasContent = true))
        assertEquals(3, pageOfAsset(assetIndex = 2, hasContent = true))
        assertEquals(0, pageOfAsset(assetIndex = 0, hasContent = false))
        for (index in 0..3) {
            assertEquals(index, pageOfAsset(assetIndexOf(index, true), true))
            assertEquals(index, assetIndexOf(pageOfAsset(index, true), true))
        }
    }

    @Test
    fun `a committing answer lands on the add-on page before the queue moves on`() {
        // A Prompt with no files: pages 0=Content, 1=Add-on. Liking it used to advance the queue
        // and leave the questionnaire unseen.
        assertEquals(
            CommitArrival.AddOnPage,
            commitArrival(
                advanceAfterCommit = true,
                hasAddOnPage = true,
                currentPage = 0,
                pageCount = 2
            )
        )
        // A media Task judged from its second file (of three) still reaches the last page, because
        // the add-on belongs to the Task and not to any one file.
        assertEquals(
            CommitArrival.AddOnPage,
            commitArrival(
                advanceAfterCommit = true,
                hasAddOnPage = true,
                currentPage = 1,
                pageCount = 4
            )
        )
    }

    @Test
    fun `a commit already on the add-on page advances the queue`() {
        assertEquals(
            CommitArrival.NextTask,
            commitArrival(
                advanceAfterCommit = true,
                hasAddOnPage = true,
                currentPage = 3,
                pageCount = 4
            )
        )
    }

    @Test
    fun `a Task with no add-on advances straight away`() {
        assertEquals(
            CommitArrival.NextTask,
            commitArrival(
                advanceAfterCommit = true,
                hasAddOnPage = false,
                currentPage = 0,
                pageCount = 1
            )
        )
    }

    @Test
    fun `a template that does not advance stays on the page`() {
        assertEquals(
            null,
            commitArrival(
                advanceAfterCommit = false,
                hasAddOnPage = true,
                currentPage = 0,
                pageCount = 2
            )
        )
    }

    @Test
    fun `the guide names the Content page Prompt rather than borrowing a file's name`() {
        val entries = reviewPageEntries(
            assets = listOf(asset("a.jpg")),
            addOn = addOn("Notes"),
            content = true
        )

        assertEquals(listOf("Prompt", "a.jpg", "add-on Notes"), entries.map { it.label })
    }

    @Test
    fun `the page state gives every surface the same count and Asset index`() {
        // A Prompt with two files and an add-on: 0=Content, 1=a, 2=b, 3=Add-on. AssetRun counts
        // Assets, so the Content page is -1 and the add-on is the sentinel assets.size.
        val item = item(payloadKind = "profile", assets = listOf(asset("a.jpg"), asset("b.jpg")))
        val addOn = addOn("Notes")

        val at = { page: Int -> taskPageState(item, addOn, requestedPage = page) }
        assertEquals(4, at(0).pageCount)
        assertEquals(-1, at(0).assetIndex)
        assertTrue(at(0).onContentPage)
        assertTrue(at(0).hasContentPage)
        assertTrue(at(0).hasAddOnPage)

        assertEquals(0, at(1).assetIndex)
        assertEquals(1, at(2).assetIndex)
        assertFalse(at(2).onAddOnPage)

        assertEquals(2, at(3).assetIndex)
        assertTrue(at(3).onAddOnPage)
        assertFalse(at(3).onContentPage)
    }

    @Test
    fun `the page state clamps a page a resync has taken away`() {
        val item = item(payloadKind = "profile", assets = listOf(asset("a.jpg")))

        assertEquals(0, taskPageState(item, addOn = null, requestedPage = -5).pageIndex)
        assertEquals(1, taskPageState(item, addOn = null, requestedPage = 99).pageIndex)
        assertEquals(
            ReviewPage.Asset(asset("a.jpg")),
            taskPageState(item, addOn = null, requestedPage = 99).current
        )
    }

    @Test
    fun `a Task with no add-on keeps the pages it always had`() {
        val item = item(assets = listOf(asset("a.jpg")))

        val state = taskPageState(item, addOn = null, requestedPage = 0)

        assertEquals(1, state.pageCount)
        assertEquals(0, state.assetIndex)
        assertFalse(state.hasPageLevel)
        assertFalse(state.hasAddOnPage)
        assertFalse(state.hasContentPage)
    }

    @Test
    fun `a Prompt with no files still counts the add-on as its last page`() {
        val item = item(payloadKind = "profile")

        val state = taskPageState(item, addOn("Notes"), requestedPage = 1)

        assertEquals(2, state.pageCount)
        assertTrue(state.onAddOnPage)
        // assets.size == 0, which is the sentinel AssetRun recognises as the add-on page.
        assertEquals(0, state.assetIndex)
    }

    @Test
    fun `an item with no pages at all reports none rather than inventing one`() {
        val state = taskPageState(item(), addOn = null, requestedPage = 0)

        assertEquals(0, state.pageCount)
        assertEquals(null, state.current)
        assertFalse(state.hasPageLevel)
    }

    @Test
    fun `a questionnaire with no questions is attached but draws no page`() {
        // `""` and a version this build cannot read are both absent; a readable version with an
        // empty question list is attached but would draw a blank page, so only the page model
        // treats it as absent. Export and write paths still read versionFor.
        val empty = addOn("Notes")
        val resolver = AddOnResolver(mapOf(empty.ref to empty))
        val encoded = encodeAddOnAttachment(empty.ref)

        assertEquals(empty, resolver.versionFor(encoded))
        assertEquals(null, resolver.readableVersion(encoded))
    }

    private fun item(
        payloadKind: String? = null,
        assets: List<ReviewAsset> = emptyList()
    ) = ReviewItem(
        id = 0L,
        taskId = "task-1",
        publicId = "",
        title = "a.jpg",
        sourceUri = null,
        relativePath = "a.jpg",
        sourceMissing = false,
        target = DecisionTarget.File(rootUri = "content://root", relativePath = "a.jpg"),
        assets = assets,
        payloadKind = payloadKind,
        payloadSchema = payloadKind?.let { 1 },
        payloadRevision = payloadKind?.let { 1 }
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
