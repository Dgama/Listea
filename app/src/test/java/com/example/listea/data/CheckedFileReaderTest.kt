package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.domain.CompletionPolicy
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
import java.util.UUID

/**
 * The delete path must see a file that was checked by a review *submission*, with no registration
 * row in sight.
 *
 * A review submission and a checkbox both move a Task's completion. The difference that used to
 * matter here is that the checkbox path also registered the file, while the submission path did
 * not — so a folder List that was swiped through to completion reported "0 checked files" in
 * Settings the moment nothing else had happened to register its files. The reader now derives
 * checked state from `assets` + `task_state` alone, and these tests pin that it does so without any
 * `registerFiles` call in the setup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CheckedFileReaderTest {

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
    fun `a review submission on a folder list is seen by the delete path with no registration`() =
        runTest {
            val listId = folderList(files = listOf("a.jpg"))

            dao.submitReview(
                taskId = dao.getItems(listId).single().taskId,
                partKey = null,
                response = media(completed = true),
                template = Templates.MediaRapidReview.ref,
                context = ReviewContext.InFolder(ROOT, ""),
                completion = CompletionPolicy.SinglePart,
                completesTask = true,
                explicitCompletion = false,
                now = 2000
            )

            // The submission wrote state and no registration — the reader must not care.
            assertEquals(0, rawInt("SELECT COUNT(*) FROM file_review_state"))
            assertEquals(listOf("a.jpg"), dao.getCheckedFilePaths(ROOT))
            assertEquals(1, dao.observeCheckedFileCount(ROOT).first())
        }

    @Test
    fun `a migrated membership with no historical registration is seen the same way`() = runTest {
        // The shape the schema-33 migration leaves behind for a membership whose file had never had
        // a decision recorded against it: a Task, its one part and part-state row, one Asset, one
        // state row, and a list_items row — and no file_review_state, because there was nothing to
        // register. Reproduced by hand rather than through createFolderList so the fixture is the
        // migration's and not the live write path's.
        val listId = manualList()
        val taskId = writeMigratedSourceItem(listId, "a.jpg")

        dao.submitReview(
            taskId = taskId,
            partKey = null,
            response = media(completed = true),
            template = Templates.MediaRapidReview.ref,
            context = ReviewContext.InList(com.example.listea.domain.ListId(listId)),
            completion = CompletionPolicy.SinglePart,
            completesTask = true,
            explicitCompletion = false,
            now = 2000
        )

        assertEquals(0, rawInt("SELECT COUNT(*) FROM file_review_state"))
        assertEquals(listOf("a.jpg"), dao.getCheckedFilePaths(ROOT))
        assertEquals(1, dao.observeCheckedFileCount(ROOT).first())
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun media(completed: Boolean) =
        ReviewResponse.MediaReview(completed = completed, selections = emptySet())

    private suspend fun folderList(files: List<String>): Long = dao.createFolderList(
        title = "Trip",
        rootUri = ROOT,
        relativePath = "",
        files = files.map { ScannedFile(name = it, relativePath = it, uri = "$ROOT/$it") },
        replacedListIds = emptyList(),
        exportOnCompletion = false,
        now = 1000
    )

    private suspend fun manualList(): Long = dao.insertList(
        ListEntity(title = "Trip", createdAt = 1000)
    )

    /**
     * A source-backed membership of the shape the migration produces, with no registration row.
     * Returns the Task id it minted, so a test can address the review submission by it.
     */
    private fun writeMigratedSourceItem(listId: Long, relativePath: String): String {
        val taskId = UUID.randomUUID().toString()
        val assetId = UUID.randomUUID().toString()
        // A Task with no import row of its own is `legacy:<taskId>` after the schema-33 migration.
        val partKey = "legacy:$taskId"
        val db = database.openHelper.writableDatabase
        db.execSQL("INSERT INTO tasks (id, createdAt) VALUES (?, ?)", arrayOf<Any?>(taskId, 1000))
        db.execSQL(
            "INSERT INTO task_parts (" +
                "taskId, partKey, position, origin, promptKey, required, present, " +
                "availability, updatedAt) " +
                "VALUES (?, ?, 0, 'legacy', NULL, 1, 1, NULL, 1000)",
            arrayOf<Any?>(taskId, partKey)
        )
        db.execSQL(
            "INSERT INTO task_part_state (" +
                "taskId, partKey, response, answeredAt, revision, updatedAt) " +
                "VALUES (?, ?, '', NULL, NULL, 1000)",
            arrayOf<Any?>(taskId, partKey)
        )
        db.execSQL(
            "INSERT INTO assets (" +
                "id, taskId, sortOrder, rootUri, relativePath, sourceMissing, partKey) " +
                "VALUES (?, ?, 0, ?, ?, 0, ?)",
            arrayOf<Any?>(assetId, taskId, ROOT, relativePath, partKey)
        )
        db.execSQL(
            "INSERT INTO task_state (" +
                "taskId, isCompleted, isFavorite, customActions, completionSource, updatedAt) " +
                "VALUES (?, 0, 0, '', '', 1000)",
            arrayOf<Any?>(taskId)
        )
        db.execSQL(
            "INSERT INTO list_items (" +
                "publicId, listId, taskId, title, isCompleted, sortOrder, createdAt, " +
                "sourceUri, sourceRelativePath, rootRelativePath, sourceMissing, " +
                "sourceSizeBytes, sourceModifiedAt, isFavorite, customActions, selections) " +
                "VALUES (?, ?, ?, ?, 0, 0, ?, ?, ?, ?, 0, NULL, NULL, 0, '', '')",
            arrayOf<Any?>(
                newItemPublicId(fileIdentity(relativePath), 1000),
                listId,
                taskId,
                relativePath,
                1000,
                "$ROOT/$relativePath",
                relativePath,
                relativePath
            )
        )
        return taskId
    }

    private fun rawInt(sql: String): Int =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            assertTrue("no row for: $sql", cursor.moveToFirst())
            cursor.getInt(0)
        }

    private companion object {
        const val ROOT = "content://root"
    }
}
