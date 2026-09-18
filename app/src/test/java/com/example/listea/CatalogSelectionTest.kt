package com.example.listea

import com.example.listea.data.CatalogRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What picking a folder means, and what picking a Task under one means afterwards.
 *
 * The rules are here rather than in the composable because the interesting cases are not "does the
 * box tick" but "what happens when the two controls disagree" - a folder ticked and then emptied, a
 * Task taken back under a folder that is still held. Those are the ones a UI test would assert
 * through two taps and a scroll, and the ones a change to the screen would silently break.
 */
class CatalogSelectionTest {

    private fun row(taskId: String, groupPath: String) = CatalogRow(
        taskId = taskId,
        groupPath = groupPath,
        displayName = groupPath.substringAfterLast('/'),
        isCompleted = false,
        assetCount = 1,
        missingAssets = 0,
        sizeBytes = null,
        lastModified = null,
        hasUnviewableAsset = false
    )

    // A small tree, with `ab` beside `a` on purpose: prefix matching that is not segment-aware
    // would let `a` swallow `ab`, which is the mistake this fixture exists to catch.
    private val rows = listOf(
        row("t1", "a/one.jpg"),
        row("t2", "a/two.jpg"),
        row("t3", "a/deep/three.jpg"),
        row("t4", "ab/four.jpg"),
        row("t5", "five.jpg")
    )

    private fun CatalogSelection.covered() = taskIds(rows)

    @Test
    fun `a scope covers itself and everything below it`() {
        val picked = CatalogSelection(scopes = setOf("a"))
        assertEquals(setOf("t1", "t2", "t3"), picked.covered())
    }

    @Test
    fun `a scope named a does not cover a sibling named ab`() {
        assertFalse(catalogScopeContains("a", "ab/four.jpg"))
        assertTrue(catalogScopeContains("a", "a/one.jpg"))
        assertTrue(catalogScopeContains("a", "a/deep/three.jpg"))
    }

    @Test
    fun `the root scope covers the whole catalog`() {
        assertEquals(setOf("t1", "t2", "t3", "t4", "t5"), CatalogSelection(scopes = setOf("")).covered())
    }

    @Test
    fun `a Task is covered by its own id alone`() {
        assertEquals(setOf("t5"), CatalogSelection(tasks = setOf("t5")).covered())
    }

    @Test
    fun `ticking a folder selects every Task under it`() {
        val picked = CatalogSelection().toggleScope("a", rows)
        assertTrue(picked.isScopePicked("a"))
        assertEquals(setOf("t1", "t2", "t3"), picked.covered())
    }

    @Test
    fun `clearing a folder takes its Tasks with it`() {
        val picked = CatalogSelection(scopes = setOf("a")).toggleScope("a", rows)
        assertFalse(picked.isScopePicked("a"))
        assertTrue(picked.covered().isEmpty())
    }

    // The case the two-controls design exists for. Unticking one Task under a held folder must not
    // untick the folder - the user said "this folder", and then said "but not this one" - and the
    // Task must stay out when the folder's box is read back.
    @Test
    fun `unticking one Task under a held folder leaves the folder held but drops that Task`() {
        val held = CatalogSelection(scopes = setOf("a"))
        val after = held.toggleTask("t2", rows)

        assertTrue("the folder is still held", after.isScopePicked("a"))
        assertEquals(setOf("t1", "t3"), after.covered())
    }

    // And the converse: because the folder is still held, ticking the same Task again puts it back
    // rather than being swallowed by a stale exclusion.
    @Test
    fun `re-ticking a Task under a held folder restores it`() {
        val after = CatalogSelection(scopes = setOf("a")).toggleTask("t2", rows).toggleTask("t2", rows)
        assertEquals(setOf("t1", "t2", "t3"), after.covered())
    }

    // Clearing a folder has to drop the exclusions recorded under it. Keeping them would make a
    // folder that was ticked, emptied of one Task and cleared silently drop that Task again the
    // next time it was ticked - a decision the user made once, applied twice.
    @Test
    fun `clearing a folder drops the exclusions recorded under it`() {
        val emptied = CatalogSelection(scopes = setOf("a")).toggleTask("t2", rows)
        val cleared = emptied.toggleScope("a", rows)
        val reticked = cleared.toggleScope("a", rows)

        assertTrue(reticked.isScopePicked("a"))
        assertEquals(setOf("t1", "t2", "t3"), reticked.covered())
    }

    // Ticking a folder must not clear a Task the user picked by hand elsewhere, and must not lose
    // it when the folder is unticked again either.
    @Test
    fun `a Task picked by hand survives ticking and unticking a folder`() {
        val withHand = CatalogSelection().toggleTask("t5", rows)
        val withFolder = withHand.toggleScope("a", rows)
        assertEquals(setOf("t1", "t2", "t3", "t5"), withFolder.covered())

        val folderGone = withFolder.toggleScope("a", rows)
        assertEquals(setOf("t5"), folderGone.covered())
    }

    @Test
    fun `an empty selection covers nothing`() {
        assertTrue(CatalogSelection().covered().isEmpty())
        assertFalse(CatalogSelection().isScopePicked("a"))
    }
}
