package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.FolderSnapshot
import com.example.listea.ImporterKind
import com.example.listea.childRelativePath
import com.example.listea.plan
import kotlinx.coroutines.flow.first
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
 * Two Todos over one folder, and what that does to everything that used to assume it could not
 * happen.
 *
 * The release's central claim is that the sharing boundary moved from the file to the Task: a file
 * reached through two Todos is two Tasks with two decisions, and a Task reached through two Lists
 * is one Task with one. The schema half of that is [MigrationV16ToV17Test]'s; this is the half that
 * lives in the queries, where the old assumption was written down and is easiest to leave behind.
 *
 * Four places had it. The reads that turn a file into a row had no `GROUP BY`, so one file under
 * two Todos would arrive as two rows whose order decided what the user saw. The counts over those
 * reads had a `COUNT(*)`, so a shared file was counted twice by a card offering to delete *files*.
 * The naming backfill wrote one wire id per Asset, which was the same thing as per file until now.
 * And the importer treated a locator another Todo held as a collision, which is the one that would
 * have made the whole release invisible: the second import would have reported conflicts instead
 * of writing Tasks.
 *
 * Everything here is real SQL through Room, for the reason [ListsDaoTest] gives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedTaskTest {

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

    // --- two Todos, two Tasks, two decisions -------------------------------------------------------

    @Test
    fun `importing one folder twice makes two Tasks over the same file, not one adopted twice`() =
        runTest {
            val chilli = todo("Chilli fried egg", now = 1000)
            val tomato = todo("Tomato and egg", now = 2000)

            val first = importFile(chilli, "a.jpg", now = 1000)
            val second = importFile(tomato, "a.jpg", now = 2000)

            // Two Tasks, one file. This is the whole release: the file is content *presented for*
            // each Task, so the second import mints rather than colliding with the first.
            assertNotEquals(first, second)
            assertEquals(2, rawInt("SELECT COUNT(*) FROM tasks"))
            assertEquals(2, rawInt("SELECT COUNT(*) FROM assets WHERE relativePath = 'a.jpg'"))
            assertEquals(chilli, rawText("SELECT sourceId FROM task_imports WHERE taskId = '$first'"))
            assertEquals(tomato, rawText("SELECT sourceId FROM task_imports WHERE taskId = '$second'"))
        }

    @Test
    fun `checking one Todo's Task leaves the other Todo's Task over the same file alone`() = runTest {
        val chilli = todo("Chilli fried egg", now = 1000)
        val tomato = todo("Tomato and egg", now = 2000)
        val first = importFile(chilli, "a.jpg", now = 1000)
        val second = importFile(tomato, "a.jpg", now = 2000)

        dao.setTaskCompleted(first, completed = true, now = 3000)

        // The decision is the Task's and the Tasks are not the same one. If the state had stayed
        // keyed on the file, this is where the second dish would silently learn it had been cooked.
        assertEquals(1, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$first'"))
        assertEquals(0, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$second'"))
    }

    @Test
    fun `tagging one Todo's Task leaves the other Todo's Task over the same file alone`() = runTest {
        val chilli = todo("Chilli fried egg", now = 1000)
        val tomato = todo("Tomato and egg", now = 2000)
        val first = importFile(chilli, "a.jpg", now = 1000)
        val second = importFile(tomato, "a.jpg", now = 2000)

        dao.setTaskAction(first, ItemAction.Custom("custom1"), enabled = true, now = 3000)

        assertEquals("custom1", rawText("SELECT customActions FROM task_state WHERE taskId = '$first'"))
        assertEquals("", rawText("SELECT customActions FROM task_state WHERE taskId = '$second'"))
    }

    // --- the reads that had to stop emitting a row per Asset ---------------------------------------

    @Test
    fun `a shared file is one row in the browser's ticks, ticked when either Task is`() = runTest {
        val chilli = todo("Chilli fried egg", now = 1000)
        val tomato = todo("Tomato and egg", now = 2000)
        val first = importFile(chilli, "a.jpg", now = 1000)
        importFile(tomato, "a.jpg", now = 2000)
        dao.registerFiles(listOf(fileState("a.jpg")))

        val before = dao.observeFileCompletion(ROOT).first()
        dao.setTaskCompleted(first, completed = true, now = 3000)
        val after = dao.observeFileCompletion(ROOT).first()

        // One row either way. Two rows would make the tick depend on which one the caller kept,
        // and the browser would flicker with the order the query happened to return.
        assertEquals(1, before.size)
        assertEquals(listOf("a.jpg"), after.map { it.relativePath })
        assertTrue("a file is ticked when any Task over it is", after.single().isCompleted)
    }

    @Test
    fun `a folder round reads a shared file once, with the decisions collapsed`() = runTest {
        val chilli = todo("Chilli fried egg", now = 1000)
        val tomato = todo("Tomato and egg", now = 2000)
        val first = importFile(chilli, "a.jpg", now = 1000)
        importFile(tomato, "a.jpg", now = 2000)
        dao.registerFiles(listOf(fileState("a.jpg")))
        dao.setTaskCompleted(first, completed = true, now = 3000)

        val states = dao.getFileStates(ROOT, listOf("a.jpg"))

        // One file, one row, one id on the wire — the round reports the file rather than either
        // Todo's opinion of it, and the mark in the browser is what says the opinions differ.
        assertEquals(1, states.size)
        assertEquals("a.jpg", states.single().relativePath)
        assertTrue(states.single().isCompleted)
    }

    @Test
    fun `the checked-file count counts files, not the Tasks over them`() = runTest {
        val chilli = todo("Chilli fried egg", now = 1000)
        val tomato = todo("Tomato and egg", now = 2000)
        val first = importFile(chilli, "a.jpg", now = 1000)
        val second = importFile(tomato, "a.jpg", now = 2000)
        dao.setTaskCompleted(first, completed = true, now = 3000)
        dao.setTaskCompleted(second, completed = true, now = 3000)

        // The card this feeds offers to delete *files*, so a file two Todos call done is still one
        // file. Counting rows would have offered to delete `a.jpg` twice.
        assertEquals(1, dao.observeCheckedFileCount(ROOT).first())
        assertEquals(listOf("a.jpg"), dao.getCheckedFilePaths(ROOT))
    }

    // --- which Todo holds what, and in which of the two capacities ---------------------------------

    @Test
    fun `every held file arrives as a claim, not only the contended ones`() = runTest {
        val chilli = todo("Chilli fried egg", now = 1000)
        val tomato = todo("Tomato and egg", now = 2000)
        importFile(chilli, "a.jpg", now = 1000)
        importFile(chilli, "b.jpg", now = 1100)
        importFile(tomato, "b.jpg", now = 2000)

        val claims = dao.observeFileClaims(ROOT).first()

        // Both files, not just the one two Todos hold. The read this replaced had a `> 1`, which is
        // exactly why the browser said nothing about any file in a folder imported once.
        assertEquals(listOf("a.jpg", "b.jpg"), claims.map { it.relativePath }.distinct())
        assertEquals(3, claims.size)
        // A file-as-Task Task is made over the very path its file sits at, so every claim here is
        // the row being its own Task.
        assertTrue(claims.all { it.groupPath == it.relativePath })
        assertEquals(
            listOf("Chilli fried egg", "Chilli fried egg", "Tomato and egg"),
            claims.map { it.sourceName }
        )
    }

    @Test
    fun `a folder-as-Task Todo holds the folder it grouped and the files inside it`() = runTest {
        val demo = todo("Demo", now = 1000, importer = ImporterKind.FOLDER_AS_TASK)
        importFolders(demo, files = listOf("Camera/a.jpg"), directories = listOf("Camera"), now = 1000)

        val claims = dao.observeFileClaims(ROOT).first()

        // Two claims and not three. The Task's own Asset sits at the Task's own path, and the read
        // drops that row rather than making callers work out that it means the same thing twice.
        assertEquals(2, claims.size)
        val folder = claims.single { it.relativePath == "Camera" }
        val file = claims.single { it.relativePath == "Camera/a.jpg" }
        assertEquals("Camera", folder.groupPath)
        assertEquals("Camera", file.groupPath)
        // Same groupPath, opposite answers: the folder *is* the Task, the file is content of it.
        // This one comparison is the whole of what the browser has to draw.
        assertEquals(folder.relativePath, folder.groupPath)
        assertNotEquals(file.relativePath, file.groupPath)
    }

    @Test
    fun `a file can be its own Task under two Todos and content of a third`() = runTest {
        val demo = todo("Demo", now = 1000, importer = ImporterKind.FOLDER_AS_TASK)
        val whatsapp = todo("Whatsapp", now = 2000)
        val duplicate = todo("Whatsapp（duplicate）", now = 3000)

        importFolders(
            demo,
            files = listOf("Whatsapp/IMG.jpg"),
            directories = listOf("Whatsapp"),
            now = 1000
        )
        importFile(whatsapp, "IMG.jpg", importRoot = "Whatsapp", now = 2000)
        importFile(duplicate, "IMG.jpg", importRoot = "Whatsapp", now = 3000)

        val states = dao.getSharedFileStates(ROOT, "Whatsapp/IMG.jpg")

        // The case the browser got wrong: three Todos over one file, and they are not three of the
        // same thing. Two made a Task of the file; Demo made a Task of the folder it sits in, and
        // the file is only one of the files that Task covers. Rendered alike, the drill-in told the
        // user all three had a Task of their own, which for one of them was not true.
        assertEquals(listOf("Demo", "Whatsapp", "Whatsapp（duplicate）"), states.map { it.sourceName })
        assertEquals(listOf(false, true, true), states.map { it.isOwnTask })
        // Still per Task, so the disagreement stays legible — the capacity is extra, not instead.
        assertEquals(3, states.size)

        val claims = dao.observeFileClaims(ROOT).first()
        // The folder itself is held only by the Todo that grouped it. The two file-as-Task Todos
        // made their Task over the file, so neither of them claims the folder above it.
        val folder = claims.single { it.relativePath == "Whatsapp" }
        assertEquals("Demo", folder.sourceName)
        assertEquals(folder.relativePath, folder.groupPath)
    }

    @Test
    fun `the drill-in names both Todos and each one's own decision`() = runTest {
        val chilli = todo("Chilli fried egg", now = 1000)
        val tomato = todo("Tomato and egg", now = 2000)
        val first = importFile(chilli, "a.jpg", now = 1000)
        val second = importFile(tomato, "a.jpg", now = 2000)
        dao.setTaskCompleted(first, completed = true, now = 3000)
        dao.setTaskAction(second, ItemAction.Custom("custom1"), enabled = true, now = 3000)

        val states = dao.getSharedFileStates(ROOT, "a.jpg")

        assertEquals(listOf("Chilli fried egg", "Tomato and egg"), states.map { it.sourceName })
        assertEquals(listOf(first, second), states.map { it.taskId })
        // Read per Task, so the disagreement is legible as one — which is the whole reason the
        // browser shows the mark rather than picking a winner for the tick beside it.
        assertEquals(listOf(true, false), states.map { it.isCompleted })
        assertEquals(listOf("", "custom1"), states.map { it.customActions })
        // Both Todos made a Task of this file rather than of a folder above it, which is what the
        // drill-in's sentence now has to be able to say.
        assertEquals(listOf(true, true), states.map { it.isOwnTask })
    }

    // --- the wire name, which used to be one per file because a file was one Task ------------------

    @Test
    fun `a shared file is named once, and the second Task is left to be named for itself`() = runTest {
        val chilli = todo("Chilli fried egg", now = 1000)
        val tomato = todo("Tomato and egg", now = 2000)
        importFile(chilli, "a.jpg", now = 1000)
        importFile(tomato, "a.jpg", now = 2000)
        dao.registerFiles(listOf(fileState("a.jpg")))

        // One row, not two. Two rows would be two Tasks reporting under one id, which a receiver
        // keyed on that id could not tell apart — and the file's own name is unaffected either
        // way, because the round reads it from the registration when no Asset carries it.
        assertEquals(1, rawInt("SELECT COUNT(*) FROM legacy_item_ids"))
        assertEquals("FILE-a.jpg", dao.getFileStates(ROOT, listOf("a.jpg")).single().publicId)
    }

    @Test
    fun `naming a file a second time does not name the other Todo's Asset`() = runTest {
        val chilli = todo("Chilli fried egg", now = 1000)
        val tomato = todo("Tomato and egg", now = 2000)
        importFile(chilli, "a.jpg", now = 1000)
        dao.registerFiles(listOf(fileState("a.jpg")))
        importFile(tomato, "a.jpg", now = 2000)

        // The second import runs the backfill again over the same path. The `NOT EXISTS` guard is
        // what keeps it from handing out a second copy of the same name.
        assertEquals(1, rawInt("SELECT COUNT(*) FROM legacy_item_ids"))
    }

    // --- Lists, which are what the whole model is for ----------------------------------------------

    @Test
    fun `one List holds Tasks from two Todos, and each keeps its own decision`() = runTest {
        val chilli = todo("Chilli fried egg", now = 1000)
        val tomato = todo("Tomato and egg", now = 2000)
        val first = importFile(chilli, "egg.jpg", now = 1000)
        val second = importFile(tomato, "tomato.jpg", now = 2000)

        val listId = dao.createListFromTasks("Shopping", listOf(first, second), false, "", now = 3000)
        dao.setTaskCompleted(first, completed = true, now = 4000)

        // The shopping list the cook reads: both markets' Tasks, one of them already bought. The
        // state reaches the List because the List holds the Task, not a copy of it.
        val items = dao.getItems(listId)
        assertEquals(listOf("egg.jpg", "tomato.jpg"), items.map { it.title })
        assertEquals(listOf(true, false), items.map { it.isCompleted })
    }

    @Test
    fun `adding Tasks to a List appends them after what it already holds`() = runTest {
        val chilli = todo("Chilli fried egg", now = 1000)
        val tomato = todo("Tomato and egg", now = 2000)
        val first = importFile(chilli, "egg.jpg", now = 1000)
        val second = importFile(tomato, "tomato.jpg", now = 2000)
        val listId = dao.createListFromTasks("Shopping", listOf(first), false, "", now = 3000)

        val added = dao.addTasksToList(listId, listOf(second), now = 4000)

        assertEquals(1, added)
        assertEquals(listOf("egg.jpg", "tomato.jpg"), dao.getItems(listId).map { it.title })
    }

    @Test
    fun `adding a Task the List already holds adds nothing and says so`() = runTest {
        val chilli = todo("Chilli fried egg", now = 1000)
        val first = importFile(chilli, "egg.jpg", now = 1000)
        val listId = dao.createListFromTasks("Shopping", listOf(first), false, "", now = 3000)

        val added = dao.addTasksToList(listId, listOf(first), now = 4000)

        // One Task at two positions is not a second opinion about it, and it would put the review
        // queue at the same Task twice.
        assertEquals(0, added)
        assertEquals(1, dao.getItems(listId).size)
    }

    @Test
    fun `the same Task in two Lists is one decision, seen from both`() = runTest {
        val chilli = todo("Chilli fried egg", now = 1000)
        val first = importFile(chilli, "egg.jpg", now = 1000)
        val shopping = dao.createListFromTasks("Shopping", listOf(first), false, "", now = 3000)
        val dinner = dao.createListFromTasks("Dinner", listOf(first), false, "", now = 3100)

        dao.setTaskCompleted(first, completed = true, now = 4000)

        // The boundary is the Task, so two Lists over one Task share the decision for free — the
        // property the opposite case (two Todos, one file) deliberately does not have.
        assertEquals(listOf(true), dao.getItems(shopping).map { it.isCompleted })
        assertEquals(listOf(true), dao.getItems(dinner).map { it.isCompleted })
    }

    @Test
    fun `adding a Task the catalog cannot reach writes nothing at all`() = runTest {
        val chilli = todo("Chilli fried egg", now = 1000)
        val first = importFile(chilli, "egg.jpg", now = 1000)
        val listId = dao.createListFromTasks("Shopping", listOf(first), false, "", now = 3000)

        val added = runCatching { dao.addTasksToList(listId, listOf("no-such-task"), now = 4000) }

        // Fails whole rather than partially, the way creation does: the good Task is not added
        // alongside a bad one, because a List half of what was asked for is a List nobody asked
        // for. The transaction is what makes that true rather than the order of the inserts.
        assertTrue(added.isFailure)
        assertEquals(1, dao.getItems(listId).size)
    }

    // --- helpers -----------------------------------------------------------------------------------

    /** A Todo over the grant root, named for the dish it is an intention to cook. */
    private suspend fun todo(
        name: String,
        now: Long,
        importer: ImporterKind = ImporterKind.FILE_AS_TASK
    ): String =
        dao.registerSource(
            rootUri = ROOT,
            relativePath = "",
            displayName = name,
            importerKind = importer,
            keepTree = true,
            now = now
        ).id

    /**
     * Imports one file under [sourceId] and answers with the Task it became.
     *
     * [importRoot] is the folder the run was started in, so [relativePath] is what the scan saw
     * inside it; the Task's own path is the two joined. The default is the grant root, which is the
     * ordinary case and leaves [relativePath] as the Task's path already.
     */
    private suspend fun importFile(
        sourceId: String,
        relativePath: String,
        now: Long,
        importRoot: String = ""
    ): String {
        dao.runImport(
            sourceId,
            importRoot,
            plan(
                ImporterKind.FILE_AS_TASK,
                importRoot,
                FolderSnapshot(
                    listOf(
                        ScannedFile(
                            name = relativePath.substringAfterLast('/'),
                            relativePath = relativePath,
                            uri = "$ROOT/${childRelativePath(importRoot, relativePath)}"
                        )
                    ),
                    emptyList()
                )
            ),
            now
        )
        val groupPath = childRelativePath(importRoot, relativePath)
        return dao.getCatalogTasks(sourceId).first { it.groupPath == groupPath }.taskId
    }

    /**
     * Runs a folder-as-Task import over the grant root: one Task per immediate child folder, whose
     * Assets are the [files] inside it.
     *
     * No coverage parameter, because this importer refuses one: a Task is assembled from a whole
     * subtree, so a run whose coverage is not the Source's own root is declined rather than allowed
     * to write a group whose Asset list is wrong. [todo] always roots a Todo at the grant root.
     */
    private suspend fun importFolders(
        sourceId: String,
        files: List<String>,
        directories: List<String>,
        now: Long
    ) {
        dao.runImport(
            sourceId,
            "",
            plan(
                ImporterKind.FOLDER_AS_TASK,
                "",
                FolderSnapshot(
                    files.map {
                        ScannedFile(
                            name = it.substringAfterLast('/'),
                            relativePath = it,
                            uri = "$ROOT/$it"
                        )
                    },
                    directories
                )
            ),
            now
        )
    }

    private fun fileState(relativePath: String) = FileReviewStateEntity(
        publicId = "FILE-" + relativePath.substringAfterLast('/'),
        rootUri = ROOT,
        relativePath = relativePath,
        updatedAt = 1000
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
