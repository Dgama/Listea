package com.example.listea.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The properties §5.2 asks of a domain id, as far as a host test can reach them.
 *
 * One of them is not here and cannot be: "Task ID and Asset ID are never interchangeable" is a
 * property of the types, not of any value, so the test that would prove it is a program that fails
 * to compile. The compiler is the assertion — that is the entire reason these are six types rather
 * than one — and what is left here is what a value can be held to.
 *
 * The one that *is* worth stating loudly: these ids must not become the scheme
 * `com.example.listea.data.newItemPublicId` already is. That one is derived from the file's path
 * on purpose, so that two people reading a log can see which file an id is about. Deriving a Task's
 * identity that way is the single most plausible way to get this wrong, because it looks like
 * reuse rather than a mistake, and it would make a file's identity change when the file moves.
 */
class IdentityTest {

    @Test
    fun `ids generated together are never the same`() {
        val tasks = List(1000) { TaskId.new() }
        val assets = List(1000) { AssetId.new() }
        val events = List(1000) { ReviewEventId.new() }

        // Uniqueness is the whole of what generation promises, and it is what makes an id safe to
        // keep as a primary key without the database being asked to enforce it.
        assertEquals(1000, tasks.toSet().size)
        assertEquals(1000, assets.toSet().size)
        assertEquals(1000, events.toSet().size)
    }

    @Test
    fun `an id encodes nothing about the thing it names`() {
        val id = TaskId.new().value

        // Parsing as a UUID is the positive form of "there is no path, filename, row number or List
        // position in here". A scheme that folded any of those in would not produce one.
        assertEquals(id, UUID.fromString(id).toString())
    }

    @Test
    fun `a task and its asset are identified differently`() {
        val task = Task(id = TaskId.new(), createdAt = 1000)
        val asset = Asset(
            id = AssetId.new(),
            taskId = task.id,
            sortOrder = 0,
            source = SourceRef("content://root", "2026-07-11/a.jpg")
        )

        // The media workflow's one-Asset case, where it would be easiest and most tempting to let
        // the two share a value. They do not, and nothing downstream may assume they could.
        assertNotEquals(task.id.value, asset.id.value)
        assertEquals(task.id, asset.taskId)
    }

    @Test
    fun `the same value is the same identity, and can be keyed on`() {
        val id = TaskId.new()
        val states = mapOf(id to TaskState(taskId = id, updatedAt = 0))

        // What "a Task keeps its ID when reopened" reduces to: equality that survives a round trip
        // through storage, and a hash that agrees with it. Value classes get this from the value
        // they wrap, which is exactly why they wrap a String and nothing richer.
        assertEquals(TaskState(taskId = TaskId(id.value), updatedAt = 0), states[id])
        assertEquals(1, setOf(id, TaskId(id.value)).size)
    }

    @Test
    fun `the two identity spaces of a List are not one space`() {
        val list = ListId(7)
        val entry = ListEntryId(7)

        // Both are 7 and both name something about one List. They are not the same 7, which is the
        // confusion this pair exists to make unwritable — `removeItem(listId, itemId)` is two Longs
        // today and only careful reading keeps them apart.
        assertEquals(7L, list.value)
        assertEquals(7L, entry.value)
        assertEquals(ListId(7), list)
        assertEquals(ListEntryId(7), entry)
    }

    @Test
    fun `a template reference reads as one token and compares as its lineage and version`() {
        val viewer = Templates.MediaViewer.ref
        val review = Templates.MediaRapidReview.ref

        assertEquals("builtin.media.viewer@1", viewer.toString())
        assertNotEquals(viewer, review)

        // The pair is the identity: a version number alone names a definition in no particular
        // lineage, and two lineages both at version 1 is the ordinary case rather than the odd one.
        assertNotEquals(
            TemplateVersionRef(TemplateId("builtin.media.viewer"), 1),
            TemplateVersionRef(TemplateId("builtin.media.rapid-review"), 1)
        )
        assertTrue(viewer.version > 0)
    }
}
