package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.FolderSnapshot
import com.example.listea.ImporterKind
import com.example.listea.ContentPolicy
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

/**
 * What a run does to a Task whose content changed, under each of the three tiers.
 *
 * The tier *rule* is pinned in `ContentPolicyTest`, which is pure and has no database. This file is
 * the other half and the one that could not be pure: what the answer does to rows. A `reset` that
 * cleared the judgment but also, invisibly, took the Task's List membership would pass every test
 * about the rule and still be wrong on a device.
 *
 * The three tiers are therefore asserted as three different *shapes of outcome*, not as three
 * values:
 *
 * | Policy | Task id | Judgment | List membership |
 * |---|---|---|---|
 * | `keep` | survives | survives | survives |
 * | `reset` | survives | gone | survives |
 * | `drop` | replaced | gone with it | gone, and the List is told why |
 *
 * The other claim here is the one that keeps a policy switch from being a machine that acts on
 * every entry: a Task the run found unchanged is under no tier at all, so a second run over the
 * same folder does nothing — even to a Task that was judged between the two runs.
 *
 * Everything is real SQL through Room, for the reason [ListsDaoTest] gives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContentPolicyRunTest {

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

    // --- keep ------------------------------------------------------------------------------------

    @Test
    fun `keep flags what has gone and keeps the judgment`() = runTest {
        val todo = fileTodo(ContentPolicy.KEEP, "a.jpg", "b.jpg")
        val judged = taskFor(todo, "photos/a.jpg")
        dao.setTaskCompleted(judged, completed = true, now = 2500)

        val result = runOver(todo, "a.jpg")

        assertEquals(1, result.assetsMarkedMissing)
        assertEquals(0, result.tasksReset)
        assertEquals(0, result.tasksDropped)
        // The Task, its Asset and the tick are all still there; only the flag is new.
        assertEquals(judged, taskFor(todo, "photos/a.jpg"))
        assertEquals(1, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$judged'"))
        assertEquals(1, rawInt("SELECT COUNT(*) FROM assets WHERE sourceMissing = 1"))
    }

    // --- reset -----------------------------------------------------------------------------------

    @Test
    fun `reset clears the judgment and nothing else`() = runTest {
        val todo = fileTodo(ContentPolicy.RESET, "a.jpg", "b.jpg")
        // The Task whose file is about to go, not the one that stays: the run brings `a.jpg` back
        // unchanged and finds `b.jpg` gone, so `b.jpg`'s Task is the one at issue.
        val judged = taskFor(todo, "photos/b.jpg")
        val listId = listOver(todo, "Trip")
        dao.setTaskCompleted(judged, completed = true, now = 2500)

        val result = runOver(todo, "a.jpg")

        assertEquals(1, result.tasksReset)
        assertEquals(0, result.tasksDropped)
        // The Task is the same Task, so every List holding it still holds it, and the ids a webhook
        // would have carried are unchanged. The one that stays (`a.jpg`) is not the one at issue.
        assertEquals(judged, taskFor(todo, "photos/b.jpg"))
        assertEquals(2, rawInt("SELECT COUNT(*) FROM list_items WHERE listId = $listId"))
        assertEquals(0, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$judged'"))
        // And the material that has gone is still flagged: clearing a judgment is not the same as
        // forgetting that the file is missing.
        assertEquals(1, rawInt("SELECT COUNT(*) FROM assets WHERE sourceMissing = 1"))
    }

    @Test
    fun `reset leaves a Task the run found unchanged exactly as it was`() = runTest {
        val todo = fileTodo(ContentPolicy.RESET, "a.jpg")
        val judged = taskFor(todo, "photos/a.jpg")
        dao.setTaskCompleted(judged, completed = true, now = 2500)

        // The same folder, twice. Nothing changed, so no tier applies — and the tick taken between
        // the runs survives the second one. Without the "no change, no decision" rule this is a
        // Todo that clears every judgment it holds every time it is refreshed.
        val result = runOver(todo, "a.jpg")

        assertEquals(0, result.tasksReset)
        assertEquals(1, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$judged'"))
    }

    @Test
    fun `a Task whose content all went is reset rather than replaced`() = runTest {
        val todo = fileTodo(ContentPolicy.RESET, "a.jpg")
        val judged = taskFor(todo, "photos/a.jpg")
        dao.setTaskCompleted(judged, completed = true, now = 2500)

        // Nothing at all is left: file-as-Task under `reset` is the degradation `drop` also makes,
        // and the Task survives as a Task with a missing Asset.
        val result = runOver(todo)

        assertEquals(1, result.tasksReset)
        assertEquals(0, result.tasksDropped)
        assertEquals(judged, taskFor(todo, "photos/a.jpg"))
        assertEquals(0, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$judged'"))
    }

    // --- drop ------------------------------------------------------------------------------------

    @Test
    fun `drop replaces a group and takes its memberships with it`() = runTest {
        val todo = folderTodo(ContentPolicy.DROP, files = listOf("dishes/a.jpg", "dishes/b.jpg"))
        val judged = soleTask(todo)
        val listId = listOver(todo, "Trip")
        dao.setTaskCompleted(judged, completed = true, now = 2500)

        val result = runOver2(
            todo,
            files = listOf("dishes/a.jpg"),
            directories = listOf("dishes")
        )

        assertEquals(1, result.tasksDropped)
        assertEquals(1, result.tasksCreated)
        // A different Task, holding only what is left, with the judgment gone because the Task that
        // carried it is gone.
        val replacement = soleTask(todo)
        assertNotEquals(judged, replacement)
        assertEquals(1, rawInt("SELECT COUNT(*) FROM assets WHERE taskId = '$replacement'"))
        assertEquals(0, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$replacement'"))
        // The membership went with it, and the List says why — a List that quietly got shorter is
        // the screen this whole mechanism exists to refuse.
        assertEquals(0, rawInt("SELECT COUNT(*) FROM list_items WHERE taskId = '$judged'"))
        assertEquals(1, rawInt("SELECT lastDissolvedCount FROM lists WHERE id = $listId"))
        assertEquals(
            DissolutionReason.Dropped.key,
            rawText("SELECT lastDissolvedReason FROM lists WHERE id = $listId")
        )
    }

    @Test
    fun `drop with nothing left replaces the Task with nothing`() = runTest {
        val todo = folderTodo(ContentPolicy.DROP, files = listOf("dishes/a.jpg", "dishes/b.jpg"))
        val judged = soleTask(todo)
        listOver(todo, "Trip")

        val result = runOver2(todo, files = emptyList(), directories = listOf("dishes"))

        // The group changed and the plan makes nothing at that path, so the Task is gone and no
        // Task takes its place. The List is still told.
        assertEquals(1, result.tasksDropped)
        assertEquals(0, result.tasksCreated)
        assertEquals(0, rawInt("SELECT COUNT(*) FROM task_imports"))
        assertEquals(1, rawInt("SELECT lastDissolvedCount FROM lists WHERE id = 1"))
    }

    @Test
    fun `drop degrades to reset on a Task that is its one file`() = runTest {
        val todo = fileTodo(ContentPolicy.DROP, "a.jpg", "b.jpg")
        val judged = taskFor(todo, "photos/b.jpg")
        val listId = listOver(todo, "Trip")
        dao.setTaskCompleted(judged, completed = true, now = 2500)

        val result = runOver(todo, "a.jpg")

        // The user asked to replace what changed, and what changed is a Task holding a single file.
        // Replacing it would mint a Task holding that same file under a new id — the judgment and
        // the membership gone, nothing bought — so the identity stands and the judgment clears.
        assertEquals(0, result.tasksDropped)
        assertEquals(1, result.tasksReset)
        assertEquals(judged, taskFor(todo, "photos/b.jpg"))
        assertEquals(2, rawInt("SELECT COUNT(*) FROM list_items WHERE listId = $listId"))
        assertEquals(0, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$judged'"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM lists WHERE lastDissolvedCount IS NOT NULL"))
    }

    @Test
    fun `drop leaves an unchanged group alone`() = runTest {
        val todo = folderTodo(ContentPolicy.DROP, files = listOf("dishes/a.jpg", "dishes/b.jpg"))
        val judged = soleTask(todo)
        dao.setTaskCompleted(judged, completed = true, now = 2500)

        val result = runOver2(
            todo,
            files = listOf("dishes/a.jpg", "dishes/b.jpg"),
            directories = listOf("dishes")
        )

        // The pin that makes `drop` safe to leave switched on: nothing changed, so nothing is
        // replaced — not even a Task that was judged in between.
        assertEquals(0, result.tasksDropped)
        assertEquals(judged, soleTask(todo))
        assertEquals(1, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$judged'"))
    }

    // --- which policy a run is under ------------------------------------------------------------

    @Test
    fun `a run applies the policy its caller names, not the one the Todo stores`() = runTest {
        // The import dialog asks the question and runs on the answer, while the Todo's row is only
        // written back afterwards — so for the length of one run the two legitimately differ. Read
        // from the row instead and the user who chose *Start it over* and pressed Refresh watches
        // the tier do nothing, and has to press it a second time to get what the first one promised.
        val todo = folderTodo(ContentPolicy.KEEP, files = listOf("dishes/a.jpg"))
        val judged = soleTask(todo)
        dao.setTaskCompleted(judged, completed = true, now = 2500)

        val result = dao.runImport(
            todo,
            "",
            folderPlan(listOf("dishes/b.jpg"), listOf("dishes")),
            3000,
            contentPolicy = ContentPolicy.RESET
        )

        assertEquals(1, result.tasksReset)
        assertEquals(judged, soleTask(todo))
        assertEquals(0, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$judged'"))
        // And the row is untouched by the run: recording the answer belongs to whoever asked the
        // question, and a run that also wrote its Todo's policy would be a second writer.
        assertEquals(
            ContentPolicy.KEEP.key,
            rawText("SELECT contentPolicy FROM sources WHERE id = '$todo'")
        )
    }

    @Test
    fun `a run that names no policy reads the Todo's own`() = runTest {
        // The other half, and the one every existing caller relies on: a Todo that follows its
        // upstream runs on what it stores, with no dialog in between to say otherwise.
        val todo = folderTodo(ContentPolicy.RESET, files = listOf("dishes/a.jpg"))
        val judged = soleTask(todo)
        dao.setTaskCompleted(judged, completed = true, now = 2500)

        val result = runOver2(
            todo,
            files = listOf("dishes/b.jpg"),
            directories = listOf("dishes")
        )

        assertEquals(1, result.tasksReset)
        assertEquals(0, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$judged'"))
    }

    // --- the question entry asks ----------------------------------------------------------------

    @Test
    fun `a Todo that follows its upstream is told when there is nothing to do`() = runTest {
        val todo = fileTodo(ContentPolicy.KEEP, "a.jpg")

        assertTrue(
            "a run over the same folder has nothing to say",
            !dao.sourceHasChanges(todo, "photos", filePlan("photos", listOf("a.jpg")))
        )
        assertTrue(
            "a file that appeared is something to say",
            dao.sourceHasChanges(todo, "photos", filePlan("photos", listOf("a.jpg", "b.jpg")))
        )
    }

    @Test
    fun `a Todo that has never run is told to run`() = runTest {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = "photos",
            displayName = "Photos",
            importerKind = ImporterKind.FILE_AS_TASK,
            now = 1000
        )

        assertTrue(dao.sourceHasChanges(source.id, "photos", filePlan("photos", listOf("a.jpg"))))
        // And an empty folder is not: there is no Task to mint and nothing to flag, so a Todo that
        // follows its upstream would otherwise run a write every time it was opened.
        assertTrue(!dao.sourceHasChanges(source.id, "photos", filePlan("photos", emptyList())))
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private suspend fun fileTodo(policy: ContentPolicy, vararg names: String): String {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = "photos",
            displayName = "Photos",
            importerKind = ImporterKind.FILE_AS_TASK,
            contentPolicy = policy,
            now = 1000
        )
        dao.runImport(source.id, "photos", filePlan("photos", names.toList()), 2000)
        return source.id
    }

    private suspend fun folderTodo(policy: ContentPolicy, files: List<String>): String {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = "",
            displayName = "Photos",
            importerKind = ImporterKind.FOLDER_AS_TASK,
            contentPolicy = policy,
            now = 1000
        )
        dao.runImport(source.id, "", folderPlan(files, listOf("dishes")), 2000)
        return source.id
    }

    /** The run a policy is about: the same folder, one file short. */
    private suspend fun runOver(sourceId: String, vararg names: String): ImportResult =
        dao.runImport(sourceId, "photos", filePlan("photos", names.toList()), 3000)

    private suspend fun runOver2(
        sourceId: String,
        files: List<String>,
        directories: List<String>
    ): ImportResult = dao.runImport(sourceId, "", folderPlan(files, directories), 3000)

    private fun filePlan(importRoot: String, names: List<String>) = plan(
        ImporterKind.FILE_AS_TASK,
        importRoot,
        FolderSnapshot(names.map { scanned(importRoot, it) }, emptyList())
    )

    private fun folderPlan(files: List<String>, directories: List<String>) = plan(
        ImporterKind.FOLDER_AS_TASK,
        "",
        FolderSnapshot(files.map { scanned("", it) }, directories)
    )

    private fun scanned(importRoot: String, relativePath: String) = ScannedFile(
        name = relativePath.substringAfterLast('/'),
        relativePath = relativePath,
        uri = "$ROOT/${childRelativePath(importRoot, relativePath)}"
    )

    /** The id of the Task whose `groupPath` is [groupPath], read the way a screen would find it. */
    private suspend fun taskFor(sourceId: String, groupPath: String): String =
        dao.getCatalogTasks(sourceId).single { it.groupPath == groupPath }.taskId

    private suspend fun soleTask(sourceId: String): String =
        dao.getCatalogTasks(sourceId).single().taskId

    private suspend fun listOver(sourceId: String, title: String): Long =
        dao.createListFromTasks(
            title = title,
            taskIds = dao.getCatalogTasks(sourceId).map { it.taskId },
            exportOnCompletion = false,
            now = 3000
        )

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

    private companion object {
        const val ROOT = "content://root"
    }
}
