package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.FolderSnapshot
import com.example.listea.ImporterKind
import com.example.listea.domain.Templates
import com.example.listea.plan
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A dissolved history, per file: what each of a Task's files gets back.
 *
 * This is the file v0.5.2 exists for. [HistoryRecoveryTest] covers *scope* — which Todo a judgment
 * reaches — and its Tasks hold one file each, so it cannot tell a per-file recovery from a Task-level
 * one that happens to look right. Here a Task holds several files, marked differently, and the claim
 * is that each of them comes back with its own record and nothing else: no `★` spreading from the
 * file that had one, no action ids landing on a neighbour, and no mark invented on a file the history
 * said nothing about.
 *
 * Every judgment is written and read through the seam a user's tap reaches — `setAssetAction`,
 * `setTaskCompleted`, and `getCatalogTasks`' per-Asset `decisions` — rather than by hand, because the
 * defect being repaired was precisely a disagreement between the surfaces about which row a mark
 * belonged to. A test that wrote the pairs itself could agree with the code and still be wrong about
 * the app.
 *
 * The other half is what a history *cannot* say. A payload from a build that wrote a shape this one
 * does not know, and a row from a release before this one whose marks were the Task's, both restore
 * the completion and leave every file alone — a partial restore rather than an invented one, and the
 * two are asserted apart so that "nothing was read" is never mistaken for "nothing to read".
 *
 * Everything here is real SQL through Room, for the reason [ListsDaoTest] gives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryRecordsTest {

    private lateinit var database: ListeaDatabase
    private lateinit var dao: ListsDao

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(context, ListeaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.listsDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    // --- what each file gets back ------------------------------------------------------------------

    @Test
    fun `each file comes back with what was marked on it, and only that`() = runTest {
        val todo = importFolder("photos", DIRECTORY, THREE_FILES)
        // Three files and three different answers: one carries both a `★` and an action id, one an
        // action id alone, one nothing at all. A recovery that folded the Task's answers into one
        // — a single flag beside one concatenated id list, which is what v0.5.1's history did —
        // would hand all three the same thing, and this is the fixture that says so.
        mark(todo, "alice/a.jpg", ItemAction.Favourite, ItemAction("custom1"))
        mark(todo, "alice/b.jpg", ItemAction("custom2"))
        dao.setTaskCompleted(taskOf(todo).taskId, completed = true, now = 1500)

        dao.deleteTodo(todo, keepState = true, now = 5000)
        reimport("photos", DIRECTORY, THREE_FILES, recoverFrom = todo, now = 6000)

        val recovered = taskOf(sourceOf("photos"))
        assertEquals(true, recovered.isCompleted)
        assertEquals(
            mapOf(
                "a.jpg" to AssetDecisions(setOf("custom1", Templates.FAVOURITE_BUTTON_ID)),
                "b.jpg" to AssetDecisions(setOf("custom2")),
                "c.jpg" to AssetDecisions()
            ),
            recovered.assets.associate { it.name to it.decisions }
        )
    }

    @Test
    fun `a file the new import did not produce recovers nothing, and does not land on the file beside it`() =
        runTest {
            val todo = importFolder("photos", DIRECTORY, listOf("alice/a.jpg", "alice/b.jpg"))
            mark(todo, "alice/a.jpg", ItemAction("custom1"))
            mark(todo, "alice/b.jpg", ItemAction("custom2"))
            dao.setTaskCompleted(taskOf(todo).taskId, completed = true, now = 1500)

            // `a.jpg` was in the folder when the history was written and is not in it now. Its
            // record has nowhere to go — and the point is that it does not go *next door*: the
            // recovery holds two records for one surviving file, and a restore that matched loosely
            // would put `custom1` on `b.jpg` and lose `custom2` doing it.
            dao.deleteTodo(todo, keepState = true, now = 5000)
            val result =
                reimport("photos", DIRECTORY, listOf("alice/b.jpg"), recoverFrom = todo, now = 6000)

            assertEquals(1, result.tasksRecovered)
            val recovered = taskOf(sourceOf("photos"))
            assertEquals(
                mapOf("b.jpg" to AssetDecisions(setOf("custom2"))),
                recovered.assets.associate { it.name to it.decisions }
            )
            // And the completion crossed anyway, because it is the Task's and the Task is still
            // there: a file that went missing is not a reason to forget that the Task was finished.
            assertEquals(true, recovered.isCompleted)
        }

    @Test
    fun `a Task with no file recovers its completion alone, and marks no file the new import brought`() =
        runTest {
            val todo = importFolder("photos", DIRECTORY, THREE_FILES)
            mark(todo, "alice/a.jpg", ItemAction.Favourite)
            dao.setTaskCompleted(taskOf(todo).taskId, completed = true, now = 1500)
            // The Task's files are taken away before it is dissolved, so the history is written about
            // a Task with nothing to record — which is what a manual item is, and what a Task whose
            // every file went missing becomes. Its payload is `''`, an answer rather than a failure.
            rawExec("DELETE FROM assets WHERE taskId = '${taskOf(todo).taskId}'")

            dao.deleteTodo(todo, keepState = true, now = 5000)
            assertEquals("", recordsOf(todo))

            // The new import brings the files back. Every one of them must arrive unmarked: the old
            // `★` was the Task's, there is no honest file to put it on, and spreading it across the
            // three would be the v0.5.1 defect reached through the recovery door instead of the fold.
            reimport("photos", DIRECTORY, THREE_FILES, recoverFrom = todo, now = 6000)

            val recovered = taskOf(sourceOf("photos"))
            assertEquals(true, recovered.isCompleted)
            assertEquals(
                mapOf(
                    "a.jpg" to AssetDecisions(),
                    "b.jpg" to AssetDecisions(),
                    "c.jpg" to AssetDecisions()
                ),
                recovered.assets.associate { it.name to it.decisions }
            )
        }

    // --- what a history cannot say -----------------------------------------------------------------

    @Test
    fun `a payload this build cannot read restores the completion, and nothing else`() = runTest {
        val todo = importFolder("photos", DIRECTORY, listOf("alice/a.jpg"))
        mark(todo, "alice/a.jpg", ItemAction.Favourite, ItemAction("custom1"))
        dao.setTaskCompleted(taskOf(todo).taskId, completed = true, now = 1500)
        dao.deleteTodo(todo, keepState = true, now = 5000)

        // Two ways a row can say something this build cannot read, and both are written as a release
        // might write them rather than as a corrupted string: a version it does not know, and a line
        // whose shape the version it names does not describe. Neither is a reason to refuse the
        // completion — which is the Task's own and needs no decoding — and neither is a licence to
        // guess at the rest.
        rawExec("UPDATE dissolved_task_state SET recordVersion = 99 WHERE sourceId = '$todo'")
        reimport("photos", DIRECTORY, listOf("alice/a.jpg"), recoverFrom = todo, now = 6000)
        assertEquals(
            mapOf("a.jpg" to AssetDecisions()),
            taskOf(sourceOf("photos")).assets.associate { it.name to it.decisions }
        )
        assertEquals(true, taskOf(sourceOf("photos")).isCompleted)

        // The version is one this build reads, so the line has to be the shape it does not: the
        // current encoding writes two fields, and a third is one it would have to guess the meaning
        // of.
        rawExec(
            "UPDATE dissolved_task_state SET recordVersion = $DISSOLVED_RECORDS_VERSION, " +
                "records = 'alice/a.jpg\tcustom1\textra' WHERE sourceId = '$todo'"
        )
        reimport("photos", DIRECTORY, listOf("alice/a.jpg"), recoverFrom = todo, now = 7000)
        assertEquals(
            mapOf("a.jpg" to AssetDecisions()),
            taskOf(sourceOf("photos")).assets.associate { it.name to it.decisions }
        )
        assertEquals(true, taskOf(sourceOf("photos")).isCompleted)
    }

    // --- the round trip ------------------------------------------------------------------------------

    @Test
    fun `dissolving a recovered Todo writes the records it recovered, byte for byte`() = runTest {
        val todo = importFolder("photos", DIRECTORY, THREE_FILES)
        mark(todo, "alice/a.jpg", ItemAction.Favourite, ItemAction("custom1"))
        mark(todo, "alice/b.jpg", ItemAction("custom2"))
        dao.deleteTodo(todo, keepState = true, now = 5000)

        reimport("photos", DIRECTORY, THREE_FILES, recoverFrom = todo, now = 6000)
        val fresh = sourceOf("photos")
        dao.deleteTodo(fresh, keepState = true, now = 7000)

        // A history that has been through a recovery and a dissolution again is the same history.
        // The encoded string is compared rather than the decoded records, because that is the claim:
        // the payload is a contract, and a round trip that reordered or re-escaped it — or that
        // dropped the line for a file nobody had marked — would be legible here as a different
        // string while every decoded comparison still passed.
        assertEquals(recordsOf(todo), recordsOf(fresh))
    }

    // --- the fixture ------------------------------------------------------------------------------

    /**
     * A folder-as-Task Todo over [folder] holding one Task at [directory] with [files] in it.
     *
     * Folder-as-Task because that is the shape the whole rule is about: a Task made of many files,
     * which is where a Task-level record and a file's record can be told apart. The file-as-Task
     * fixtures elsewhere hold one file per Task and cannot.
     */
    private suspend fun importFolder(
        folder: String,
        directory: String,
        files: List<String>,
        now: Long = 1000
    ): String {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = folder,
            displayName = folder.replaceFirstChar { it.uppercaseChar() },
            importerKind = ImporterKind.FOLDER_AS_TASK,
            keepTree = true,
            now = now
        )
        dao.runImport(
            source.id,
            folder,
            plan(
                ImporterKind.FOLDER_AS_TASK,
                folder,
                FolderSnapshot(files.map { scanned(it) }, listOf(directory))
            ),
            now
        )
        return source.id
    }

    /** A second Todo over the same folder, born knowing whatever [recoverFrom] has to say. */
    private suspend fun reimport(
        folder: String,
        directory: String,
        files: List<String>,
        recoverFrom: String,
        now: Long
    ): ImportResult {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = folder,
            displayName = folder.replaceFirstChar { it.uppercaseChar() },
            importerKind = ImporterKind.FOLDER_AS_TASK,
            keepTree = true,
            now = now
        )
        return dao.runImport(
            source.id,
            folder,
            plan(
                ImporterKind.FOLDER_AS_TASK,
                folder,
                FolderSnapshot(files.map { scanned(it) }, listOf(directory))
            ),
            now,
            recoverFrom = recoverFrom
        )
    }

    /**
     * Marks one file of the Todo's single Task, through the write a chip tap reaches.
     *
     * The Asset is found by the name the app draws it under, so a fixture that named the wrong file
     * would fail here rather than write a record onto a neighbour that a loose reader would then
     * agree with.
     */
    private suspend fun mark(sourceId: String, file: String, vararg actions: ItemAction) {
        val task = taskOf(sourceId)
        val asset = task.assets.first { it.name == file.substringAfterLast('/') }
        for (action in actions) {
            dao.setAssetAction(task.taskId, asset.assetId, action, enabled = true, now = 1500)
        }
    }

    /** The Todo's one Task, as the catalog draws it: every Asset with its own pair's record. */
    private suspend fun taskOf(sourceId: String): CatalogRow =
        dao.getCatalogTasks(sourceId).single { it.groupPath == "$FOLDER/$DIRECTORY" }

    private suspend fun sourceOf(folder: String): String =
        dao.sourcesCovering(ROOT, folder).maxByOrNull { it.createdAt }!!.id

    /** The payload a dissolved Todo left, as the snapshot wrote it. */
    private fun recordsOf(sourceId: String): String =
        database.openHelper.writableDatabase.query(
            "SELECT records FROM dissolved_task_state WHERE sourceId = '$sourceId'"
        ).use { cursor ->
            assertTrue("no history for $sourceId", cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun scanned(relativePath: String) = ScannedFile(
        name = relativePath.substringAfterLast('/'),
        relativePath = relativePath,
        uri = "$ROOT/$FOLDER/$relativePath"
    )

    private fun rawExec(sql: String) {
        database.openHelper.writableDatabase.execSQL(sql)
    }

    private companion object {
        const val ROOT = "content://root"
        const val FOLDER = "photos"
        const val DIRECTORY = "alice"

        val THREE_FILES = listOf("alice/a.jpg", "alice/b.jpg", "alice/c.jpg")
    }
}
