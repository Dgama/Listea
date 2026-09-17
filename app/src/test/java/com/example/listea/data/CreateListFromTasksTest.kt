package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.FolderSnapshot
import com.example.listea.ImporterKind
import com.example.listea.plan
import com.example.listea.domain.ListId
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
 * Creating a List from already-normalized Tasks, and the lifetime it keeps once it is created.
 *
 * §8.1 is the seam the release exists to make visible: a List is membership over Tasks, resolved
 * from Task ids rather than from a raw folder scan, and an id it cannot reach fails the whole
 * transaction. §8.2 is the compatibility obligation on the other side — the Folder tab's Create
 * List still produces the same `list_items` row it always did, now through an import.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreateListFromTasksTest {

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

    // §8.1, §8.2
    @Test
    fun `createListFromTasks accepts Task ids and an unknown id fails the whole transaction`() =
        runTest {
            val source = registerSource(ROOT, "", ImporterKind.FILE_AS_TASK)
            dao.runImport(source.id, "", filePlan("", listOf("a.jpg", "b.jpg")), 2000)
            val taskIds = dao.getCatalogTasks(source.id).sortedBy { it.groupPath }.map { it.taskId }

            // An unknown id fails the whole transaction, so no partial List is left behind.
            val listsBefore = rawInt("SELECT COUNT(*) FROM lists")
            assertTrue(
                runCatching {
                    dao.createListFromTasks("Trip", taskIds + "no-such-task", false, "", 3000)
                }.isFailure
            )
            assertEquals(listsBefore, rawInt("SELECT COUNT(*) FROM lists"))

            // Known ids create one membership per Task, in the order given, with the Task's own
            // title and groupPath carried onto the row.
            val listId = dao.createListFromTasks("Trip", taskIds, false, "", 3000)
            val items = dao.getItems(listId)
            assertEquals(taskIds, items.map { it.taskId })
            assertEquals(listOf("a.jpg", "b.jpg"), items.map { it.title })
            assertEquals(listOf("a.jpg", "b.jpg"), items.map { it.rootRelativePath })
            assertEquals(listOf("a.jpg", "b.jpg"), items.map { it.sourceRelativePath })
        }

    // §8.2
    @Test
    fun `a list_items row from the recomposed createFolderList is byte-identical to the v13 one`() =
        runTest {
            val now = 1000L
            val listId = dao.createFolderList(
                title = "Trip",
                rootUri = ROOT,
                relativePath = "2026-07-11",
                files = listOf(
                    ScannedFile(
                        name = "a.jpg",
                        relativePath = "a.jpg",
                        uri = "$ROOT/2026-07-11/a.jpg",
                        sizeBytes = 4096,
                        lastModified = 555
                    )
                ),
                replacedListIds = emptyList(),
                webhookEnabled = false,
                webhookUrl = "",
                now = now
            )
            val item = dao.getItems(listId).single()

            assertEquals("a.jpg", item.title)
            assertEquals("a.jpg", item.sourceRelativePath)
            assertEquals("2026-07-11/a.jpg", item.rootRelativePath)
            assertEquals("$ROOT/2026-07-11/a.jpg", item.sourceUri)
            assertEquals(4096L, item.sourceSizeBytes)
            assertEquals(555L, item.sourceModifiedAt)
            // The same name a receiver always got, fingerprint and all.
            assertEquals(
                newItemPublicId(fileIdentity("2026-07-11/a.jpg"), now).substringBeforeLast('-'),
                item.publicId.substringBeforeLast('-')
            )
        }

    // spec, shared review truth
    @Test
    fun `one Task in two Lists shows one TaskState and a submission from one is one event`() =
        runTest {
            val listA = dao.createFolderList("A", ROOT, "", files("a.jpg"), emptyList(), false, "", 1000)
            val listB = dao.createFolderList("B", ROOT, "", files("a.jpg"), emptyList(), false, "", 1000)
            val taskA = dao.getItems(listA).single().taskId
            val taskB = dao.getItems(listB).single().taskId
            assertEquals(taskA, taskB)

            dao.submitReview(
                taskA,
                media(true),
                Templates.MediaRapidReview.ref,
                ReviewContext.InList(ListId(listA)),
                2000
            )

            assertEquals(1, rawInt("SELECT COUNT(*) FROM task_state WHERE taskId = '$taskA'"))
            assertEquals(1, rawInt("SELECT COUNT(*) FROM review_events"))
            assertEquals(true, dao.getItems(listA).single().isCompleted)
            assertEquals(true, dao.getItems(listB).single().isCompleted)
        }

    // scenario C
    @Test
    fun `deleting a List leaves the Task, its Assets, state, events and provenance intact`() =
        runTest {
            val listId = dao.createFolderList("Trip", ROOT, "", files("a.jpg"), emptyList(), false, "", 1000)
            val taskId = dao.getItems(listId).single().taskId
            dao.submitReview(taskId, media(true), Templates.MediaRapidReview.ref, ReviewContext.InFolder(ROOT, ""), 2000)

            dao.deleteList(listId)

            assertEquals(0, rawInt("SELECT COUNT(*) FROM list_items WHERE listId = $listId"))
            assertEquals(1, rawInt("SELECT COUNT(*) FROM tasks WHERE id = '$taskId'"))
            assertEquals(1, rawInt("SELECT COUNT(*) FROM assets WHERE taskId = '$taskId'"))
            assertEquals(1, rawInt("SELECT COUNT(*) FROM task_state WHERE taskId = '$taskId'"))
            assertEquals(1, rawInt("SELECT COUNT(*) FROM review_events WHERE taskId = '$taskId'"))
            assertEquals(1, rawInt("SELECT COUNT(*) FROM task_imports WHERE taskId = '$taskId'"))
        }

    // --- helpers ---------------------------------------------------------------------------------

    private fun media(completed: Boolean) =
        ReviewResponse.MediaReview(completed = completed, favorite = false, customActions = emptySet())

    private fun files(vararg names: String) = names.map {
        ScannedFile(name = it, relativePath = it, uri = "$ROOT/$it")
    }

    private suspend fun registerSource(rootUri: String, relativePath: String, kind: ImporterKind): SourceEntity =
        dao.registerSource(rootUri, relativePath, relativePath.ifEmpty { "Root" }, kind, keepTree = true, now = 1000)

    private fun filePlan(importRoot: String, files: List<String>) =
        plan(
            ImporterKind.FILE_AS_TASK,
            importRoot,
            FolderSnapshot(files.map { ScannedFile(name = it.substringAfterLast('/'), relativePath = it, uri = "$ROOT/$it") }, emptyList())
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
