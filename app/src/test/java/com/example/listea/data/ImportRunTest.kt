package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.FolderSnapshot
import com.example.listea.ImporterKind
import com.example.listea.childRelativePath
import com.example.listea.plan
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * The import run: the one transaction allowed to create a Task, and the reconciliation rules that
 * keep it identity-stable.
 *
 * These are the §4.4 and §5 invariants made concrete. A run resolves every planned Asset locator
 * against `assets` before writing anything, so a re-run of unchanged material creates nothing and
 * an import over a legacy-claimed file adopts it once rather than minting a rival. Coverage bounds
 * the only destructive-looking thing a run does — flagging a file missing — so a scoped refresh can
 * never reach past the folder it scanned.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportRunTest {

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

    // §2.3, §3, AR-1
    @Test
    fun `importing the whole root adopts every legacy Task without changing any id`() = runTest {
        val a = writeLegacySourceTask("a.jpg", completed = true)
        val b = writeLegacySourceTask("b.jpg")
        val source = registerSource(ROOT, "", ImporterKind.FILE_AS_TASK)

        val result = dao.runImport(source.id, "", filePlan("", listOf("a.jpg", "b.jpg")), 2000)

        assertEquals(ImportOutcome.COMPLETE, result.outcome)
        assertEquals(0, result.tasksCreated)
        assertEquals(2, result.tasksAdopted)
        // The Tasks and Assets keep their identity; the claim set empties; state and history are
        // untouched, because adoption is a change of owner, not of what was decided.
        assertEquals(setOf(a, b), rawTexts("SELECT taskId FROM task_imports").toSet())
        assertEquals(0, rawInt("SELECT COUNT(*) FROM legacy_source_tasks"))
        assertEquals(1, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$a'"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM review_events"))
    }

    // §5.3
    @Test
    fun `re-importing unchanged material creates nothing, adopts nothing and changes no id`() = runTest {
        val source = registerSource(ROOT, "", ImporterKind.FILE_AS_TASK)
        dao.runImport(source.id, "", filePlan("", listOf("a.jpg", "b.jpg")), 2000)
        val taskIds = rawTexts("SELECT id FROM tasks").toSet()
        val assetIds = rawTexts("SELECT id FROM assets").toSet()

        val result = dao.runImport(source.id, "", filePlan("", listOf("a.jpg", "b.jpg")), 3000)

        assertEquals(ImportOutcome.COMPLETE, result.outcome)
        assertEquals(0, result.tasksCreated)
        assertEquals(0, result.tasksAdopted)
        assertEquals(2, result.tasksReconciled)
        assertEquals(0, result.assetsCreated)
        assertEquals(taskIds, rawTexts("SELECT id FROM tasks").toSet())
        assertEquals(assetIds, rawTexts("SELECT id FROM assets").toSet())
        assertEquals(2, rawInt("SELECT COUNT(*) FROM tasks"))
    }

    // §4.4
    @Test
    fun `a legacy-claimed file met by a folder-as-Task import is a conflict, not a merge or re-mint`() =
        runTest {
            val legacy = writeLegacySourceTask("alice/cv.pdf")
            val source = registerSource(ROOT, "", ImporterKind.FOLDER_AS_TASK)

            val result = dao.runImport(
                source.id,
                "",
                folderPlan("", listOf("alice/cv.pdf", "bob/cv.pdf"), listOf("alice", "bob")),
                2000
            )

            assertEquals(ImportOutcome.PARTIAL, result.outcome)
            assertEquals(listOf("alice/cv.pdf"), result.conflicts.map { it.relativePath })
            // The non-conflicting folder was committed; the legacy Task was neither folded into a
            // group nor minted a second time.
            assertEquals(1, result.tasksCreated)
            assertEquals(1, rawInt("SELECT COUNT(*) FROM legacy_source_tasks WHERE taskId = '$legacy'"))
            assertEquals(1, rawInt("SELECT COUNT(*) FROM tasks WHERE id = '$legacy'"))
            assertEquals(0, rawInt("SELECT COUNT(*) FROM task_imports WHERE taskId = '$legacy'"))
        }

    // §5.6, AR-2 — the rule this release deletes
    @Test
    fun `registering a Source over ground an existing one covers is allowed, parent and child either way`() =
        runTest {
            val parent = registerSource(ROOT, "a", ImporterKind.FILE_AS_TASK)
            val child = dao.registerSource(ROOT, "a/b", "x", ImporterKind.FILE_AS_TASK, keepTree = true, now = 1000)

            // Two rows, not one and not an error. Import roots may nest now, because they stopped
            // selling ownership of anything: what a Task belongs to is its `task_imports` row, and
            // the folder a run was started on no longer has to be a folder nothing else covers.
            assertNotEquals(parent.id, child.id)
            assertEquals(2, rawInt("SELECT COUNT(*) FROM sources"))

            val outer = registerSource(ROOT, "x", ImporterKind.FILE_AS_TASK)
            val inner = dao.registerSource(ROOT, "x/y", "y", ImporterKind.FILE_AS_TASK, keepTree = true, now = 1000)
            assertNotEquals(outer.id, inner.id)
            assertEquals(4, rawInt("SELECT COUNT(*) FROM sources"))
        }

    // §5.6
    @Test
    fun `a folder inside a covering Source still gets its own Source when one is registered`() =
        runTest {
            val source = registerSource(ROOT, "", ImporterKind.FILE_AS_TASK)

            // Covering is a question about a path, and it is still answered the same way — the
            // Source at the grant root covers everything under it. What has gone is the *inference*
            // from that answer to "so this folder already has an owner": registration no longer
            // consults it, and a run started lower down is a second intent rather than a reuse.
            assertEquals(listOf(source.id), dao.sourcesCovering(ROOT, "holiday").map { it.id })
            assertEquals(listOf(source.id), dao.sourcesCovering(ROOT, "holiday/sub").map { it.id })
            assertTrue(dao.sourcesAt(ROOT, "holiday").isEmpty())

            val nested = dao.registerSource(ROOT, "holiday", "Holiday", ImporterKind.FILE_AS_TASK, keepTree = true, now = 2000)
            assertEquals(
                listOf(nested.id, source.id),
                dao.sourcesCovering(ROOT, "holiday/sub").map { it.id }
            )
            assertEquals(2, rawInt("SELECT COUNT(*) FROM sources"))
        }

    // §5.6, AR-2
    @Test
    fun `refreshing two Sources alternately never moves a Task between them`() = runTest {
        val legacy = writeLegacySourceTask("a/x.jpg")
        val a = registerSource(ROOT, "a", ImporterKind.FILE_AS_TASK)
        val b = registerSource(ROOT, "b", ImporterKind.FILE_AS_TASK)

        assertEquals(1, dao.runImport(a.id, "a", filePlan("a", listOf("x.jpg")), 2000).tasksAdopted)
        dao.runImport(b.id, "b", filePlan("b", listOf("y.jpg")), 2000)

        val aAgain = dao.runImport(a.id, "a", filePlan("a", listOf("x.jpg")), 3000)
        val bAgain = dao.runImport(b.id, "b", filePlan("b", listOf("y.jpg")), 3000)

        // Adoption is one-way and happens once: the Task stays with A, and no refresh moves it.
        assertEquals(0, aAgain.tasksAdopted)
        assertEquals(0, bAgain.tasksAdopted)
        assertEquals(a.id, rawText("SELECT sourceId FROM task_imports WHERE taskId = '$legacy'"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM legacy_source_tasks"))
    }

    // §5.5, AR-5
    @Test
    fun `an import with a conflict returns partial and still commits the non-conflicting Tasks`() =
        runTest {
            writeLegacySourceTask("alice/cv.pdf")
            val source = registerSource(ROOT, "", ImporterKind.FOLDER_AS_TASK)

            val result = dao.runImport(
                source.id,
                "",
                folderPlan("", listOf("alice/cv.pdf", "bob/cv.pdf"), listOf("alice", "bob")),
                2000
            )

            assertEquals(ImportOutcome.PARTIAL, result.outcome)
            assertEquals(1, result.conflicts.size)
            assertEquals(1, result.tasksCreated)
            // The Source's durable status is partial, which is what a composed shortcut gates on.
            assertEquals(ImportOutcome.PARTIAL.key, rawText("SELECT lastImportStatus FROM sources"))
            // Only the non-conflicting folder became a Task; the legacy one was left alone.
            assertEquals(1, rawInt("SELECT COUNT(*) FROM task_imports"))
        }

    // §5.5
    @Test
    fun `an import whose only irregularities are notices returns complete`() = runTest {
        val source = registerSource(ROOT, "", ImporterKind.FOLDER_AS_TASK)

        val result = dao.runImport(
            source.id,
            "",
            folderPlan(
                "",
                files = listOf("alice/cv.pdf", "rubric.txt"),
                directories = listOf("alice", "empty")
            ),
            2000
        )

        // A skipped file and an empty folder are the importer doing its job, not a reason to stop a
        // composed shortcut from proceeding.
        assertEquals(ImportOutcome.COMPLETE, result.outcome)
        assertTrue(result.notices.isNotEmpty())
        assertEquals(1, result.tasksCreated)
    }

    // §5.1, AR-4
    @Test
    fun `a scoped refresh marks nothing missing outside its coverage`() = runTest {
        val source = registerSource(ROOT, "", ImporterKind.FILE_AS_TASK)
        dao.runImport(source.id, "", filePlan("", listOf("a/x.jpg", "b/y.jpg")), 2000)

        val result = dao.runImport(source.id, "a", filePlan("a", listOf("x.jpg")), 3000)

        assertEquals(ImportOutcome.COMPLETE, result.outcome)
        assertEquals(0, result.assetsMarkedMissing)
        // `b/y.jpg` was not scanned, so nothing new is said about it.
        assertEquals(0, rawInt("SELECT sourceMissing FROM assets WHERE relativePath = 'b/y.jpg'"))
        assertEquals(0, rawInt("SELECT sourceMissing FROM assets WHERE relativePath = 'a/x.jpg'"))
    }

    // §5.1, AR-4
    @Test
    fun `a scoped refresh whose folder is now empty marks exactly that folder's Assets missing`() =
        runTest {
            val source = registerSource(ROOT, "", ImporterKind.FILE_AS_TASK)
            dao.runImport(source.id, "", filePlan("", listOf("a/x.jpg", "a/y.jpg", "b/z.jpg")), 2000)

            val result = dao.runImport(source.id, "a", filePlan("a", emptyList()), 3000)

            assertEquals(2, result.assetsMarkedMissing)
            assertEquals(1, rawInt("SELECT sourceMissing FROM assets WHERE relativePath = 'a/x.jpg'"))
            assertEquals(1, rawInt("SELECT sourceMissing FROM assets WHERE relativePath = 'a/y.jpg'"))
            assertEquals(0, rawInt("SELECT sourceMissing FROM assets WHERE relativePath = 'b/z.jpg'"))
        }

    // §5.2
    @Test
    fun `a scoped refresh is refused for a folder-as-Task Source`() = runTest {
        val source = registerSource(ROOT, "", ImporterKind.FOLDER_AS_TASK)

        val result = dao.runImport(source.id, "alice", filePlan("alice", listOf("cv.pdf")), 2000)

        // A half-scanned group is a group whose Asset list is wrong, so nothing is written at all.
        assertEquals(ImportOutcome.FAILED, result.outcome)
        assertEquals(0, rawInt("SELECT COUNT(*) FROM task_imports"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM assets"))
    }

    // --- helpers ---------------------------------------------------------------------------------

    private suspend fun registerSource(
        rootUri: String,
        relativePath: String,
        kind: ImporterKind
    ): SourceEntity =
        dao.registerSource(rootUri, relativePath, relativePath.ifEmpty { "Root" }, kind, keepTree = true, now = 1000)

    /** A file-as-Task plan over [files], which are relative to the scan root at [importRoot]. */
    private fun filePlan(importRoot: String, files: List<String>): com.example.listea.ImportPlan =
        plan(
            ImporterKind.FILE_AS_TASK,
            importRoot,
            FolderSnapshot(files.map { scanned(importRoot, it) }, emptyList())
        )

    private fun folderPlan(
        importRoot: String,
        files: List<String>,
        directories: List<String>
    ): com.example.listea.ImportPlan = plan(
        ImporterKind.FOLDER_AS_TASK,
        importRoot,
        FolderSnapshot(files.map { scanned(importRoot, it) }, directories)
    )

    private fun scanned(importRoot: String, relativePath: String) = ScannedFile(
        name = relativePath.substringAfterLast('/'),
        relativePath = relativePath,
        uri = "$ROOT/${childRelativePath(importRoot, relativePath)}"
    )

    /**
     * A file-backed Task claimed by the legacy set, the shape the migration leaves: a Task, one
     * Asset, state, and a `legacy_source_tasks` row, with no Source and no provenance.
     */
    private fun writeLegacySourceTask(relativePath: String, completed: Boolean = false): String {
        val taskId = UUID.randomUUID().toString()
        val assetId = UUID.randomUUID().toString()
        val db = database.openHelper.writableDatabase
        db.execSQL("INSERT INTO tasks (id, createdAt) VALUES (?, 1000)", arrayOf<Any?>(taskId))
        db.execSQL(
            "INSERT INTO assets (id, taskId, sortOrder, rootUri, relativePath, sourceMissing) " +
                "VALUES (?, ?, 0, ?, ?, 0)",
            arrayOf<Any?>(assetId, taskId, ROOT, relativePath)
        )
        db.execSQL(
            "INSERT INTO task_state (taskId, isCompleted, isFavorite, customActions, updatedAt) " +
                "VALUES (?, ?, 0, '', 1000)",
            arrayOf<Any?>(taskId, if (completed) 1 else 0)
        )
        db.execSQL("INSERT INTO legacy_source_tasks (taskId) VALUES (?)", arrayOf<Any?>(taskId))
        return taskId
    }

    private fun rawInt(sql: String): Int =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            assertTrue("no row for: $sql", cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun rawText(sql: String): String =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            assertTrue("no row for: $sql", cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun rawTexts(sql: String): List<String> =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private companion object {
        const val ROOT = "content://root"
    }
}
