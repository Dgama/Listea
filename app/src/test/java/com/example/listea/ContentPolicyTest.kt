package com.example.listea

import com.example.listea.data.AssetEntity
import com.example.listea.data.TaskImportEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tier a run applies to a Task, and the facts it is applied to.
 *
 * Two layers, and the second is the one worth having a test for. [decideContentDecision] is four
 * lines of arithmetic over counts; [taskChanges] is the *reading* — which of a Task's Assets count
 * as changed, and which absences are not news — and every one of its rules is a decision somebody
 * could get wrong in a way no compile error would catch.
 *
 * Pure JVM, in the shape `FolderTypeTest` and `QuickReviewQueueTest` establish: no Android, no
 * database, no clock. The DAO reads what this says; it does not decide anything itself.
 */
class ContentPolicyTest {

    // --- how a Todo stores its answer -----------------------------------------------------------------

    @Test
    fun `every policy survives its own key`() {
        for (policy in ContentPolicy.entries) {
            assertEquals(policy, ContentPolicy.fromKey(policy.key))
        }
    }

    @Test
    fun `a Todo that never answered, or answered unreadably, reads back as the default`() {
        // Two different installs in one assertion, and both are the reason the fallback is `keep`
        // rather than a throw. The first is every Todo that existed before this version, whose row
        // has no value to read; the second is a value written by a build that had a tier this one
        // does not. `docs/settings.md` is the rule that makes the answer the same for both: a
        // control added after a feature shipped starts where an untouched install already was, and
        // an untouched install changed no Task's state on a re-import.
        assertEquals(ContentPolicy.KEEP, ContentPolicy.fromKey(null))
        assertEquals(ContentPolicy.KEEP, ContentPolicy.fromKey(""))
        assertEquals(ContentPolicy.KEEP, ContentPolicy.fromKey("KEEP"))
        assertEquals(ContentPolicy.KEEP, ContentPolicy.fromKey("replace"))
        // And the default is the same value the fallback is, so the two cannot drift apart.
        assertEquals(ContentPolicy.KEEP, ContentPolicy.Default)
    }

    @Test
    fun `each tier is named by the words the chooser shows, and no two alike`() {
        // The labels live on the enum because two screens read them: the import dialog that asks
        // the question, and the Todo card that reports which answer is in force. Before that they
        // were literals in the chooser, where the card could not reach them — so this pins the
        // words themselves, and pins them apart, because a card that named two tiers the same way
        // could not say which one a Todo is on.
        assertEquals("Keep it", ContentPolicy.KEEP.label)
        assertEquals("Start it over", ContentPolicy.RESET.label)
        assertEquals("Replace the Task", ContentPolicy.DROP.label)
        assertEquals(
            ContentPolicy.entries.size,
            ContentPolicy.entries.map { it.label }.distinct().size
        )
    }

    // --- the tier ---------------------------------------------------------------------------------

    @Test
    fun `a Task nothing changed about is kept, whatever the Todo said`() {
        // The first rule rather than a case at the bottom, and the one that keeps `reset` from
        // being a switch that clears every judgment on every refresh: all three tiers describe what
        // a *change* means, so a run that finds the material as it left it has nothing to answer.
        for (policy in ContentPolicy.entries) {
            for (count in 0..4) {
                assertEquals(
                    "policy $policy with $count Assets and no change",
                    ContentDecision.KEEP,
                    decideContentDecision(policy, added = 0, missing = 0, assetCount = count)
                )
            }
        }
    }

    @Test
    fun `keep never clears or replaces, however much changed`() {
        for (added in 0..3) {
            for (missing in 0..3) {
                if (added == 0 && missing == 0) continue
                assertEquals(
                    ContentDecision.KEEP,
                    decideContentDecision(ContentPolicy.KEEP, added, missing, assetCount = 3)
                )
            }
        }
    }

    @Test
    fun `reset clears on any change, and reset is what a single-Asset Task drops to`() {
        assertEquals(
            ContentDecision.RESET,
            decideContentDecision(ContentPolicy.RESET, added = 1, missing = 0, assetCount = 3)
        )
        assertEquals(
            ContentDecision.RESET,
            decideContentDecision(ContentPolicy.RESET, added = 0, missing = 1, assetCount = 1)
        )
        // The one place the count is read, and it is reading it to choose between two ways of doing
        // what the user already asked for: a file-as-Task Task *is* its file, so replacing it would
        // mint a Task holding the same single Asset under a new id, with the judgment and the List
        // memberships gone. Loss with nothing bought.
        assertEquals(
            ContentDecision.RESET,
            decideContentDecision(ContentPolicy.DROP, added = 0, missing = 1, assetCount = 1)
        )
    }

    @Test
    fun `drop replaces a group, and only a group`() {
        assertEquals(
            ContentDecision.DROP,
            decideContentDecision(ContentPolicy.DROP, added = 1, missing = 0, assetCount = 2)
        )
        assertEquals(
            ContentDecision.DROP,
            decideContentDecision(ContentPolicy.DROP, added = 0, missing = 2, assetCount = 2)
        )
    }

    @Test
    fun `a Task with no Assets left is never deleted for having none`() {
        // The pin on the architecture's own rule. A count of zero means there is nothing to have
        // changed — `missing` counts the Assets a Task holds, so it cannot be positive when it
        // holds none — and a run must never reach "this Task has no material, therefore it is
        // gone". A Task left with nothing is a Task that is missing its material, and it lives.
        for (policy in ContentPolicy.entries) {
            assertEquals(
                ContentDecision.KEEP,
                decideContentDecision(policy, added = 0, missing = 0, assetCount = 0)
            )
        }
    }

    @Test
    fun `a Task that lost every Asset is a changed group, not an empty one`() {
        // The distinction the rule above turns on. `assetCount` is the count as the run finds it,
        // which for a folder-as-Task group whose files have all gone is still 2 — so a `drop` Todo
        // really does replace it, and the zero it is heading towards is the outcome rather than the
        // reason.
        assertEquals(
            ContentDecision.DROP,
            decideContentDecision(ContentPolicy.DROP, added = 0, missing = 2, assetCount = 2)
        )
    }

    // --- reading the change -----------------------------------------------------------------------

    @Test
    fun `a Task whose Assets are all still planned has not changed`() {
        val changes = taskChanges(
            coverage = "photos",
            imports = listOf(import("t1", "a.jpg")),
            assets = listOf(asset("t1", "photos/a.jpg")),
            planned = listOf(task("a.jpg", "photos/a.jpg")),
            plannedPaths = setOf("photos/a.jpg")
        )

        assertEquals(1, changes.size)
        assertFalse(changes.single().hasChange)
    }

    @Test
    fun `a file the scan no longer names is missing`() {
        val changes = taskChanges(
            coverage = "photos",
            imports = listOf(import("t1", "a.jpg")),
            assets = listOf(asset("t1", "photos/a.jpg"), asset("t1", "photos/b.jpg")),
            planned = listOf(task("a.jpg", "photos/a.jpg")),
            plannedPaths = setOf("photos/a.jpg")
        )

        assertEquals(1, changes.single().missing)
        assertEquals(0, changes.single().added)
        assertEquals(2, changes.single().assetCount)
    }

    @Test
    fun `an Asset the scan did not look at is not missing`() {
        // The coverage rule, and the reason it is a parameter rather than inferred: a scoped refresh
        // of one folder says nothing about the Assets a Task holds outside it, and treating their
        // absence from the plan as a disappearance would hand the user a policy question about
        // material nobody looked at.
        val changes = taskChanges(
            coverage = "photos/holiday",
            imports = listOf(import("t1", "a.jpg")),
            assets = listOf(asset("t1", "photos/a.jpg")),
            planned = emptyList(),
            plannedPaths = emptySet()
        )

        assertEquals(0, changes.single().missing)
        assertFalse(changes.single().hasChange)
    }

    @Test
    fun `an Asset already flagged missing is not news`() {
        // Without this, a `reset` Todo would clear the same Tasks' judgments on every refresh, and
        // a `drop` Todo would replace them on every refresh — a switch that keeps doing something
        // long after the thing it was about has been settled.
        val changes = taskChanges(
            coverage = "photos",
            imports = listOf(import("t1", "a.jpg")),
            assets = listOf(asset("t1", "photos/a.jpg", sourceMissing = true)),
            planned = emptyList(),
            plannedPaths = emptySet()
        )

        assertFalse(changes.single().hasChange)
    }

    @Test
    fun `a file that moved to another Task has not gone missing`() {
        // `plannedPaths` is every path the plan names across the whole Todo, and the width is the
        // point: a file the plan still makes somewhere is a regrouping, which the Source settles as
        // its own contradiction, and calling it a disappearance here would be this layer answering
        // a question that is not its own.
        val changes = taskChanges(
            coverage = "photos",
            imports = listOf(import("t1", "a.jpg")),
            assets = listOf(asset("t1", "photos/a.jpg")),
            planned = listOf(task("group", "photos/b.jpg")),
            plannedPaths = setOf("photos/a.jpg", "photos/b.jpg")
        )

        assertEquals(0, changes.single().missing)
    }

    @Test
    fun `an Asset with no path is left out of both counts`() {
        // A null `relativePath` is an Asset this build cannot place. Counting it as missing would be
        // acting on a guess about a file nobody can name.
        val changes = taskChanges(
            coverage = "photos",
            imports = listOf(import("t1", "a.jpg")),
            assets = listOf(asset("t1", null)),
            planned = emptyList(),
            plannedPaths = emptySet()
        )

        assertEquals(0, changes.single().missing)
        assertEquals(0, changes.single().assetCount)
        assertFalse(changes.single().hasChange)
    }

    @Test
    fun `a file the plan added to an existing Task counts as added`() {
        val changes = taskChanges(
            coverage = "photos",
            imports = listOf(import("t1", "a.jpg")),
            assets = listOf(asset("t1", "photos/a.jpg")),
            planned = listOf(task("a.jpg", "photos/a.jpg", "photos/b.jpg")),
            plannedPaths = setOf("photos/a.jpg", "photos/b.jpg")
        )

        assertEquals(1, changes.single().added)
        assertEquals(0, changes.single().missing)
    }

    @Test
    fun `a Task whose content has all gone is a change with nothing planned`() {
        val changes = taskChanges(
            coverage = "photos",
            imports = listOf(import("t1", "a.jpg")),
            assets = listOf(asset("t1", "photos/a.jpg")),
            planned = emptyList(),
            plannedPaths = emptySet()
        )

        assertEquals(null, changes.single().planned)
        assertTrue(changes.single().hasChange)
    }

    // --- the question a Todo that follows its upstream asks ---------------------------------------

    @Test
    fun `nothing changed means no run`() {
        val changes = taskChanges(
            coverage = "photos",
            imports = listOf(import("t1", "a.jpg")),
            assets = listOf(asset("t1", "photos/a.jpg")),
            planned = listOf(task("a.jpg", "photos/a.jpg")),
            plannedPaths = setOf("photos/a.jpg")
        )

        assertFalse(hasSourceChanges(changes, planned = listOf(task("a.jpg", "photos/a.jpg"))))
    }

    @Test
    fun `a Task the plan makes and the Todo does not hold is a change`() {
        // The half a per-Task comparison cannot see: a file that appeared is a planned Task with no
        // row, and it belongs to no Task the Todo has, so it is a change to the Todo and not to any
        // Task in it.
        val planned = listOf(task("a.jpg", "photos/a.jpg"), task("b.jpg", "photos/b.jpg"))
        val changes = taskChanges(
            coverage = "photos",
            imports = listOf(import("t1", "a.jpg")),
            assets = listOf(asset("t1", "photos/a.jpg")),
            planned = planned,
            plannedPaths = setOf("photos/a.jpg", "photos/b.jpg")
        )

        assertTrue(hasSourceChanges(changes, planned))
    }

    @Test
    fun `a Todo holding nothing is a change when the scan found something`() {
        // The degenerate read, and it is on the path a Todo takes the first time it is entered with
        // following switched on: no provenance rows at all, and a scan that found material.
        val planned = listOf(task("a.jpg", "photos/a.jpg"))
        assertTrue(hasSourceChanges(emptyList(), planned))
        assertFalse(hasSourceChanges(emptyList(), emptyList()))
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private fun import(taskId: String, groupPath: String) = TaskImportEntity(
        taskId = taskId,
        sourceId = "todo-1",
        groupPath = groupPath,
        displayName = groupPath,
        importedAt = 1000,
        lastSeenAt = 1000
    )

    private fun asset(taskId: String, path: String?, sourceMissing: Boolean = false) = AssetEntity(
        id = "$taskId-$path",
        taskId = taskId,
        sortOrder = 0,
        rootUri = "content://root",
        relativePath = path,
        sourceMissing = sourceMissing
    )

    /**
     * A planned Task as the local planners make one: a single degenerate part holding its files.
     *
     * A Task's Assets live on its parts since v0.8.4, and [taskChanges] counts what a plan adds by
     * walking them, so a fixture that still put the files at Task level would test a shape the
     * planner never produces.
     */
    private fun task(groupPath: String, vararg paths: String) = PlannedTask(
        groupPath = groupPath,
        displayName = groupPath,
        parts = listOf(
            PlannedPart(
                partKey = "local:0",
                position = 0,
                origin = PartOrigin.FILE,
                assets = paths.mapIndexed { index, path ->
                    PlannedAsset(
                        rootRelativePath = path,
                        name = path.substringAfterLast('/'),
                        uri = "content://root/$path",
                        sizeBytes = null,
                        lastModified = null,
                        sortOrder = index
                    )
                }
            )
        )
    )
}
