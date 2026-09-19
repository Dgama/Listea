package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
 * What v0.2's cleanup path is allowed to delete, and the one thing it must never touch.
 *
 * A manual item is a Task with no Asset, reachable only through the membership row it was typed
 * into. Removing that row removes the last thing that could name it, so its Task goes too - which
 * is the only deletion in this app that reaches `tasks` from the item side, and the reason it goes
 * through a named set rather than a guess like "no Asset, therefore manual". A Task with no Asset
 * and no name is a Task the app has lost track of, not one it may assume nobody wanted.
 *
 * Every case is tested against a file's Task as well as a manual one, because the failure worth
 * guarding is not "the manual Task was left behind" - that is untidy - but "a file's Task was taken
 * with the List", which throws away somebody's decisions about a folder because they tidied up one
 * way of looking at it. A cleanup tested only against the case it exists for would pass on the
 * version that deletes everything.
 *
 * The empty case is the ordinary case rather than an edge. Most deletes are of Lists over a folder,
 * so most of the sets collected here are empty, and every test below asserts what survived as well
 * as what went - a cleanup that took nothing at all would otherwise look like a correct one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyManualCleanupTest {

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

    // --- a List going takes what only it could reach ----------------------------------------------

    @Test
    fun `a manual List takes every item's Task with it`() = runTest {
        val listId = manualList()
        val milk = database.writeLegacyManualItem(listId, "Buy milk", now = 1000)
        val bread = database.writeLegacyManualItem(listId, "Buy bread", now = 1000)

        dao.deleteList(listId)

        // Both, not the first: what is collected is the List's items, and a cleanup that found one
        // row's Task and stopped would leave the rest named forever.
        assertEquals(0, rawInt("SELECT COUNT(*) FROM tasks WHERE id IN ('$milk', '$bread')"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM tasks"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM task_state"))
        // The name goes with the thing it named. A name left behind would be a row pointing at
        // nothing, and the whole reason this table exists is to be a closed set.
        assertEquals(0, rawInt("SELECT COUNT(*) FROM legacy_manual_tasks"))
    }

    @Test
    fun `a List going leaves the Tasks of the files it showed`() = runTest {
        val listId = folderList(folderPath = "2026-07-11", files = listOf("a.jpg"))
        val fileTask = dao.getItems(listId).single().taskId
        dao.setTaskCompleted(fileTask, completed = true, now = 2000)

        dao.deleteList(listId)

        // The decision is about the file, and the file is not what was deleted. A List is one way
        // of looking at a folder; closing it must not un-answer for the folder.
        assertEquals(1, rawInt("SELECT COUNT(*) FROM tasks WHERE id = '$fileTask'"))
        assertEquals(1, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$fileTask'"))
        assertEquals(1, rawInt("SELECT COUNT(*) FROM assets WHERE taskId = '$fileTask'"))
        // Nothing here is manual, so the cleanup had an empty set to work with - which is the
        // ordinary case - and everything above still had to hold.
        assertEquals(0, rawInt("SELECT COUNT(*) FROM legacy_manual_tasks"))
    }

    @Test
    fun `deleting one List leaves another's manual items alone`() = runTest {
        val going = manualList()
        val staying = manualList()
        val kept = database.writeLegacyManualItem(staying, "Buy milk", now = 1000)
        database.writeLegacyManualItem(going, "Buy bread", now = 1000)

        dao.deleteList(going)

        // Two Lists with items of the same shape, one deleted. The set is collected per List, so
        // the neighbour's Task is not collateral.
        assertEquals(1, rawInt("SELECT COUNT(*) FROM tasks"))
        assertEquals(1, rawInt("SELECT COUNT(*) FROM tasks WHERE id = '$kept'"))
        assertEquals(1, rawInt("SELECT COUNT(*) FROM legacy_manual_tasks"))
    }

    // --- a row going takes what only it could reach ------------------------------------------------

    @Test
    fun `removing a manual item takes its Task and the name that reached it`() = runTest {
        val listId = manualList()
        val milk = database.writeLegacyManualItem(listId, "Buy milk", now = 1000)
        val bread = database.writeLegacyManualItem(listId, "Buy bread", now = 1000)
        val milkItem = dao.getItems(listId).single { it.title == "Buy milk" }

        dao.removeItem(listId, milkItem.id, now = 2000)

        assertEquals(0, rawInt("SELECT COUNT(*) FROM tasks WHERE id = '$milk'"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM legacy_manual_tasks WHERE taskId = '$milk'"))
        // One row's removal, not the List's: the item left behind keeps both its Task and its name.
        assertEquals(1, rawInt("SELECT COUNT(*) FROM tasks WHERE id = '$bread'"))
        assertEquals(1, rawInt("SELECT COUNT(*) FROM legacy_manual_tasks"))
        assertEquals(bread, dao.getItems(listId).single().taskId)
    }

    @Test
    fun `removing a file's row takes the row and leaves the Task`() = runTest {
        val listId = folderList(folderPath = "2026-07-11", files = listOf("a.jpg"))
        val item = dao.getItems(listId).single()
        dao.setTaskCompleted(item.taskId, completed = true, now = 2000)

        dao.removeItem(listId, item.id, now = 3000)

        // The row was membership and the Task is the decision. Taking the file out of this List
        // says nothing about the file, and the next List over this folder resolves the same Task
        // and sees the checkmark still there.
        assertEquals(0, dao.getItems(listId).size)
        assertEquals(1, rawInt("SELECT COUNT(*) FROM tasks"))
        assertEquals(1, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '${item.taskId}'"))
    }

    // --- giving up a root ---------------------------------------------------------------------------

    @Test
    fun `giving up a root takes its Lists' manual items and no other root's`() = runTest {
        // A folder-backed List, because that is the one that can be reached by a root at all - and
        // the one a cleanup written for manual Lists alone would walk straight past.
        val onRoot = folderList(folderPath = "2026-07-11", files = listOf("a.jpg"))
        val onRootFileTask = dao.getItems(onRoot).single().taskId
        dao.setTaskCompleted(onRootFileTask, completed = true, now = 2000)
        val onRootManual = database.writeLegacyManualItem(onRoot, "Buy milk", now = 1000)
        val elsewhere = folderList(
            rootUri = OTHER_ROOT,
            folderPath = "2026-07-11",
            files = listOf("b.jpg")
        )
        val elsewhereManual = database.writeLegacyManualItem(elsewhere, "Buy bread", now = 1000)

        val deleted = dao.deleteListsForRoot(ROOT)

        assertEquals(1, deleted)
        assertEquals(0, rawInt("SELECT COUNT(*) FROM tasks WHERE id = '$onRootManual'"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM legacy_manual_tasks WHERE taskId = '$onRootManual'"))
        // The files under that root are the reason it was granted, and a root being given up is
        // not a reason to forget what was decided about them.
        assertEquals(1, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$onRootFileTask'"))
        assertEquals(1, rawInt("SELECT COUNT(*) FROM assets WHERE taskId = '$onRootFileTask'"))
        // A List over another folder has nothing to do with the grant being given up, and neither
        // does anything the user typed into it.
        assertEquals(1, rawInt("SELECT COUNT(*) FROM tasks WHERE id = '$elsewhereManual'"))
        assertEquals(1, rawInt("SELECT COUNT(*) FROM legacy_manual_tasks"))
    }

    @Test
    fun `giving up a root with no manual items anywhere still counts its Lists`() = runTest {
        folderList(folderPath = "2026-07-11", files = listOf("a.jpg"))
        folderList(folderPath = "2026-07-12", files = listOf("b.jpg"))

        val deleted = dao.deleteListsForRoot(ROOT)

        // The cleanup must not have become the thing that decides whether anything was deleted:
        // the empty set is the ordinary case, and the count is still the Lists'.
        assertEquals(2, deleted)
        assertEquals(0, rawInt("SELECT COUNT(*) FROM lists"))
    }

    // --- helpers ------------------------------------------------------------------------------------

    private suspend fun folderList(
        rootUri: String = ROOT,
        folderPath: String,
        files: List<String>,
        title: String = "Trip"
    ): Long = dao.createFolderList(
        title = title,
        rootUri = rootUri,
        relativePath = folderPath,
        files = files.map {
            ScannedFile(name = it, relativePath = it, uri = "$rootUri/$folderPath/$it")
        },
        replacedListIds = emptyList(),
        exportOnCompletion = false,
        now = 1000
    )

    /** A List with no source folder, which is the only kind a manual item was ever typed into. */
    private suspend fun manualList(): Long = dao.insertList(
        ListEntity(title = "Shopping", createdAt = 1000)
    )

    private fun rawInt(sql: String): Int =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            assertTrue("no row for: $sql", cursor.moveToFirst())
            cursor.getInt(0)
        }

    private companion object {
        const val ROOT = "content://root"
        const val OTHER_ROOT = "content://other"
    }
}
