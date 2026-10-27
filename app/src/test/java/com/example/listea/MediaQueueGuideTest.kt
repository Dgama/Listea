package com.example.listea

import com.example.listea.data.DecisionTarget
import com.example.listea.data.ReviewAsset
import com.example.listea.data.ReviewItem
import com.example.listea.data.ReviewPart
import com.example.listea.domain.AddOnId
import com.example.listea.domain.AddOnVersionRef
import com.example.listea.domain.StoredAddOnVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the queue guide says when the queue is more than one level.
 *
 * The outer level is the round's Tasks and the level below each row is that Task's pages. Both have
 * to be the same sequences the arrows walk, because a jump is only a shortcut through them: the
 * outer arrows land on a Task's first page, and the inner arrows walk the page list the guide
 * draws. A Task with one page is a leaf — an arrow into a list of one would be a level that is not
 * a level.
 */
class MediaQueueGuideTest {

    @Test
    fun `each Task row carries its own pages, in the round's order`() {
        val items = listOf(item(1, "camera"), item(2, "receipts"))
        val pages = mapOf(
            "task-1" to listOf(asset("photo-1.jpg"), asset("clip.mp4")),
            "task-2" to listOf(asset("scan-1.png"), asset("scan-2.png"), asset("scan-3.png"))
        )

        val entries = reviewQueueEntries(items) { queued ->
            reviewPageEntries(listOf(part(pages.getValue(queued.taskId))), addOn = null)
        }

        assertEquals(listOf("camera", "receipts"), entries.map { it.label })
        assertEquals(
            listOf(listOf("photo-1.jpg", "clip.mp4"), listOf("scan-1.png", "scan-2.png", "scan-3.png")),
            entries.map { entry -> entry.pages.map { it.label } }
        )
    }

    @Test
    fun `the add-on page is part of the Task it belongs to`() {
        val items = listOf(item(1, "camera"), item(2, "receipts"))

        val entries = reviewQueueEntries(items) { queued ->
            val addOn = if (queued.taskId == "task-2") addOn("Field notes") else null
            reviewPageEntries(
                listOf(part(listOf(asset("scan-1.png"), asset("scan-2.png")))),
                addOn
            )
        }

        assertTrue("a Task without an add-on has no page for one", entries[0].pages.none { it.label.startsWith("add-on") })
        assertEquals(
            listOf("scan-1.png", "scan-2.png", "add-on Field notes"),
            entries[1].pages.map { it.label }
        )
    }

    @Test
    fun `a Task with a single page collapses to a leaf`() {
        val items = listOf(item(1, "one-file"), item(2, "many-files"))

        val entries = reviewQueueEntries(items) { queued ->
            when (queued.taskId) {
                "task-1" -> listOf(MediaQueueEntry("only.jpg"))
                else -> listOf(MediaQueueEntry("a.jpg"), MediaQueueEntry("b.jpg"))
            }
        }

        assertEquals(emptyList<MediaQueueEntry>(), entries[0].pages)
        assertEquals(2, entries[1].pages.size)
    }

    @Test
    fun `the path is the detail unless the title already says it`() {
        val entries = reviewQueueEntries(
            listOf(
                item(1, "Holiday", relativePath = "camera/Holiday"),
                item(2, "photo-2.jpg", relativePath = "photo-2.jpg"),
                item(3, "photo-3.jpg", relativePath = null, rootRelativePath = "trips/2026")
            )
        ) { emptyList() }

        assertEquals(listOf("camera/Holiday", null, "trips/2026"), entries.map { it.detail })
    }

    // --- helpers -----------------------------------------------------------------------------------

    private fun item(
        id: Long,
        title: String,
        relativePath: String? = title,
        rootRelativePath: String? = null
    ) = ReviewItem(
        id = id,
        taskId = "task-$id",
        publicId = "ITEM-$id",
        title = title,
        sourceUri = null,
        relativePath = relativePath,
        sourceMissing = false,
        target = DecisionTarget.File("content://root", title),
        rowId = id,
        rootRelativePath = rootRelativePath
    )

    // A media Task is one degenerate part: its files and no Viewer body.
    private fun part(assets: List<ReviewAsset>) = ReviewPart(
        partKey = "p1",
        position = 0,
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
