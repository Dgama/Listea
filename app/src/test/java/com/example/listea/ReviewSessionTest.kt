package com.example.listea

import androidx.compose.runtime.State
import com.example.listea.data.DecisionTarget
import com.example.listea.data.ReviewDecisions
import com.example.listea.data.ReviewItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a round starts, and what leaving it reports.
 *
 * The other two halves of a session are already pinned elsewhere — which items a round contains by
 * `ListArrangeTest` and `QuickReviewQueueTest`, and what the snapshot holds once it is taken by
 * `ReviewRoundTest` — so neither is repeated here.
 *
 * The two vocabularies meet here and only here. What the session holds is a task id; what the List
 * remembers about it is a row id; and these tests are where a caller can see that the first is
 * derived from the second without either becoming the other.
 */
class ReviewSessionTest {

    // --- where a round starts ----------------------------------------------------------------------

    @Test
    fun `a remembered position wins even when that item is already checked`() {
        val items = listOf(item(1, "a.jpg"), item(2, "b.jpg", completed = true))

        // Resuming means resuming: the item the user was looking at is where they come back to,
        // not the first one they happen not to have done yet.
        assertEquals("task-2", resumeTaskId(2, items))
    }

    @Test
    fun `without a position a round starts at the first unchecked item`() {
        val items = listOf(
            item(1, "a.jpg", completed = true),
            item(2, "b.jpg", completed = true),
            item(3, "c.jpg")
        )

        assertEquals("task-3", resumeTaskId(null, items))
    }

    @Test
    fun `a round where everything is already checked starts at the very first item`() {
        val items = listOf(item(1, "a.jpg", completed = true), item(2, "b.jpg", completed = true))

        assertEquals("task-1", resumeTaskId(null, items))
    }

    @Test
    fun `a position pointing at an item that is gone falls back`() {
        val items = listOf(item(1, "a.jpg", completed = true), item(2, "b.jpg"))

        // Re-sync, cleanup and manual deletes all shift positions, so a stale id simply falls back.
        assertEquals("task-2", resumeTaskId(99, items))
    }

    @Test
    fun `resuming reads the row id and not the task id`() {
        // The two spaces are numbered differently on purpose — a row is 7 while its Task is
        // "task-7" — so a cursor that had started being read as a task id would land on the wrong
        // row, or on none, and the fallback would cover it up rather than fail.
        val items = listOf(item(1, "a.jpg"), item(7, "b.jpg"), item(9, "c.jpg"))

        assertEquals("task-7", resumeTaskId(7, items))
    }

    // --- leaving -----------------------------------------------------------------------------------

    @Test
    fun `leaving hands the round's own queue to the report`() {
        var reported: List<Long>? = null
        val queue = listOf(item(1, "a.jpg"), item(2, "b.jpg"))

        // The queue, not the list: a narrowed round reports what it walked.
        leaveReview(queue, FakeDelivered(false), { reported = it.map { it.id } }, {})()

        assertEquals(listOf(1L, 2L), reported)
    }

    @Test
    fun `a round that finished on screen is not reported again on the way out`() {
        var reports = 0

        // Reaching the end of the queue reports it there and then; this is the exit that follows,
        // and a second report of the same round is the thing the guard exists to stop.
        leaveReview(listOf(item(1, "a.jpg")), FakeDelivered(true), { reports++ }, {})()

        assertEquals(0, reports)
    }

    @Test
    fun `an empty round reports nothing, because nothing happened`() {
        var reports = 0

        leaveReview(emptyList(), FakeDelivered(false), { reports++ }, {})()

        assertEquals(0, reports)
    }

    @Test
    fun `leaving always goes back, whether or not anything was reported`() {
        var back = 0

        leaveReview(listOf(item(1, "a.jpg")), FakeDelivered(false), {}, { back++ })()
        leaveReview(emptyList(), FakeDelivered(false), {}, { back++ })()

        assertEquals(2, back)
    }

    // --- helpers -----------------------------------------------------------------------------------

    /** The session's "already delivered" flag, without a Compose host to hold it. */
    private class FakeDelivered(initial: Boolean) : State<Boolean> {
        override var value: Boolean = initial
    }

    private fun item(id: Long, title: String, completed: Boolean = false) = ReviewItem(
        id = id,
        taskId = "task-$id",
        publicId = "ITEM-$id",
        title = title,
        sourceUri = "content://root/$title",
        relativePath = title,
        sourceMissing = false,
        target = DecisionTarget.File("content://root", title),
        decisions = ReviewDecisions(isCompleted = completed),
        rowId = id
    )
}
