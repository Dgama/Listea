package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every reader that answers "is this file completed right now", pinned across the feature
 * boundaries the source of truth has to cross.
 *
 * These are not tests of a migration or of a query's shape. They are the promises a user would
 * notice breaking: the folder browser's ticks, a List's progress bar, the count on the card that
 * offers to delete checked files, and — the one that costs something irreversible — the exact set
 * of files that delete takes. Those four read the same fact through four different queries, and a
 * source-of-truth change that moved three of them would leave the fourth quietly lying.
 *
 * Written before the switch and deliberately phrased in terms of what is *shown*, not where the
 * value is stored, so the same assertions have to hold on both sides of it — they were run against
 * the old implementation and passed unchanged, which is the only thing that makes them evidence.
 *
 * The one exception is `a stale column on the file's own row is never the answer`, which asserts
 * the *new* invariant and so was the only failure before the switch: until then the file's own row
 * genuinely was the answer, and that is precisely what this step stops being true. It is kept
 * beside the others rather than in a file of its own because it is the same question — which copy
 * of the state is trusted — asked from the other side.
 *
 * The writes are addressed by Task id, resolved through [taskIdOf] the way a folder review resolves
 * one; the assertions stay about what each reader shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CurrentStateTest {

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

    // --- which files the delete path would take --------------------------------------------------

    @Test
    fun `checked files are the completed ones under the root, in path order`() = runTest {
        folderList(folderPath = "", files = listOf("b.jpg", "a.jpg", "Zoo/c.jpg"))
        registerFiles("a.jpg", "b.jpg", "Zoo/c.jpg")

        dao.setTaskCompleted(dao.taskIdOf(ROOT, "b.jpg"), completed = true, now = 2000)
        dao.setTaskCompleted(dao.taskIdOf(ROOT, "Zoo/c.jpg"), completed = true, now = 2000)

        // Case-insensitive, so a receiver that reads the list sees it in the order it was scanned.
        assertEquals(listOf("b.jpg", "Zoo/c.jpg"), dao.getCheckedFilePaths(ROOT))
    }

    @Test
    fun `a file checked where no list is looking is still one the delete can take`() = runTest {
        // Quick Review over a folder no List covers. The feature that deletes checked files has to
        // see this file too, or it is lying about what "checked" means.
        dao.setTaskCompleted(dao.taskIdOf(ROOT, "lonely.jpg"), completed = true, now = 2000)

        assertEquals(listOf("lonely.jpg"), dao.getCheckedFilePaths(ROOT))
    }

    @Test
    fun `an unchecked file is not one the delete would take`() = runTest {
        folderList(folderPath = "", files = listOf("a.jpg"))
        registerFiles("a.jpg")
        val taskId = dao.taskIdOf(ROOT, "a.jpg")

        dao.setTaskCompleted(taskId, completed = true, now = 2000)
        dao.setTaskCompleted(taskId, completed = false, now = 3000)

        assertEquals(emptyList<String>(), dao.getCheckedFilePaths(ROOT))
    }

    @Test
    fun `the count on the card is the number of files the delete would take`() = runTest {
        folderList(folderPath = "", files = listOf("a.jpg", "b.jpg", "c.jpg"))
        registerFiles("a.jpg", "b.jpg", "c.jpg")
        dao.setTaskCompleted(dao.taskIdOf(ROOT, "a.jpg"), completed = true, now = 2000)
        dao.setTaskCompleted(dao.taskIdOf(ROOT, "c.jpg"), completed = true, now = 2000)

        // The card and the confirmation dialog are built by two different queries, and a card that
        // promises three files while the dialog lists two is the worst version of this bug.
        assertEquals(dao.getCheckedFilePaths(ROOT).size, dao.observeCheckedFileCount(ROOT).first())
        assertEquals(2, dao.observeCheckedFileCount(ROOT).first())
    }

    @Test
    fun `deleting the checked files takes exactly them, and keeps every decision`() = runTest {
        val listId = folderList(folderPath = "", files = listOf("a.jpg", "b.jpg"))
        registerFiles("a.jpg", "b.jpg")
        dao.setTaskCompleted(dao.taskIdOf(ROOT, "a.jpg"), completed = true, now = 2000)
        dao.setTaskCompleted(dao.taskIdOf(ROOT, "b.jpg"), completed = true, now = 2000)

        val taken = dao.getCheckedFilePaths(ROOT)
        dao.markFilesDeleted(ROOT, taken)

        // Nothing is left to offer, because the only two checked files are the two that went.
        assertEquals(emptyList<String>(), dao.getCheckedFilePaths(ROOT))
        assertEquals(0, dao.observeCheckedFileCount(ROOT).first())
        // The decision outlives the file, which is the whole reason deleting flags rather than
        // erases: a list whose files were deleted must not silently become incomplete again.
        assertEquals(true, dao.getItems(listId).first { it.title == "a.jpg" }.isCompleted)
        val summary = dao.observeSummaries().first().single()
        assertEquals(2, summary.completedItems)
    }

    @Test
    fun `a file that came back is offered for deletion again`() = runTest {
        folderList(folderPath = "", files = listOf("a.jpg"))
        registerFiles("a.jpg")
        dao.setTaskCompleted(dao.taskIdOf(ROOT, "a.jpg"), completed = true, now = 2000)
        dao.markFilesDeleted(ROOT, listOf("a.jpg"))
        assertEquals(emptyList<String>(), dao.getCheckedFilePaths(ROOT))

        // A folder resolution found it present again, which is the only thing that un-flags it.
        dao.markFilesPresent(ROOT, listOf("a.jpg"))

        assertEquals(listOf("a.jpg"), dao.getCheckedFilePaths(ROOT))
    }

    // --- the folder browser's ticks ---------------------------------------------------------------

    @Test
    fun `a registered file nobody has decided about still has a tick, and it is empty`() = runTest {
        // Registering a file for review must never assert anything about it. The browser shows an
        // empty tick for these rather than no tick at all, because a folder it has been asked to
        // review is a folder it has a relationship with.
        registerFiles("a.jpg")

        assertEquals(mapOf("a.jpg" to false), dao.observeFileCompletion(ROOT).first().associate {
            it.relativePath to it.isCompleted
        })
    }

    @Test
    fun `folder ticks report each registered file's own state`() = runTest {
        registerFiles("a.jpg", "b.jpg")
        dao.setTaskCompleted(dao.taskIdOf(ROOT, "a.jpg"), completed = true, now = 2000)

        assertEquals(
            mapOf("a.jpg" to true, "b.jpg" to false),
            dao.observeFileCompletion(ROOT).first().associate { it.relativePath to it.isCompleted }
        )
    }

    @Test
    fun `one decision shows on the folder's tick and on the list's row together`() = runTest {
        val listId = folderList(folderPath = "", files = listOf("a.jpg"))
        registerFiles("a.jpg")

        dao.setTaskCompleted(dao.taskIdOf(ROOT, "a.jpg"), completed = true, now = 2000)

        // Two queries over one fact. The list's page is the one that did the deciding; the browser
        // ticks are the one that has to notice without being told.
        assertTrue(dao.observeFileCompletion(ROOT).first().single().isCompleted)
        assertTrue(dao.getItems(listId).single().isCompleted)
    }

    // --- what a review queue is handed ------------------------------------------------------------

    @Test
    fun `an undecided file is still in the queue, carrying nothing decided`() = runTest {
        registerFiles("a.jpg", "b.jpg")
        dao.setTaskCompleted(dao.taskIdOf(ROOT, "a.jpg"), completed = true, now = 2000)

        val states = dao.observeFileStates(ROOT, listOf("a.jpg", "b.jpg")).first()

        // The queue exists to show the work still to do, so a file nobody has judged must come
        // back. Dropping it would leave a review round showing only what has already been done.
        assertEquals(setOf("a.jpg", "b.jpg"), states.map { it.relativePath }.toSet())
        assertEquals(
            ReviewDecisions(isCompleted = true),
            states.first { it.relativePath == "a.jpg" }.decisions
        )
        assertEquals(ReviewDecisions(), states.first { it.relativePath == "b.jpg" }.decisions)
    }

    @Test
    fun `the same path under two roots is two files with two decisions`() = runTest {
        registerFiles("a.jpg")
        dao.registerFiles(
            listOf(
                FileReviewStateEntity(
                    publicId = "FILE-other-a",
                    rootUri = OTHER,
                    relativePath = "a.jpg",
                    updatedAt = 1000
                )
            )
        )
        dao.setTaskCompleted(dao.taskIdOf(ROOT, "a.jpg"), completed = true, now = 2000)

        // A path is only unique *under a grant*, and `(rootUri, relativePath)` is what the Asset's
        // uniqueness is declared on. Resolving a decision by path alone would hand one root's
        // review the other root's answer.
        assertEquals(listOf("a.jpg"), dao.getCheckedFilePaths(ROOT))
        assertEquals(emptyList<String>(), dao.getCheckedFilePaths(OTHER))
        assertEquals(
            ReviewDecisions(isCompleted = true),
            dao.getFileStates(ROOT, listOf("a.jpg")).single().decisions
        )
        assertEquals(
            ReviewDecisions(),
            dao.getFileStates(OTHER, listOf("a.jpg")).single().decisions
        )
    }

    // --- the one-shot reads a payload is built from -----------------------------------------------

    @Test
    fun `the one-shot read agrees with the observed one`() = runTest {
        registerFiles("a.jpg", "b.jpg")
        dao.setTaskCompleted(dao.taskIdOf(ROOT, "a.jpg"), completed = true, now = 2000)
        dao.setTaskAction(dao.taskIdOf(ROOT, "b.jpg"), ItemAction.Favourite, enabled = true, now = 2000)

        val observed = dao.observeFileStates(ROOT, listOf("a.jpg", "b.jpg")).first()
            .associate { it.relativePath to it.decisions }
        val oneShot = dao.getFileStates(ROOT, listOf("a.jpg", "b.jpg"))
            .associate { it.relativePath to it.decisions }

        // A live queue and a payload built beside it diverging is how a receiver ends up told
        // something the screen never showed.
        assertEquals(observed, oneShot)
        assertEquals(ReviewDecisions(isCompleted = true), observed.getValue("a.jpg"))
        assertEquals(ReviewDecisions(isFavorite = true), observed.getValue("b.jpg"))
    }

    // --- the state is read from one place, and the others are not consulted -----------------------

    @Test
    fun `a stale column on the file's own row is never the answer`() = runTest {
        registerFiles("a.jpg")
        dao.setTaskCompleted(dao.taskIdOf(ROOT, "a.jpg"), completed = true, now = 2000)

        // A value left behind by an older version, or by a write that went somewhere else. Whichever
        // copy the reads consult, it must not be this one — a second answer that can disagree is
        // how a list shows one thing and its folder shows another.
        rawExec("UPDATE file_review_state SET isCompleted = 0 WHERE relativePath = 'a.jpg'")
        rawExec("UPDATE list_items SET isCompleted = 0")

        assertTrue(dao.observeFileCompletion(ROOT).first().single().isCompleted)
        assertEquals(listOf("a.jpg"), dao.getCheckedFilePaths(ROOT))
        assertEquals(true, dao.getFileStates(ROOT, listOf("a.jpg")).single().decisions.isCompleted)
    }

    /**
     * The same question asked about presence, and it is a separate answer: a decision is one thing
     * the file's row used to hold, and whether the file is there at all is another.
     */
    @Test
    fun `a stale presence flag on the registration is never the answer either`() = runTest {
        folderList(folderPath = "", files = listOf("a.jpg"))
        registerFiles("a.jpg")
        dao.setTaskCompleted(dao.taskIdOf(ROOT, "a.jpg"), completed = true, now = 2000)

        // A value the migration wrote and nothing has maintained since. Reading it would report a
        // file that is sitting right there as gone, and drop it out of a deletion the user asked
        // for without ever saying so.
        rawExec("UPDATE file_review_state SET sourceMissing = 1 WHERE relativePath = 'a.jpg'")

        assertEquals(listOf("a.jpg"), dao.getCheckedFilePaths(ROOT))
        assertEquals(1, dao.observeCheckedFileCount(ROOT).first())
        assertFalse(dao.getFileStates(ROOT, listOf("a.jpg")).single().sourceMissing)
    }

    @Test
    fun `a file that is gone is flagged on the file, not on the registration`() = runTest {
        folderList(folderPath = "", files = listOf("a.jpg"))
        registerFiles("a.jpg")
        dao.setTaskCompleted(dao.taskIdOf(ROOT, "a.jpg"), completed = true, now = 2000)

        dao.markFilesDeleted(ROOT, listOf("a.jpg"))

        // Where the write lands is the whole of the move. The delete path is where it is read, and
        // a file that is already gone must not be offered up to be deleted a second time.
        assertEquals(emptyList<String>(), dao.getCheckedFilePaths(ROOT))
        assertEquals(1, rawInt("SELECT sourceMissing FROM assets WHERE relativePath = 'a.jpg'"))
        // And the registration's copy is left exactly where the migration put it, because nothing
        // writes there any more. Were that write to come back, this is the assertion that says so.
        assertEquals(
            0,
            rawInt("SELECT sourceMissing FROM file_review_state WHERE relativePath = 'a.jpg'")
        )
    }

    @Test
    fun `a stale column on a list row is never the answer either`() = runTest {
        val listId = folderList(folderPath = "", files = listOf("a.jpg"))
        registerFiles("a.jpg")
        dao.setTaskCompleted(dao.taskIdOf(ROOT, "a.jpg"), completed = true, now = 2000)

        rawExec("UPDATE list_items SET isCompleted = 0")

        assertTrue(dao.getItems(listId).single().isCompleted)
        val summary = dao.observeSummaries().first().single()
        assertEquals(1, summary.completedItems)
        assertEquals(1, dao.observeFolderScopes(ROOT).first().single().completedItems)
    }

    /**
     * The same fact, read through every query that can answer it, because they are separate SQL
     * and each is separately capable of being given the wrong column. A screen is only correct if
     * the query behind *it* is, and four of these feed four different surfaces.
     */
    @Test
    fun `every query that reports an item's state agrees about it`() = runTest {
        val listId = folderList(folderPath = "", files = listOf("a.jpg"))
        registerFiles("a.jpg")
        dao.setTaskCompleted(dao.taskIdOf(ROOT, "a.jpg"), completed = true, now = 2000)
        rawExec("UPDATE list_items SET isCompleted = 0")

        assertTrue(dao.observeItems(listId).first().single().decisions.isCompleted)
        assertTrue(dao.getItems(listId).single().decisions.isCompleted)
        assertEquals(1, dao.observeSummaries().first().single().completedItems)
        assertEquals(1, dao.observeFolderScopes(ROOT).first().single().completedItems)
        assertTrue(dao.observeFolderScopeItems(ROOT).first().single().isCompleted)
    }

    @Test
    fun `a manual item's own row is the one that is trusted`() = runTest {
        val listId = manualList()
        database.writeLegacyManualItem(listId, "Buy milk", now = 1000)
        val milk = dao.getItems(listId).single()

        dao.setTaskCompleted(milk.taskId, completed = true, now = 2000)

        // A manual item has no file, so there is nothing for it to share a decision with, and no
        // second copy to disagree with.
        assertTrue(dao.getItems(listId).single().isCompleted)
        assertEquals(ReviewDecisions(isCompleted = true), dao.getItems(listId).single().decisions)
    }

    @Test
    fun `clearing a manual item's action clears only that action`() = runTest {
        val listId = manualList()
        database.writeLegacyManualItem(listId, "Buy milk", now = 1000)
        val milk = dao.getItems(listId).single()
        dao.setTaskCompleted(milk.taskId, completed = true, now = 2000)

        dao.setTaskAction(milk.taskId, ItemAction.Favourite, enabled = true, now = 3000)
        dao.setTaskAction(milk.taskId, ItemAction.Favourite, enabled = false, now = 4000)

        val after = dao.getItems(listId).single().decisions
        assertFalse(after.isFavorite)
        assertTrue(after.isCompleted)
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

    private suspend fun manualList(): Long = dao.insertList(
        ListEntity(title = "Shopping", createdAt = 1000)
    )

    /** Registration as Quick Review does it: idempotent, asserting nothing about the files. */
    private suspend fun registerFiles(vararg rootRelativePaths: String) = dao.registerFiles(
        rootRelativePaths.map {
            FileReviewStateEntity(
                publicId = "FILE-$it",
                rootUri = ROOT,
                relativePath = it,
                updatedAt = 1000
            )
        }
    )

    private fun rawExec(sql: String) {
        database.openHelper.writableDatabase.execSQL(sql)
    }

    private fun rawInt(sql: String): Int =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            assertTrue("no row for: $sql", cursor.moveToFirst())
            cursor.getInt(0)
        }

    private companion object {
        const val ROOT = "content://root"
        const val OTHER = "content://other"
    }
}
