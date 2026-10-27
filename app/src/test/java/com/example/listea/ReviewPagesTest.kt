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
 * The typed page list: each present part's Content page and Assets, then the add-on.
 *
 * One list feeds the counter, the arrows, the resume identity and the guide, so the order asserted
 * here is the order every one of those surfaces walks. Parts keep composition order, and each
 * contributes its Content page first (when it carries a payload envelope) and then its own Assets.
 * A media Task is one degenerate part with no body, so its pages are exactly what they were before
 * v0.8.2. The add-on stays last because it is a page of the Task but not a file the source holds.
 */
class ReviewPagesTest {

    @Test
    fun `a media Task's pages are its Assets in order`() {
        val item = item(part(assets = listOf(asset("a.jpg"), asset("b.jpg"))))

        val pages = reviewPages(item, hasAddOn = false)

        assertEquals(
            listOf(
                ReviewPage.Asset("p1", asset("a.jpg"), assetIndex = 0),
                ReviewPage.Asset("p1", asset("b.jpg"), assetIndex = 1)
            ),
            pages
        )
        assertFalse(item.hasContentPage())
    }

    @Test
    fun `a Prompt's Content page comes first, even before its Assets`() {
        val item = item(part(payloadKind = "profile", assets = listOf(asset("a.jpg"))))

        val pages = reviewPages(item, hasAddOn = false)

        assertEquals(
            listOf(
                ReviewPage.Content(item.taskId, "p1"),
                ReviewPage.Asset("p1", asset("a.jpg"), assetIndex = 0)
            ),
            pages
        )
        assertTrue(item.hasContentPage())
    }

    @Test
    fun `a Prompt with no Assets is still one page`() {
        val item = item(part(payloadKind = "profile"))

        assertEquals(
            listOf(ReviewPage.Content(item.taskId, "p1")),
            reviewPages(item, hasAddOn = false)
        )
    }

    @Test
    fun `the add-on page stays last after Content and Assets`() {
        val item = item(part(payloadKind = "profile", assets = listOf(asset("a.jpg"))))

        val pages = reviewPages(item, hasAddOn = true)

        assertEquals(
            listOf(
                ReviewPage.Content(item.taskId, "p1"),
                ReviewPage.Asset("p1", asset("a.jpg"), assetIndex = 0),
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
        val item = item(part(payloadKind = "vocabulary", assets = listOf(asset("a.jpg"))))

        assertEquals(
            listOf(
                ReviewPage.Content(item.taskId, "p1"),
                ReviewPage.Asset("p1", asset("a.jpg"), assetIndex = 0)
            ),
            reviewPages(item, hasAddOn = false)
        )
        assertEquals(
            3,
            taskPageState(item, addOn("Notes"), requestedPage = 0).pageCount
        )
    }

    @Test
    fun `a media Task with an add-on is unchanged by the page model`() {
        val item = item(part(assets = listOf(asset("a.jpg"), asset("b.jpg"))))

        assertEquals(
            listOf(
                ReviewPage.Asset("p1", asset("a.jpg"), assetIndex = 0),
                ReviewPage.Asset("p1", asset("b.jpg"), assetIndex = 1),
                ReviewPage.AddOn
            ),
            reviewPages(item, hasAddOn = true)
        )
    }

    @Test
    fun `the Asset index is read off the page list, not converted from the page number`() {
        // A Prompt with a body and two files has pages 0=Content, 1=a, 2=b. AssetRun counts Assets,
        // so it must be handed 0..1 rather than 1..2. The page carries that index now, because a
        // Task of several parts has no single offset a boolean could name.
        val prompt = item(
            part(payloadKind = "profile", assets = listOf(asset("a.jpg"), asset("b.jpg")))
        )
        val atPrompt = { page: Int -> taskPageState(prompt, addOn("Notes"), requestedPage = page) }

        assertEquals(-1, atPrompt(0).assetIndex)
        assertEquals(0, atPrompt(1).assetIndex)
        assertEquals(1, atPrompt(2).assetIndex)
        // The add-on sentinel is the Asset page count.
        assertEquals(2, atPrompt(3).assetIndex)
        // A media Task has no offset.
        val media = item(part(assets = listOf(asset("a.jpg"), asset("b.jpg"))))
        assertEquals(1, taskPageState(media, addOn = null, requestedPage = 1).assetIndex)
    }

    @Test
    fun `the page state names the Asset's page back, and the pair round-trips`() {
        // The page list is the authority now: a surface that writes back a file chosen within
        // AssetRun has to land where the arrows would have walked to, and TaskPageState answers
        // that from the Asset index the page itself carries.
        val item = item(
            part(payloadKind = "profile", assets = listOf(asset("a.jpg"), asset("b.jpg")))
        )
        val at = { page: Int -> taskPageState(item, addOn("Notes"), requestedPage = page) }

        assertEquals(1, at(0).pageOfAssetIndex(0))
        assertEquals(3, at(3).pageOfAssetIndex(2))
        assertEquals(0, at(0).pageOfAssetIndex(-1))

        for (page in 0..3) {
            val state = at(page)
            assertEquals(page, state.pageOfAssetIndex(state.assetIndex))
        }
        for (assetIndex in 0..1) {
            assertEquals(assetIndex, at(at(0).pageOfAssetIndex(assetIndex)).assetIndex)
        }

        // A media Task has no Content offset.
        val media = item(part(assets = listOf(asset("a.jpg"))))
        assertEquals(0, taskPageState(media, addOn = null, requestedPage = 0).pageOfAssetIndex(0))
    }

    @Test
    fun `a committing answer lands on the next decision unit before the queue moves on`() {
        // A Prompt with no files: pages 0=Content, 1=Add-on. Liking it used to advance the queue
        // and leave the questionnaire unseen.
        val prompt = item(part(payloadKind = "profile"))
        assertEquals(
            CommitArrival.Page(1),
            commitArrival(
                advanceAfterCommit = true,
                pages = reviewPages(prompt, hasAddOn = true),
                currentPage = 0
            )
        )
        // A media Task judged from its second file (of three) still reaches the last page, because
        // the add-on belongs to the Task and not to any one file.
        val media = item(part(assets = listOf(asset("a.jpg"), asset("b.jpg"), asset("c.jpg"))))
        assertEquals(
            CommitArrival.Page(3),
            commitArrival(
                advanceAfterCommit = true,
                pages = reviewPages(media, hasAddOn = true),
                currentPage = 1
            )
        )
        // Two Prompts and an add-on: 0=first question, 1=second question, 2=Add-on. Answering one
        // part shows the next part's question rather than the whole Task's questionnaire, and the
        // last question is what reaches the add-on.
        val twoPrompts = item(
            part(key = "p1", payloadKind = "profile"),
            part(key = "p2", payloadKind = "vocabulary")
        )
        val twoPromptPages = reviewPages(twoPrompts, hasAddOn = true)
        assertEquals(
            CommitArrival.Page(1),
            commitArrival(advanceAfterCommit = true, pages = twoPromptPages, currentPage = 0)
        )
        assertEquals(
            CommitArrival.Page(2),
            commitArrival(advanceAfterCommit = true, pages = twoPromptPages, currentPage = 1)
        )
    }

    @Test
    fun `a commit already on the add-on page advances the queue`() {
        val item = item(part(assets = listOf(asset("a.jpg"), asset("b.jpg"), asset("c.jpg"))))

        assertEquals(
            CommitArrival.NextTask,
            commitArrival(
                advanceAfterCommit = true,
                pages = reviewPages(item, hasAddOn = true),
                currentPage = 3
            )
        )
    }

    @Test
    fun `a Task with no add-on advances straight away`() {
        val item = item(part(payloadKind = "profile"))

        assertEquals(
            CommitArrival.NextTask,
            commitArrival(
                advanceAfterCommit = true,
                pages = reviewPages(item, hasAddOn = false),
                currentPage = 0
            )
        )
    }

    @Test
    fun `a template that does not advance stays on the page`() {
        val item = item(part(payloadKind = "profile"))

        assertEquals(
            null,
            commitArrival(
                advanceAfterCommit = false,
                pages = reviewPages(item, hasAddOn = true),
                currentPage = 0
            )
        )
    }

    @Test
    fun `the guide names the Content page Question rather than borrowing a file's name`() {
        val entries = reviewPageEntries(
            parts = listOf(part(payloadKind = "profile", assets = listOf(asset("a.jpg")))),
            addOn = addOn("Notes")
        )

        assertEquals(listOf("Question", "a.jpg", "add-on Notes"), entries.map { it.label })
    }

    @Test
    fun `the page state gives every surface the same count and Asset index`() {
        // A Prompt with two files and an add-on: 0=Content, 1=a, 2=b, 3=Add-on. AssetRun counts
        // Assets, so the Content page is -1 and the add-on is the sentinel Asset page count.
        val item = item(
            part(payloadKind = "profile", assets = listOf(asset("a.jpg"), asset("b.jpg")))
        )
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
        val item = item(part(payloadKind = "profile", assets = listOf(asset("a.jpg"))))

        assertEquals(0, taskPageState(item, addOn = null, requestedPage = -5).pageIndex)
        assertEquals(1, taskPageState(item, addOn = null, requestedPage = 99).pageIndex)
        assertEquals(
            ReviewPage.Asset("p1", asset("a.jpg"), assetIndex = 0),
            taskPageState(item, addOn = null, requestedPage = 99).current
        )
    }

    @Test
    fun `a Task with no add-on keeps the pages it always had`() {
        val item = item(part(assets = listOf(asset("a.jpg"))))

        val state = taskPageState(item, addOn = null, requestedPage = 0)

        assertEquals(1, state.pageCount)
        assertEquals(0, state.assetIndex)
        assertFalse(state.hasPageLevel)
        assertFalse(state.hasAddOnPage)
        assertFalse(state.hasContentPage)
    }

    @Test
    fun `a Prompt with no files still counts the add-on as its last page`() {
        val item = item(part(payloadKind = "profile"))

        val state = taskPageState(item, addOn("Notes"), requestedPage = 1)

        assertEquals(2, state.pageCount)
        assertTrue(state.onAddOnPage)
        // assetPageCount == 0, which is the sentinel AssetRun recognises as the add-on page.
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

    private fun item(vararg parts: ReviewPart) = ReviewItem(
        id = 0L,
        taskId = "task-1",
        publicId = "",
        title = "a.jpg",
        sourceUri = null,
        relativePath = "a.jpg",
        sourceMissing = false,
        target = DecisionTarget.File(rootUri = "content://root", relativePath = "a.jpg"),
        parts = parts.toList()
    )

    private fun part(
        key: String = "p1",
        position: Int = 0,
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
