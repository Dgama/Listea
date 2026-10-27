package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.domain.CompletionPolicy
import com.example.listea.domain.ReviewContext
import com.example.listea.domain.ReviewResponse
import com.example.listea.domain.Templates
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
 * The two sides of addressing a Task-level write by its id rather than by a file's location.
 *
 * One side is that a write can no longer conjure a Task: a locator that missed used to mint a
 * replacement Task on the write path, silently splitting one file's judgment in two. A write is now
 * handed a Task id that was resolved long before the surface showed it, so an id that names nothing
 * writes nothing and creates nothing. The other side is that the same Task keeps meaning the same
 * thing across every List and folder that reaches it, because the id is what every one of them
 * shares.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskWriteTest {

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

    @Test
    fun `a write addressed at a Task that does not exist mints no replacement`() = runTest {
        dao.setTaskCompleted("no-such-task", completed = true, now = 2000)
        dao.setAssetAction(
            "no-such-task", "no-such-asset", ItemAction.Favourite, enabled = true, now = 2100
        )

        // A vanished address is a no-op, not an invitation to invent a Task to write to. Both
        // halves of the pair are addressed and neither exists, so neither table gains a row.
        assertEquals(0, rawInt("SELECT COUNT(*) FROM tasks"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM task_state"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM task_asset_state"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM assets"))
    }

    @Test
    fun `a submission for a Task that does not exist writes no state and no event`() = runTest {
        val transitioned = dao.submitReview(
            taskId = "no-such-task",
            partKey = null,
            response = ReviewResponse.MediaReview(
                completed = true,
                selections = emptySet()
            ),
            template = Templates.MediaRapidReview.ref,
            context = ReviewContext.InFolder(ROOT, ""),
            completion = CompletionPolicy.SinglePart,
            completesTask = true,
            explicitCompletion = false,
            now = 2000
        )

        assertEquals(0, rawInt("SELECT COUNT(*) FROM review_events"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM task_state"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM tasks"))
        assertEquals(emptyList<Long>(), transitioned)
    }

    @Test
    fun `two lists sharing one Task still see one shared TaskState`() = runTest {
        // Two overlapping scopes over one file: the root, and the folder it sits in. One file is
        // one Task with one state, whichever List is looking.
        val overRoot = folderList(folderPath = "", files = listOf("2026-07-11/a.jpg"), title = "All")
        val overFolder = folderList(folderPath = "2026-07-11", files = listOf("a.jpg"), title = "Day")

        val taskId = dao.getItems(overRoot).single().taskId
        assertEquals(taskId, dao.getItems(overFolder).single().taskId)
        assertEquals(1, rawInt("SELECT COUNT(*) FROM task_state WHERE taskId = '$taskId'"))

        dao.setTaskCompleted(taskId, completed = true, now = 2000)

        // Still one state row, and both Lists read the decision off the same Task.
        assertEquals(1, rawInt("SELECT COUNT(*) FROM task_state"))
        assertTrue(dao.getItems(overRoot).single().isCompleted)
        assertTrue(dao.getItems(overFolder).single().isCompleted)
    }

    @Test
    fun `every membership resolves to a Task with exactly one TaskState`() = runTest {
        // A folder List mints a Task (and its one state row) for every file; a manual item's Task
        // is written by the migration fixture. Between them, every row shape a List can hold.
        folderList(folderPath = "", files = listOf("a.jpg", "b.jpg"))
        val manualId = manualList()
        database.writeLegacyManualItem(manualId, "Buy milk", now = 1000)

        // Every list_items row names a Task that exists...
        assertEquals(
            0,
            rawInt(
                "SELECT COUNT(*) FROM list_items i " +
                    "LEFT JOIN tasks t ON t.id = i.taskId WHERE t.id IS NULL"
            )
        )
        // ...and each such Task has exactly one state row.
        assertEquals(
            0,
            rawInt(
                "SELECT COUNT(*) FROM list_items i " +
                    "WHERE (SELECT COUNT(*) FROM task_state ts WHERE ts.taskId = i.taskId) != 1"
            )
        )
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
        exportOnCompletion = false,
        now = 1000
    )

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
    }
}
