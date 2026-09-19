package com.example.listea

import com.example.listea.data.DecisionTarget
import com.example.listea.data.ReviewItem
import com.example.listea.domain.ExportChoice
import com.example.listea.domain.ExportKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bridge between a folder round's queue and the split — the one join no test was standing on.
 *
 * [ExportDispatchTest] holds the rule (*one round, more than one export*) and asserts it over rows
 * that already carry a Task id; the folder path asserts nothing at all, because a folder round's
 * rows are **minted** from [ReviewItem]s rather than read out of `list_items`. Everything between
 * those two facts was untested, and the release went out with it broken: the minting hard-coded
 * `taskId = ""`, so *every* item of *every* folder round reached `planExports` looking like a Task
 * no Todo had ever imported. A round over a *Txt* Todo posted to the webhook instead of writing a
 * file, and the same empty field left the items' `actions` unresolved, since the control is looked
 * up by the same id.
 *
 * Green suite, wrong app: the rule was right, the rows were wrong, and no test crossed the seam. It
 * is a plain JVM test because the seam is — [ReviewItem.toPayloadRow] is internal, pure, and needs no
 * Context, which is the point of lifting it out of the view model to here.
 */
class FolderRoundRowTest {

    /**
     * **The regression.** A folder round's row names the Task the card was of.
     *
     * Asserted first as the property itself, because it is the one the routing depends on and the
     * one that was false: the row is never inserted, so nothing enforced the field, and the KDoc at
     * the time reasoned only about the foreign key that never sees it.
     */
    @Test
    fun `a folder round's row carries the Task it was resolved from`() {
        val row = folderItem(taskId = "task-1").toPayloadRow(0)

        assertEquals("task-1", row.taskId)
        // Still not a membership, and this must not drift: a row with a list id would look
        // insertable, and the one thing a folder-resolved file must never become is a List member.
        assertEquals(0L, row.listId)
        assertEquals(0L, row.createdAt)
    }

    /**
     * And that is what lets the round split by Todo.
     *
     * The end-to-end version, over the two functions the dispatcher actually runs in sequence: the
     * lookup a caller hands [planExports] is keyed by the row's Task id — `getExportSources` in the
     * app, a map here — so a row that cannot answer it cannot be routed. This is the test that fails
     * on the shipped code and passes on the fixed one, and it fails in the shape the user reported:
     * *one group, no source, by WEBHOOK*, where the Todo said *Txt*.
     */
    @Test
    fun `a folder round splits by the Todos its Tasks came from`() {
        val rows = listOf(
            folderItem(taskId = "task-1").toPayloadRow(0),
            folderItem(taskId = "task-2").toPayloadRow(1),
            folderItem(taskId = "task-1", id = "task-1").toPayloadRow(2)
        )
        val byTask = mapOf(
            "task-1" to ExportSource("s1", "2026-09-11", ExportKind.TXT),
            "task-2" to ExportSource("s2", "Demo", ExportKind.WEBHOOK)
        )

        val groups = planExports(rows, listChoice = ExportChoice.Inherit) { byTask[it.taskId] }

        // Two Todos, so two exports — the plural case the release exists for, reached through a
        // folder rather than through a List.
        assertEquals(listOf(ExportKind.TXT, ExportKind.WEBHOOK), groups.map { it.kind })
        assertEquals(listOf("s1", "s2"), groups.map { it.sourceId })
        // The two cards of one Task travel together, and the group holds the cards rather than the
        // Tasks. A grouping that dropped the second-of-a-pair would still deliver one payload.
        assertEquals(listOf(2, 1), groups.map { it.items.size })
    }

    /**
     * A Task no Todo owns still falls to the default webhook, which is the rule and not the bug.
     *
     * The bug was that **everything** arrived here looking unowned. This is the same assertion as the
     * one above read the other way: the orphan branch is reached by an id that is genuinely absent
     * from the lookup, and the test that guards the fix must not have made the branch unreachable.
     */
    @Test
    fun `a Task no Todo owns is still the webhook's`() {
        val rows = listOf(folderItem(taskId = "stranger").toPayloadRow(0))

        val groups = planExports(rows, listChoice = ExportChoice.Inherit) { null }

        assertEquals(1, groups.size)
        assertEquals(ExportKind.WEBHOOK, groups.single().kind)
        assertEquals(null, groups.single().sourceId)
    }

    /** One card as a folder review resolves it: a file, no membership row, a real Task behind it. */
    private fun folderItem(taskId: String, id: String = "row-$taskId") = ReviewItem(
        id = 0L,
        taskId = taskId,
        publicId = id,
        title = "$taskId.jpg",
        sourceUri = "content://root/doc/$taskId.jpg",
        relativePath = "2026-09-11/$taskId.jpg",
        sourceMissing = false,
        target = DecisionTarget.File(rootUri = "content://root", relativePath = "2026-09-11/$taskId.jpg")
    )
}
