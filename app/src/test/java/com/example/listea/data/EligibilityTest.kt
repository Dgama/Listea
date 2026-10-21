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
import org.junit.Assert.assertFalse
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

    // §6.4, §9.3, AR-3 — and the v0.7.0 change that gives a PDF a viewer
    @Test
    fun `a PDF Task is viewable and reviewable now that the app can show a PDF`() = runTest {
        // Before v0.7.0 this Task was imported and catalogued but refused a judgment, because no
        // viewer in the build could render its one Asset. The rule has not moved — a Task whose
        // content cannot be shown is still refused — but a PDF can be shown now, so this is the
        // ordinary case rather than the exception it used to pin.
        val source = registerSource(ROOT, "", ImporterKind.FOLDER_AS_TASK)
        dao.runImport(source.id, "", folderPlan("", listOf("alice/cv.pdf"), listOf("alice")), 2000)

        val row = dao.getCatalogTasks(source.id).single()
        assertFalse(row.hasUnviewableAsset)
        assertEquals("alice", row.groupPath)

        dao.submitReview(row.taskId, media(true), Templates.MediaRapidReview.ref, ReviewContext.InFolder(ROOT, ""), 2000)
        assertEquals(1, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '${row.taskId}'"))
        assertEquals(1, rawInt("SELECT COUNT(*) FROM review_events"))
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

    // Definition of Done, v0.3 readiness — and v0.7.0's promise that a PDF is not a second class
    @Test
    fun `a PDF Task and an image Task are identical in every respect`() = runTest {
        val source = registerSource(ROOT, "", ImporterKind.FOLDER_AS_TASK)
        dao.runImport(source.id, "", folderPlan("", listOf("img/cv.jpg", "pdf/cv.pdf"), listOf("img", "pdf")), 2000)
        val rows = dao.getCatalogTasks(source.id).associateBy { it.displayName }
        val image = rows.getValue("img")
        val pdf = rows.getValue("pdf")

        // Both are imported, catalogued and measured alike — including the viewability flag, which
        // is the line v0.7.0 moved.
        assertEquals(1, image.assetCount)
        assertEquals(1, pdf.assetCount)
        assertEquals(false, image.hasUnviewableAsset)
        assertEquals(false, pdf.hasUnviewableAsset)

        // Both are members of the same List — eligibility does not gate membership.
        val listId = dao.createListFromTasks("Trip", listOf(image.taskId, pdf.taskId), false,3000)
        assertEquals(2, dao.getItems(listId).size)
        assertEquals(1, rawInt("SELECT COUNT(*) FROM task_state WHERE taskId = '${image.taskId}'"))
        assertEquals(1, rawInt("SELECT COUNT(*) FROM task_state WHERE taskId = '${pdf.taskId}'"))

        // Both submit and record a judgment.
        dao.submitReview(image.taskId, media(true), Templates.MediaRapidReview.ref, ReviewContext.InFolder(ROOT, ""), 4000)
        dao.submitReview(pdf.taskId, media(true), Templates.MediaRapidReview.ref, ReviewContext.InFolder(ROOT, ""), 4000)
        assertEquals(2, rawInt("SELECT COUNT(*) FROM review_events"))
        assertEquals(1, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '${image.taskId}'"))
        assertEquals(1, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '${pdf.taskId}'"))
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun media(completed: Boolean) =
        ReviewResponse.MediaReview(completed = completed, selections = emptySet())

    private suspend fun folderList(vararg files: String): Long = dao.createFolderList(
        title = "Trip",
        rootUri = ROOT,
        relativePath = "",
        files = files.map { ScannedFile(name = it, relativePath = it, uri = "$ROOT/$it") },
        replacedListIds = emptyList(),
        exportOnCompletion = false,
        now = 1000
    )

    private suspend fun registerSource(
        rootUri: String,
        relativePath: String,
        kind: ImporterKind
    ): SourceEntity = dao.registerSource(rootUri, relativePath, relativePath.ifEmpty { "Root" }, kind, keepTree = true, now = 1000)

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
