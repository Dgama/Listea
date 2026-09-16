package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
            folderList(files = listOf("a.jpg"))

            dao.submitReview(
                target = DecisionTarget.File(ROOT, "a.jpg"),
                response = media(completed = true),
                template = Templates.MediaRapidReview.ref,
                context = ReviewContext.InFolder(ROOT, ""),
                now = 2000
            )

            // The submission wrote state and no registration — the reader must not care.
            assertEquals(0, rawInt("SELECT COUNT(*) FROM file_review_state"))
            assertEquals(listOf("a.jpg"), dao.getCheckedFilePaths(ROOT))
            assertEquals(1, dao.observeCheckedFileCount(ROOT).first())
        }

    @Test
    fun `a migrated membership with no historical registration is seen the same way`() = runTest {
        // The shape MIGRATION_12_13 leaves behind for a membership whose file had never had a
        // decision recorded against it: a Task, one Asset, one state row, and a list_items row —
        // and no file_review_state, because there was nothing to register. Reproduced by hand
        // rather than through createFolderList so the fixture is the migration's and not the live
        // write path's.
        val listId = manualList()
        writeMigratedSourceItem(listId, "a.jpg")

        dao.submitReview(
            target = DecisionTarget.File(ROOT, "a.jpg"),
            response = media(completed = true),
            template = Templates.MediaRapidReview.ref,
            context = ReviewContext.InList(com.example.listea.domain.ListId(listId)),
            now = 2000
        )

        assertEquals(0, rawInt("SELECT COUNT(*) FROM file_review_state"))
        assertEquals(listOf("a.jpg"), dao.getCheckedFilePaths(ROOT))
        assertEquals(1, dao.observeCheckedFileCount(ROOT).first())
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun media(completed: Boolean) =
        ReviewResponse.MediaReview(completed = completed, favorite = false, customActions = emptySet())

    private suspend fun folderList(files: List<String>): Long = dao.createFolderList(
        title = "Trip",
        rootUri = ROOT,
        relativePath = "",
        files = files.map { ScannedFile(name = it, relativePath = it, uri = "$ROOT/$it") },
        replacedListIds = emptyList(),
        webhookEnabled = false,
        webhookUrl = "",
        now = 1000
    )

    private suspend fun manualList(): Long = dao.insertList(
        ListEntity(title = "Trip", createdAt = 1000)
    )

    /** A source-backed membership of the shape the migration produces, with no registration row. */
    private fun writeMigratedSourceItem(listId: Long, relativePath: String) {
        val taskId = UUID.randomUUID().toString()
        val assetId = UUID.randomUUID().toString()
        val db = database.openHelper.writableDatabase
        db.execSQL("INSERT INTO tasks (id, createdAt) VALUES (?, ?)", arrayOf<Any?>(taskId, 1000))
        db.execSQL(
            "INSERT INTO assets (id, taskId, sortOrder, rootUri, relativePath, sourceMissing) " +
                "VALUES (?, ?, 0, ?, ?, 0)",
            arrayOf<Any?>(assetId, taskId, ROOT, relativePath)
        )
        db.execSQL(
            "INSERT INTO task_state (taskId, isCompleted, isFavorite, customActions, updatedAt) " +
                "VALUES (?, 0, 0, '', 1000)",
            arrayOf<Any?>(taskId)
        )
        db.execSQL(
            "INSERT INTO list_items (" +
                "publicId, listId, taskId, title, isCompleted, sortOrder, createdAt, " +
                "sourceUri, sourceRelativePath, rootRelativePath, sourceMissing, " +
                "sourceSizeBytes, sourceModifiedAt, isFavorite, customActions) " +
                "VALUES (?, ?, ?, ?, 0, 0, ?, ?, ?, ?, 0, NULL, NULL, 0, '')",
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
