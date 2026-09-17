package com.example.listea

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The decision table behind Create List's two confirmation gates, pinned directly rather than
 * through the ViewModel it drives.
 *
 * v0.2.5's first implementation folded these two questions into one `if`, and the branch that
 * asked for import consent returned before ever checking for a replace conflict - so accepting an
 * import silently deleted the Lists it overlapped, with no dialog ever having named them. That
 * defect lived one level above the DAO, where nothing was tested; this is the regression test for
 * it, asserted on the table itself so it cannot regress by the control flow changing shape again.
 */
class FolderListGateTest {

    @Test
    fun `un-imported material with an overlapping List asks for import consent, never replaces first`() {
        assertEquals(
            FolderListStep.NEEDS_IMPORT_CONSENT,
            folderListStep(isImported = false, hasConflicts = true)
        )
    }

    @Test
    fun `un-imported material with nothing to replace still asks for import consent`() {
        assertEquals(
            FolderListStep.NEEDS_IMPORT_CONSENT,
            folderListStep(isImported = false, hasConflicts = false)
        )
    }

    @Test
    fun `imported material that would replace a List asks for replace confirmation`() {
        assertEquals(
            FolderListStep.NEEDS_REPLACE_CONFIRM,
            folderListStep(isImported = true, hasConflicts = true)
        )
    }

    @Test
    fun `imported material with nothing to replace commits with no dialog`() {
        assertEquals(
            FolderListStep.COMMIT,
            folderListStep(isImported = true, hasConflicts = false)
        )
    }

    // The case v0.2.5 shipped broken: accepting import consent on a folder that also overlaps an
    // existing List must land on the replace confirmation next, not on a direct commit - otherwise
    // accepting "import this" is silently accepted as "and delete that", which named nothing.
    @Test
    fun `re-entering the table after import consent is accepted still catches a replace conflict`() {
        val firstStep = folderListStep(isImported = false, hasConflicts = true)
        assertEquals(FolderListStep.NEEDS_IMPORT_CONSENT, firstStep)

        val afterAccepting = folderListStep(isImported = true, hasConflicts = true)
        assertEquals(FolderListStep.NEEDS_REPLACE_CONFIRM, afterAccepting)
    }
}
