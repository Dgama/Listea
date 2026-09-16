package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.FolderSnapshot
import com.example.listea.ImporterKind
import com.example.listea.plan
import com.example.listea.domain.ReviewContext
import com.example.listea.domain.ReviewResponse
import com.example.listea.domain.Templates
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
 * Review eligibility at the write boundary: being imported is not being reviewable.
 *
 * §6.4's refusal lives in [ListsDao.submitReview], not in a disabled button, so these pin it there.
 * A Task whose content is missing, or whose content no viewer in this build can render, writes no
 * state and no event — while `setTaskCompleted`, a checkbox rather than a submission, is not gated
 * and keeps a List's membership and progress exactly as v0.1 left them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EligibilityTest {

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

    // §6.4, AR-3
    @Test
    fun `a submission against missing or unviewable content writes nothing, and a checkbox still works`() =
        runTest {
            val missingJpg = dao.taskIdOf(ROOT, "gone.jpg")
            dao.markFilesDeleted(ROOT, listOf("gone.jpg"))
            val unviewableTxt = dao.taskIdOf(ROOT, "notes.txt")

            assertEquals(
                emptyList<Long>(),
                dao.submitReview(missingJpg, media(true), Templates.MediaRapidReview.ref, ReviewContext.InFolder(ROOT, ""), 2000)
            )
            assertEquals(
                emptyList<Long>(),
                dao.submitReview(unviewableTxt, media(true), Templates.MediaRapidReview.ref, ReviewContext.InFolder(ROOT, ""), 2000)
            )

            assertEquals(0, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$missingJpg'"))
            assertEquals(0, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$unviewableTxt'"))
            assertEquals(0, rawInt("SELECT COUNT(*) FROM review_events"))

            // A checkbox is not a submission and is not gated.
            dao.setTaskCompleted(missingJpg, completed = true, now = 2000)
            dao.setTaskCompleted(unviewableTxt, completed = true, now = 2000)
            assertEquals(1, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$missingJpg'"))
            assertEquals(1, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$unviewableTxt'"))
            assertEquals(0, rawInt("SELECT COUNT(*) FROM review_events"))
        }

    // §6.4, §9.3, AR-3
    @Test
    fun `a PDF Task imports, appears in the catalog as awaiting a viewer, and cannot be reviewed`() =
        runTest {
            val source = registerSource(ROOT, "", ImporterKind.FOLDER_AS_TASK)
            dao.runImport(source.id, "", folderPlan("", listOf("alice/cv.pdf"), listOf("alice")), 2000)

            val row = dao.getCatalogTasks(source.id).single()
            assertTrue(row.hasUnviewableAsset)
            assertEquals("alice", row.groupPath)

            assertEquals(
                emptyList<Long>(),
                dao.submitReview(row.taskId, media(true), Templates.MediaRapidReview.ref, ReviewContext.InFolder(ROOT, ""), 2000)
            )
            assertEquals(0, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '${row.taskId}'"))
            assertEquals(0, rawInt("SELECT COUNT(*) FROM review_events"))
        }

    // §6.4, AR-3
    @Test
    fun `a Task whose Asset goes missing keeps its membership and history but is no longer submittable`() =
        runTest {
            val listId = folderList("a.jpg")
            val taskId = dao.getItems(listId).single().taskId
            dao.submitReview(taskId, media(true), Templates.MediaRapidReview.ref, ReviewContext.InFolder(ROOT, ""), 2000)
            assertEquals(1, rawInt("SELECT COUNT(*) FROM review_events"))

            dao.markFilesDeleted(ROOT, listOf("a.jpg"))

            // Still a member, still counted, still completable by checkbox.
            assertEquals(1, dao.getItems(listId).size)
            assertEquals(1, dao.observeSummaries().first().single().totalItems)
            dao.setTaskCompleted(taskId, completed = true, now = 3000)

            // A new submission is refused, and the prior judgment is untouched.
            assertEquals(
                emptyList<Long>(),
                dao.submitReview(taskId, media(true), Templates.MediaRapidReview.ref, ReviewContext.InFolder(ROOT, ""), 4000)
            )
            assertEquals(1, rawInt("SELECT COUNT(*) FROM review_events"))
        }

    // §4.3, §6.4, §14.3
    @Test
    fun `a legacy folder List containing a text file keeps its membership and progress, blocked only for review`() =
        runTest {
            val listId = folderList("notes.txt")
            val item = dao.getItems(listId).single()

            assertEquals(1, dao.getItems(listId).size)
            assertEquals(1, dao.observeSummaries().first().single().totalItems)

            assertEquals(
                emptyList<Long>(),
                dao.submitReview(item.taskId, media(true), Templates.MediaRapidReview.ref, ReviewContext.InFolder(ROOT, ""), 2000)
            )
            assertEquals(0, rawInt("SELECT COUNT(*) FROM review_events"))

            // The checkbox still works, and the block changed nothing about membership or progress.
            dao.setTaskCompleted(item.taskId, completed = true, now = 3000)
            assertEquals(1, dao.observeSummaries().first().single().completedItems)
            assertEquals(1, dao.getItems(listId).size)
        }

    // --- helpers ---------------------------------------------------------------------------------

    private fun media(completed: Boolean) =
        ReviewResponse.MediaReview(completed = completed, favorite = false, customActions = emptySet())

    private suspend fun folderList(vararg files: String): Long = dao.createFolderList(
        title = "Trip",
        rootUri = ROOT,
        relativePath = "",
        files = files.map { ScannedFile(name = it, relativePath = it, uri = "$ROOT/$it") },
        replacedListIds = emptyList(),
        webhookEnabled = false,
        webhookUrl = "",
        now = 1000
    )

    private suspend fun registerSource(
        rootUri: String,
        relativePath: String,
        kind: ImporterKind
    ): SourceEntity = when (
        val result = dao.registerSource(rootUri, relativePath, relativePath.ifEmpty { "Root" }, kind, 1000)
    ) {
        is RegisterSourceResult.Registered -> result.source
        is RegisterSourceResult.Overlaps -> throw AssertionError("unexpected overlap")
    }

    private fun folderPlan(importRoot: String, files: List<String>, directories: List<String>) =
        plan(
            ImporterKind.FOLDER_AS_TASK,
            importRoot,
            FolderSnapshot(
                files = files.map {
                    ScannedFile(name = it.substringAfterLast('/'), relativePath = it, uri = "$ROOT/$it")
                },
                directories = directories
            )
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
