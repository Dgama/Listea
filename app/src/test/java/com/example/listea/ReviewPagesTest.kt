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
    fun `the guide names the Content page Prompt rather than borrowing a file's name`() {
        val entries = reviewPageEntries(
            assets = listOf(asset("a.jpg")),
            addOn = addOn("Notes"),
            content = true
        )

        assertEquals(listOf("Prompt", "a.jpg", "add-on Notes"), entries.map { it.label })
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
