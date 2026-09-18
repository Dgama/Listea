package com.example.listea

import com.example.listea.data.DecisionTarget
import com.example.listea.data.ReviewItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where the explicit previous/next controls can go, which is the whole of what they do.
 *
 * §7 makes navigation pure: a step changes which Task is in front of the user and moves the List's
 * resume cursor, and it does not complete a Task, append an event, finish a round or deliver
 * anything. That "and nothing else" is a property of the step itself rather than of one control,
 * which is why the boundary is a function here and not a condition written into the bar.
 *
 * The one consequence worth pinning hardest is the forward end. If stepping could run past the
 * last Task, arriving there would be a second way to end a round, and the round would end without
 * the last Task ever being judged. It cannot: the last Task has no next.
 */
class TaskNavigationTest {

    // --- the ends ----------------------------------------------------------------------------------

    @Test
    fun `the first Task has no previous and steps forward to the second`() {
        val items = listOf(item(1), item(2), item(3))

        val nav = taskNavigation(items, 0)

        assertEquals(false, nav.canGoPrevious)
        assertNull(nav.previous)
        assertEquals(items[1], nav.next)
    }

    @Test
    fun `the last Task has no next, so arriving at it never ends the round`() {
        // The completion screen is reached by *completing* the last Task, never by walking to it.
        // A next here would be that second route, and it would end a round nobody finished.
        val items = listOf(item(1), item(2), item(3))

        val nav = taskNavigation(items, items.lastIndex)

        assertEquals(false, nav.canGoNext)
        assertNull(nav.next)
        assertEquals(items[1], nav.previous)
    }

    @Test
    fun `a round of one Task can be stepped neither way`() {
        // The `1 / 1` case, and the reason both controls are drawn disabled rather than dropped:
        // there is a queue, it simply has nowhere to go from here.
        val nav = taskNavigation(listOf(item(1)), 0)

        assertEquals(false, nav.canGoPrevious)
        assertEquals(false, nav.canGoNext)
    }

    @Test
    fun `an empty queue has nowhere to step`() {
        // Not reachable from the session, which never gets past an empty round — but the honest
        // answer for "the Task before the zeroth of none" is that there is not one, rather than a
        // crash on the way to finding out.
        val nav = taskNavigation(emptyList(), 0)

        assertEquals(false, nav.canGoPrevious)
        assertEquals(false, nav.canGoNext)
    }

    @Test
    fun `an index outside the queue steps nowhere rather than to the wrong Task`() {
        val items = listOf(item(1), item(2))

        assertEquals(false, taskNavigation(items, -1).canGoNext)
        assertEquals(false, taskNavigation(items, 5).canGoPrevious)
        assertNull(taskNavigation(items, 5).next)
    }

    // --- the middle ---------------------------------------------------------------------------------

    @Test
    fun `a Task in the middle steps to the ones beside it and no further`() {
        val items = listOf(item(1), item(2), item(3), item(4))

        val nav = taskNavigation(items, 2)

        // Adjacency, not order: the neighbours are the Tasks either side of this one, which is
        // what makes repeated steps walk the queue rather than jump around it.
        assertEquals(items[1], nav.previous)
        assertEquals(items[3], nav.next)
    }

    @Test
    fun `stepping is by position, so two Tasks sharing a title are still stepped between`() {
        // A step is a move in the queue and not a lookup, so nothing about the Tasks themselves —
        // not their titles, not their ids — can decide where a step lands. Two entries that look
        // identical are two positions, and both are reachable.
        val items = listOf(item(1, "same.jpg"), item(2, "same.jpg"))

        assertEquals(items[1], taskNavigation(items, 0).next)
        assertEquals(items[0], taskNavigation(items, 1).previous)
    }

    // --- helpers -----------------------------------------------------------------------------------

    private fun item(id: Long, title: String = "file-$id.jpg") = ReviewItem(
        id = id,
        taskId = "task-$id",
        publicId = "ITEM-$id",
        title = title,
        sourceUri = "content://root/$title",
        relativePath = title,
        sourceMissing = false,
        target = DecisionTarget.File("content://root", title),
        rowId = id
    )
}
