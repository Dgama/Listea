package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The three facts v0.2 must not change while it rebuilds everything underneath them.
 *
 * A decision about a file lives on the file, so every list holding it sees it; progress is counted
 * from that resolved decision rather than from any row's own column; and a list completes when its
 * last unresolved item resolves, from whichever screen did the resolving.
 *
 * Real SQLite through Room rather than a fake, because all three are properties of the SQL: the
 * projection is a CASE over a LEFT JOIN, and a test that mocked the DAO would be asserting the
 * mock instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListsDaoTest {

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

    // --- a decision belongs to the file, not to a list row ---------------------------------------

    @Test
    fun `checking a file writes its Task's state and no older column`() = runTest {
        val listId = folderList(folderPath = "2026-07-11", files = listOf("a.jpg"))
        dao.registerFiles(listOf(fileState("2026-07-11/a.jpg")))

        dao.setFileCompleted(ROOT, "2026-07-11/a.jpg", completed = true, now = 2000)

        // One file in this fixture, so one Task and one state row: no subquery needed to name it.
        assertEquals(1, rawInt("SELECT isCompleted FROM task_state"))
        // The reading that counts resolves through the Task...
        assertEquals(true, dao.getItems(listId).single().isCompleted)
        // ...and both columns a decision used to be written to stay at their defaults, because
        // neither is written any more. A stale value in either is a second answer that could
        // disagree with the one the app shows, which is the whole defect this replaces.
        assertEquals(0, rawInt("SELECT isCompleted FROM list_items"))
        assertEquals(0, rawInt("SELECT isCompleted FROM file_review_state"))
    }

    @Test
    fun `every list holding a file sees one decision about it`() = runTest {
        // Two scopes that overlap: one over the root, one over the folder the file sits in. Both
        // resolve the same file to the same root-relative path, which is the whole point of
        // storing a decision against a path rather than against a row.
        val overRoot = folderList(folderPath = "", files = listOf("2026-07-11/a.jpg"), title = "All")
        val overFolder = folderList(folderPath = "2026-07-11", files = listOf("a.jpg"), title = "Day")
        dao.registerFiles(listOf(fileState("2026-07-11/a.jpg")))

        val transitioned = dao.setFileCompleted(ROOT, "2026-07-11/a.jpg", completed = true, now = 2000)

        assertEquals(true, dao.getItems(overRoot).single().isCompleted)
        assertEquals(true, dao.getItems(overFolder).single().isCompleted)
        // Nothing here assumes a file belongs to at most one list.
        assertEquals(setOf(overRoot, overFolder), transitioned.toSet())
    }

    @Test
    fun `a decision taken where no list is looking is still the file's decision`() = runTest {
        // Quick Review's own sequence: register the file, then decide about it. No List takes part.
        val listId = folderList(folderPath = "2026-07-11", files = listOf("a.jpg"))
        dao.registerFiles(listOf(fileState("2026-07-11/a.jpg")))

        dao.setFileCompleted(ROOT, "2026-07-11/a.jpg", completed = true, now = 2000)

        // The list the folder belongs to was told, without the folder review knowing it exists.
        assertEquals(true, dao.getItems(listId).single().isCompleted)
    }

    @Test
    fun `registering a file asserts nothing about it`() = runTest {
        val listId = folderList(folderPath = "2026-07-11", files = listOf("a.jpg"))
        dao.setFileCompleted(ROOT, "2026-07-11/a.jpg", completed = true, now = 2000)

        // IGNORE, not REPLACE: resolving the folder for review must never undo last month's work.
        dao.registerFiles(listOf(fileState("2026-07-11/a.jpg")))

        assertEquals(true, dao.getItems(listId).single().isCompleted)
    }

    // --- progress is projected from the resolved decision ----------------------------------------

    @Test
    fun `list progress counts the file's decision, not the row's`() = runTest {
        val listId = folderList(folderPath = "2026-07-11", files = listOf("a.jpg", "b.jpg"))
        dao.registerFiles(listOf(fileState("2026-07-11/a.jpg"), fileState("2026-07-11/b.jpg")))

        dao.setFileCompleted(ROOT, "2026-07-11/a.jpg", completed = true, now = 2000)

        val summary = dao.observeSummaries().first().single()
        assertEquals(listId, summary.id)
        assertEquals(2, summary.totalItems)
        assertEquals(1, summary.completedItems)
        assertNull(summary.completedAt)
    }

    @Test
    fun `a file nobody has decided about counts as unchecked`() = runTest {
        // A list is a snapshot of a folder, so its files have no state row until something
        // resolves them; COALESCE is what keeps that from reading as "already done".
        folderList(folderPath = "2026-07-11", files = listOf("a.jpg"))

        val summary = dao.observeSummaries().first().single()

        assertEquals(1, summary.totalItems)
        assertEquals(0, summary.completedItems)
    }

    @Test
    fun `a manual item's progress is its own row`() = runTest {
        val listId = folderList(folderPath = "2026-07-11", files = listOf("a.jpg"))
        dao.addItem(listId, "Buy milk", now = 1000)

        val manual = dao.getItems(listId).single { it.sourceRelativePath == null }
        dao.setManualItemCompleted(listId, manual.id, completed = true, now = 2000)

        val summary = dao.observeSummaries().first().single()
        assertEquals(2, summary.totalItems)
        assertEquals(1, summary.completedItems)
    }

    @Test
    fun `folder scope progress resolves the same way`() = runTest {
        val listId = folderList(folderPath = "2026-07-11", files = listOf("a.jpg", "b.jpg"))
        dao.registerFiles(listOf(fileState("2026-07-11/a.jpg")))
        dao.setFileCompleted(ROOT, "2026-07-11/a.jpg", completed = true, now = 2000)

        val scope = dao.observeFolderScopes(ROOT).first().single()

        assertEquals(listId, scope.id)
        assertEquals("2026-07-11", scope.relativePath)
        assertEquals(2, scope.totalItems)
        assertEquals(1, scope.completedItems)
    }

    // --- completion transitions ------------------------------------------------------------------

    @Test
    fun `a folder review can complete a list it has never heard of`() = runTest {
        val listId = folderList(folderPath = "2026-07-11", files = listOf("a.jpg"))
        dao.registerFiles(listOf(fileState("2026-07-11/a.jpg")))

        val transitioned = dao.setFileCompleted(ROOT, "2026-07-11/a.jpg", completed = true, now = 2000)

        assertEquals(listOf(listId), transitioned)
        assertNotNull(dao.getList(listId)?.completedAt)
    }

    @Test
    fun `a transition is reported once, and only on the way up`() = runTest {
        val listId = folderList(folderPath = "2026-07-11", files = listOf("a.jpg", "b.jpg"))
        dao.registerFiles(listOf(fileState("2026-07-11/a.jpg"), fileState("2026-07-11/b.jpg")))
        dao.setFileCompleted(ROOT, "2026-07-11/a.jpg", completed = true, now = 2000)

        val whenItCompletes = dao.setFileCompleted(ROOT, "2026-07-11/b.jpg", completed = true, now = 3000)
        // Unchecking and rechecking is not a second completion: the webhook fires on the way up.
        val whenItIsUndone = dao.setFileCompleted(ROOT, "2026-07-11/b.jpg", completed = false, now = 4000)
        val whenItCompletesAgain = dao.setFileCompleted(ROOT, "2026-07-11/b.jpg", completed = true, now = 5000)

        assertEquals(listOf(listId), whenItCompletes)
        assertTrue(whenItIsUndone.isEmpty())
        assertEquals(listOf(listId), whenItCompletesAgain)
    }

    @Test
    fun `the original completion time survives an edit that does not break completion`() = runTest {
        val listId = folderList(folderPath = "2026-07-11", files = listOf("a.jpg", "b.jpg"))
        dao.registerFiles(listOf(fileState("2026-07-11/a.jpg"), fileState("2026-07-11/b.jpg")))
        dao.setFileCompleted(ROOT, "2026-07-11/b.jpg", completed = true, now = 2000)
        dao.setFileCompleted(ROOT, "2026-07-11/a.jpg", completed = true, now = 3000)

        // A second write that leaves the list complete must not restamp it.
        dao.setFileCompleted(ROOT, "2026-07-11/a.jpg", completed = true, now = 9000)

        assertEquals(3000L, dao.getList(listId)?.completedAt ?: 0L)
    }

    @Test
    fun `adding an item to a complete list makes it incomplete again`() = runTest {
        val listId = folderList(folderPath = "2026-07-11", files = listOf("a.jpg"))
        dao.registerFiles(listOf(fileState("2026-07-11/a.jpg")))
        dao.setFileCompleted(ROOT, "2026-07-11/a.jpg", completed = true, now = 2000)
        assertNotNull(dao.getList(listId)?.completedAt)

        val transitioned = dao.addItem(listId, "Buy milk", now = 3000)

        assertEquals(false, transitioned)
        assertNull(dao.getList(listId)?.completedAt)
    }

    @Test
    fun `a manual list completes on its last item, once`() = runTest {
        val listId = manualList()
        dao.addItem(listId, "Buy milk", now = 1000)
        dao.addItem(listId, "Buy bread", now = 1000)

        val milk = dao.getItems(listId).first { it.title == "Buy milk" }
        assertEquals(false, dao.setManualItemCompleted(listId, milk.id, true, now = 2000))
        val bread = dao.getItems(listId).first { it.title == "Buy bread" }
        assertEquals(true, dao.setManualItemCompleted(listId, bread.id, true, now = 3000))

        assertNotNull(dao.getList(listId)?.completedAt)
    }

    @Test
    fun `a list with no items left is not a complete one`() = runTest {
        val listId = manualList()
        dao.addItem(listId, "Buy milk", now = 1000)
        val milk = dao.getItems(listId).single()
        dao.setManualItemCompleted(listId, milk.id, true, now = 2000)
        assertNotNull(dao.getList(listId)?.completedAt)

        // Vacuously all of nothing is checked; removing the last item is not a completion.
        dao.removeItem(listId, milk.id, now = 3000)

        assertNull(dao.getList(listId)?.completedAt)
    }

    // --- a folder review gives what it shows an identity to be judged by -------------------------

    @Test
    fun `a folder review gives every file it shows a Task`() = runTest {
        // Nothing has ever been decided about either file. Registration alone leaves no Task behind,
        // and a card with no Task is one a session cannot walk and a judgment cannot land on.
        dao.registerFiles(listOf(fileState("2026-07-11/a.jpg"), fileState("2026-07-11/b.jpg")))

        val resolved = dao.ensureTasksForFiles(
            ROOT,
            listOf("2026-07-11/a.jpg", "2026-07-11/b.jpg"),
            now = 2000
        )

        assertEquals(2, resolved.map { it.taskId }.toSet().size)
        // Every Task has exactly one state, and this is where a Task without one would come from —
        // so the invariant the whole read model is projected through is asserted at its source.
        assertEquals(2, rawInt("SELECT COUNT(*) FROM tasks"))
        assertEquals(2, rawInt("SELECT COUNT(*) FROM task_state"))
    }

    @Test
    fun `a file already decided about keeps the Task that decision was recorded against`() = runTest {
        // Decided from a List first, then met again in a folder review. A second Task here would
        // put one file in two queues under two identities, and leave the user's existing checkmark
        // answering for the one they are not looking at.
        dao.setFileCompleted(ROOT, "2026-07-11/a.jpg", completed = true, now = 2000)
        val decided = dao.ensureTasksForFiles(ROOT, listOf("2026-07-11/a.jpg"), now = 3000).single()

        // Looking again answers the same thing...
        val again = dao.ensureTasksForFiles(ROOT, listOf("2026-07-11/a.jpg"), now = 4000).single()

        assertEquals(decided.taskId, again.taskId)
        assertEquals(1, rawInt("SELECT COUNT(*) FROM tasks"))
        // ...and opening a folder says nothing about what is in it.
        assertEquals(
            1,
            rawInt("SELECT isCompleted FROM task_state WHERE taskId = '${decided.taskId}'")
        )
    }

    @Test
    fun `the answer follows the listing rather than the database's order`() = runTest {
        // The queue is built by zipping this against the folder listing, so an answer in any other
        // order hands the wrong Task to every card after the first one that differs.
        val paths = listOf("c.jpg", "a.jpg", "b.jpg")

        val resolved = dao.ensureTasksForFiles(ROOT, paths, now = 2000)

        assertEquals(paths, resolved.map { it.relativePath })
    }

    // --- helpers ---------------------------------------------------------------------------------

    private suspend fun folderList(
        folderPath: String,
        files: List<String>,
        title: String = "Trip"
    ): Long = dao.createFolderList(
        title = title,
        rootUri = ROOT,
        relativePath = folderPath,
        files = files.map {
            ScannedFile(name = it, relativePath = it, uri = "$ROOT/$folderPath/$it")
        },
        replacedListIds = emptyList(),
        webhookEnabled = false,
        webhookUrl = "",
        now = 1000
    )

    /** A list with no source folder, which is the only kind that can hold manual items. */
    private suspend fun manualList(): Long = dao.insertList(
        ListEntity(title = "Shopping", createdAt = 1000)
    )

    private fun fileState(rootRelativePath: String) = FileReviewStateEntity(
        publicId = "FILE-$rootRelativePath",
        rootUri = ROOT,
        relativePath = rootRelativePath,
        updatedAt = 1000
    )

    private fun rawInt(sql: String): Int =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            assertTrue("no row for: $sql", cursor.moveToFirst())
            cursor.getInt(0)
        }

    private companion object {
        const val ROOT = "content://root"
    }
}
