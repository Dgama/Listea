package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.FolderSnapshot
import com.example.listea.ImporterKind
import com.example.listea.plan
import kotlinx.coroutines.flow.first
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
 * What a List looks like after a Todo it held Tasks from was deleted.
 *
 * Two questions, and the first is why the membership removal is not a design choice. The Lists index
 * counts memberships with a `LEFT JOIN` to `task_state`, and the detail screen resolves them with an
 * inner one, so a membership whose Task is gone is *counted* by the index and *not drawn* by the
 * screen — a List reading "3 items, 2 checked" over two rows. Leaving the row is therefore not a
 * tidier option that was declined; it is a bug, and the first test is what says so.
 *
 * The second is the note. A List that lost rows is otherwise indistinguishable from one that never
 * had them, and the note is the whole of what the app knows about why it is shorter than the user
 * remembers. It is about a moment, so it is cleared by the List moving on — but only by moving on,
 * which is why a re-sync that found nothing new is asserted to leave it standing.
 *
 * Everything here is real SQL through Room, for the reason [ListsDaoTest] gives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListAfterDissolutionTest {

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

    // --- the two reads agree ---------------------------------------------------------------------

    @Test
    fun `a List whose Tasks went is empty to both reads, not short to one of them`() = runTest {
        val todo = importFiles("a.jpg", "b.jpg")
        val listId = listOver(todo, "Trip")

        dao.deleteTodo(todo, keepState = false, now = 5000)

        val summary = dao.observeSummaries().first().single()
        assertEquals(0, summary.totalItems)
        assertEquals(0, summary.completedItems)
        assertEquals(0, dao.observeItems(listId).first().size)
    }

    @Test
    fun `a Task cannot go while a List still names it, which is the order the delete is written in`() =
        runTest {
            val todo = importFiles("a.jpg", "b.jpg")
            val listId = listOver(todo, "Trip")
            val taskId = dao.getCatalogTasks(todo).first().taskId

            // `list_items.taskId` is NO ACTION, so the memberships have to go first and the schema
            // says so rather than a comment. That is not tidiness: a delete written the other way
            // round fails here, on a device, and fails *after* it has taken some of the Todo.
            val early = runCatching { rawExec("DELETE FROM tasks WHERE id = '$taskId'") }
            assertTrue(
                "deleting a Task a List names: ${early.exceptionOrNull()}",
                early.exceptionOrNull()?.message.orEmpty().contains("FOREIGN KEY")
            )

            dao.deleteTodo(todo, keepState = false, now = 5000)

            // And the same constraint is what rules out the opposite mistake. A membership whose
            // Task is gone would be counted by the index — a `LEFT JOIN` — and not drawn by the
            // detail screen, an inner one, so the List would read "3 items" over two rows. That row
            // cannot be left behind and cannot be put back, because it cannot exist at all.
            val orphan = runCatching {
                rawExec(
                    "INSERT INTO list_items (publicId, listId, taskId, title, isCompleted, " +
                        "sortOrder, createdAt, sourceMissing, isFavorite, customActions) VALUES " +
                        "('ITEM-A', $listId, '$taskId', 'a.jpg', 0, 0, 1000, 0, 0, '')"
                )
            }
            assertTrue(
                "an orphan membership: ${orphan.exceptionOrNull()}",
                orphan.exceptionOrNull()?.message.orEmpty().contains("FOREIGN KEY")
            )
        }

    // --- the note --------------------------------------------------------------------------------

    @Test
    fun `the List that lost rows says how many, and the one that did not says nothing`() = runTest {
        val todo = importFiles("a.jpg", "b.jpg")
        val holder = listOver(todo, "Trip")
        val bystander = dao.createListFromTasks(
            title = "Elsewhere",
            taskIds = emptyList(),
            webhookEnabled = false,
            webhookUrl = "",
            now = 1000
        )

        dao.deleteTodo(todo, keepState = false, now = 5000)

        val summaries = dao.observeSummaries().first().associateBy { it.id }
        assertEquals(2, summaries.getValue(holder).lastDissolvedCount)
        assertEquals(5000L, summaries.getValue(holder).lastDissolvedAt)
        assertEquals(null, summaries.getValue(bystander).lastDissolvedCount)
        assertEquals(null, summaries.getValue(bystander).lastDissolvedAt)
    }

    @Test
    fun `adding Tasks to the List forgets the note, because the List has moved on`() = runTest {
        val todo = importFiles("a.jpg", "b.jpg")
        val listId = listOver(todo, "Trip")
        dao.deleteTodo(todo, keepState = false, now = 5000)
        assertEquals(2, summaryOf(listId).lastDissolvedCount)

        // A second Todo over the same folder, and its Tasks put into the List the first one left.
        val replacement = importFiles("a.jpg")
        val fresh = dao.getCatalogTasks(replacement).map { it.taskId }
        assertEquals(fresh.size, dao.addTasksToList(listId, fresh, now = 6000))

        // "2 items removed when a Todo was deleted" is no longer true of a List that has rows in it
        // again, and a note the screen cannot reconcile with what is under it is worse than none.
        assertEquals(null, summaryOf(listId).lastDissolvedCount)
        assertEquals(null, summaryOf(listId).lastDissolvedAt)
    }

    @Test
    fun `an addition that put nothing in leaves the note standing`() = runTest {
        val todo = importFiles("a.jpg", "b.jpg")
        val listId = listOver(todo, "Trip")
        dao.deleteTodo(todo, keepState = false, now = 5000)

        // Asking for a Task the List already has writes nothing, so nothing has moved on. Reporting
        // a change here would clear a note every time somebody pressed Add twice.
        assertEquals(0, dao.addTasksToList(listId, emptyList(), now = 6000))

        assertEquals(2, summaryOf(listId).lastDissolvedCount)
    }

    @Test
    fun `a re-sync that puts the files back forgets the note`() = runTest {
        val todo = importFiles("a.jpg", "b.jpg")
        val listId = folderListOver("Trip")
        dao.deleteTodo(todo, keepState = false, now = 5000)
        assertEquals(2, summaryOf(listId).lastDissolvedCount)

        // The source is re-registered, because the delete took the Todo's own row with it, and the
        // folder is scanned again by the same grant.
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = "photos",
            displayName = "Photos",
            importerKind = ImporterKind.FILE_AS_TASK,
            keepTree = true,
            now = 6000
        )
        dao.applyReconcile(
            listId = listId,
            source = source,
            files = listOf("a.jpg", "b.jpg").map { scanned(it) },
            now = 6000
        )

        assertEquals(2, summaryOf(listId).totalItems)
        assertEquals(null, summaryOf(listId).lastDissolvedCount)
    }

    @Test
    fun `a re-sync that finds what it already had leaves the note standing`() = runTest {
        val todo = importFiles("a.jpg", "b.jpg")
        val listId = folderListOver("Trip")
        // A note written while the rows are still there: nothing was dissolved in this story, so
        // this is the case where a refresh has nothing to reconcile with the note and must not
        // silently erase it.
        rawExec("UPDATE lists SET lastDissolvedCount = 5, lastDissolvedAt = 4000 WHERE id = $listId")

        val source = dao.getSource(todo)!!
        dao.applyReconcile(
            listId = listId,
            source = source,
            files = listOf("a.jpg", "b.jpg").map { scanned(it) },
            now = 6000
        )

        assertEquals(2, summaryOf(listId).totalItems)
        assertEquals(5, summaryOf(listId).lastDissolvedCount)
    }

    // --- the fixture -----------------------------------------------------------------------------

    private suspend fun summaryOf(listId: Long): ListSummary =
        dao.observeSummaries().first().first { it.id == listId }

    private fun scanned(name: String) = ScannedFile(
        name = name,
        relativePath = name,
        uri = "$ROOT/photos/$name"
    )

    private suspend fun importFiles(vararg names: String): String {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = "photos",
            displayName = "Photos",
            importerKind = ImporterKind.FILE_AS_TASK,
            keepTree = true,
            now = 1000
        )
        dao.runImport(
            source.id,
            "photos",
            plan(
                ImporterKind.FILE_AS_TASK,
                "photos",
                FolderSnapshot(names.map { scanned(it) }, emptyList())
            ),
            2000
        )
        return source.id
    }

    /** A List over every Task the Todo holds, with no folder of its own. */
    private suspend fun listOver(sourceId: String, title: String): Long =
        dao.createListFromTasks(
            title = title,
            taskIds = dao.getCatalogTasks(sourceId).map { it.taskId },
            webhookEnabled = false,
            webhookUrl = "",
            now = 3000
        )

    /**
     * A List over the folder the Todo already covers, made the way the app makes one.
     *
     * [ListsDao.createFolderList] reuses the covering Source rather than registering a second one,
     * so this List's rows are the Todo's own Tasks — which is what the two tests using it are about.
     * Built by hand as `createListFromTasks` plus a `sourceRelativePath` it would have been a state
     * the app cannot produce: a List that looks folder-linked with root-relative rows under it, and
     * [ListsDao.applyReconcile] would have found every file added and every row missing.
     */
    private suspend fun folderListOver(title: String): Long =
        dao.createFolderList(
            title = title,
            rootUri = ROOT,
            relativePath = "photos",
            files = listOf("a.jpg", "b.jpg").map { scanned(it) },
            replacedListIds = emptyList(),
            webhookEnabled = false,
            webhookUrl = "",
            now = 3000
        )

    private fun rawExec(sql: String) {
        database.openHelper.writableDatabase.execSQL(sql)
    }

    private companion object {
        const val ROOT = "content://root"
    }
}
