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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Whether a List is recorded as finished, on every path that can change the answer.
 *
 * A List's completion is a **stored** flag — `lists.completedAt` — and it is what the Lists screen
 * files a row under. The counts beside it are computed live, so the two can disagree, and the
 * reported defect is exactly that disagreement: a List reading `N / N` and filed under *In
 * progress*. See [ListSummary.isComplete] and `ListsScreen.ListFilter`.
 *
 * The rule this file holds the code to is narrow and mechanical: **every write that moves a
 * membership, or changes a member's Task, leaves the flag agreeing with the rows.** Four writes
 * already did; the four here did not. Each test therefore builds a List through one of those four
 * and then asks the *screen's* question — `observeSummaries`, not a count — because a count can be
 * right while the section is wrong, and it is the section the user sees.
 *
 * The Tasks are completed through [ListsDao.setTaskCompleted], which is the write the checkbox
 * reaches, so no test here has to know how completion is stored.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListCompletionTest {

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

    // --- building a List out of Tasks that are already done ----------------------------------------

    @Test
    fun `a List built from Tasks that are already done is complete`() = runTest {
        // The reported path: a Todo is dissolved, re-imported, and the completion comes back from
        // the history — so the List is built out of Tasks that were finished before it existed.
        // Nothing about creating it is a user *doing* something to a Task, which is why no write
        // downstream of the creation ever re-asked the question.
        val source = folder("photos", listOf("a.jpg", "b.jpg", "c.jpg"))
        val tasks = taskIds(source)
        for ((index, taskId) in tasks.withIndex()) {
            dao.setTaskCompleted(taskId, completed = true, now = 3000L + index)
        }

        val listId = dao.createListFromTasks("Trip", tasks, false, 4000)

        val summary = summaryOf(listId)
        assertEquals(3, summary.totalItems)
        assertEquals(3, summary.completedItems)
        assertTrue("a List of finished Tasks is filed as finished", summary.isComplete)
    }

    @Test
    fun `a List built from Tasks that are not all done is not complete`() = runTest {
        // The other half, and the reason the fix cannot simply be "mark it complete on creation":
        // the same call has to leave an unfinished List unfinished.
        val source = folder("photos", listOf("a.jpg", "b.jpg", "c.jpg"))
        val tasks = taskIds(source)
        dao.setTaskCompleted(tasks[0], completed = true, now = 3000)
        dao.setTaskCompleted(tasks[1], completed = true, now = 3001)

        val listId = dao.createListFromTasks("Trip", tasks, false, 4000)

        val summary = summaryOf(listId)
        assertEquals(2, summary.completedItems)
        assertFalse(summary.isComplete)
    }

    @Test
    fun `adding the last unfinished Task to a List that was waiting on it completes the List`() =
        runTest {
            // The same fault from the other end: the List exists first, and the Tasks arrive. An
            // addition that moves the last membership is as much a change to the question as a
            // completion is, and `addTasksToList` is the write that does it.
            val source = folder("photos", listOf("a.jpg", "b.jpg"))
            val tasks = taskIds(source)
            val first = dao.createListFromTasks("Trip", listOf(tasks[0]), false, 4000)
            dao.setTaskCompleted(tasks[0], completed = true, now = 3000)
            // Built from one finished Task, so it is complete — and the second addition is about to
            // make it *not* complete, which is the same flag being asked the same question.
            assertTrue(summaryOf(first).isComplete)

            assertEquals(1, dao.addTasksToList(first, listOf(tasks[1]), 5000))
            assertFalse("a List that just gained an unfinished Task is not finished",
                summaryOf(first).isComplete)

            // And completing that Task settles it again, through the write that has always refreshed.
            dao.setTaskCompleted(tasks[1], completed = true, now = 6000)
            assertTrue(summaryOf(first).isComplete)
        }

    // --- a dissolution that changes what a List is waiting on --------------------------------------

    @Test
    fun `a dissolution that takes the last unfinished Task completes the List left behind`() = runTest {
        // Two Todos, one List spanning them — which is the whole point of v0.4.0's ownership rule,
        // and the only shape in which a dissolution can remove *some* of a List's rows.
        val done = folder("done", listOf("a.jpg", "b.jpg"))
        val pending = folder("pending", listOf("c.jpg"))
        val listId = dao.createListFromTasks(
            "Trip",
            taskIds(done) + taskIds(pending),
            false,
            4000
        )
        for ((index, taskId) in taskIds(done).withIndex()) {
            dao.setTaskCompleted(taskId, completed = true, now = 3000L + index)
        }
        assertFalse("the third Task is still unfinished", summaryOf(listId).isComplete)

        // The unfinished Task's Todo is dissolved — keepState false, because the point is the
        // removal rather than the history. Its membership goes with it, and the List is left with
        // nothing but finished rows.
        assertNotNull(dao.deleteTodo(pending, keepState = false, now = 7000))

        val summary = summaryOf(listId)
        assertEquals(2, summary.totalItems)
        assertEquals(2, summary.completedItems)
        assertTrue("what is left is finished, so the List is", summary.isComplete)
    }

    @Test
    fun `a dissolution that empties a List leaves it unfinished rather than claiming completion`() =
        runTest {
            // The reverse drift, and the reason the refresh is `refreshCompletion` rather than a
            // bare `markCompleted`: `0 / 0` is not a finished List. This is already the answer
            // `applyReconcile` and `removeMissingSourceItems` give, so a dissolution agreeing with
            // them is consistency rather than a new rule.
            val source = folder("photos", listOf("a.jpg"))
            val listId = dao.createListFromTasks("Trip", taskIds(source), false, 4000)
            dao.setTaskCompleted(taskIds(source).single(), completed = true, now = 3000)
            assertTrue(summaryOf(listId).isComplete)

            dao.deleteTodo(source, keepState = false, now = 7000)

            val summary = summaryOf(listId)
            assertEquals(0, summary.totalItems)
            assertFalse("an empty List is not a finished one", summary.isComplete)
        }

    // --- the fixture ------------------------------------------------------------------------------

    /** A Todo over one folder of [files], one Task per file, and its Task ids in catalog order. */
    private suspend fun folder(name: String, files: List<String>): String {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = name,
            displayName = name,
            importerKind = ImporterKind.FILE_AS_TASK,
            keepTree = true,
            now = 1000
        )
        dao.runImport(
            source.id,
            name,
            plan(
                ImporterKind.FILE_AS_TASK,
                name,
                FolderSnapshot(files.map { scanned(name, it) }, emptyList())
            ),
            2000
        )
        return source.id
    }

    private suspend fun taskIds(sourceId: String): List<String> =
        dao.getCatalogTasks(sourceId).sortedBy { it.groupPath }.map { it.taskId }

    /** One List's row as the Lists screen reads it — the section, not just the count. */
    private suspend fun summaryOf(listId: Long): ListSummary =
        dao.observeSummaries().first().single { it.id == listId }

    private fun scanned(folder: String, name: String) = ScannedFile(
        name = name,
        relativePath = name,
        uri = "$ROOT/$folder/$name"
    )

    private companion object {
        const val ROOT = "content://root"
    }
}
