package com.example.listea.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The release's two sentences, held against the source rather than the behaviour.
 *
 * "Only an import run may create a Task" is a property of where `INSERT INTO tasks` can be reached
 * from, which a runtime test cannot see — a mint that nothing happens to call is still a mint. So
 * the invariant is asserted on the DAO source itself: the single call to the Task insert lives in
 * `resolveNewTask`, reachable only from `runImportInternal`, and the lazy-minting entry points the
 * release was written to remove are gone.
 */
class ImportInvariantTest {

    // §12 step 10
    @Test
    fun `INSERT INTO tasks is reachable only from the import transaction`() {
        val source = File("src/main/java/com/example/listea/data/ListsDao.kt").readText()

        // The @Insert declaration is a method signature; the only *call* is the one inside
        // resolveNewTask, which runImportInternal alone reaches.
        assertEquals(1, Regex("insertTask\\(TaskEntity\\(").findAll(source).count())

        // The lazy minting the release removed no longer exists as an entry point.
        assertFalse(source.contains("ensureTasksForFiles"))
        assertFalse(source.contains("taskForContent"))

        // The import body and its Task-minting helper are still present, so the assertion above is
        // not passing merely because minting was deleted outright.
        assertTrue(source.contains("runImportInternal"))
        assertTrue(source.contains("resolveNewTask"))
    }
}
