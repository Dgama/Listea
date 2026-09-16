package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.AppSettings
import com.example.listea.EVENT_LIST_COMPLETED
import com.example.listea.FolderSnapshot
import com.example.listea.ImporterKind
import com.example.listea.buildWebhookPayload
import com.example.listea.plan
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
 * The compatibility boundary §10 draws, held down from both sides: a folder Task behaves like a
 * file Task on the wire except where a viewer is missing, and a file Task's body is byte-identical
 * to what v0.1 sent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebhookCompatTest {

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

    // §10
    @Test
    fun `a completed folder Task contributes all its Assets to the checked-file reader`() = runTest {
        val source = registerSource(ROOT, "", ImporterKind.FOLDER_AS_TASK)
        dao.runImport(source.id, "", folderPlan("", listOf("alice/a.jpg", "alice/b.jpg"), listOf("alice")), 2000)
        val taskId = dao.getCatalogTasks(source.id).single().taskId

        dao.setTaskCompleted(taskId, completed = true, now = 3000)

        // The truthful answer to "which files did the user check" is all of the Task's Assets.
        assertEquals(listOf("alice/a.jpg", "alice/b.jpg"), dao.getCheckedFilePaths(ROOT))
        assertEquals(2, dao.observeCheckedFileCount(ROOT).first())
    }

    // §2.4, §10, AR-7
    @Test
    fun `a multi-Asset Task gets a stable wire id across rounds, stored in legacy_task_ids`() =
        runTest {
            val source = registerSource(ROOT, "", ImporterKind.FOLDER_AS_TASK)
            dao.runImport(source.id, "", folderPlan("", listOf("alice/a.jpg", "alice/b.jpg"), listOf("alice")), 2000)
            val taskId = dao.getCatalogTasks(source.id).single().taskId

            val first = dao.legacyTaskId(taskId, 3000)
            val second = dao.legacyTaskId(taskId, 4000)

            // One id, minted once, read back on every later round.
            assertEquals(first, second)
            assertEquals(first, rawText("SELECT publicId FROM legacy_task_ids WHERE taskId = '$taskId'"))
            assertEquals(1, rawInt("SELECT COUNT(*) FROM legacy_task_ids"))
        }

    // §10
    @Test
    fun `the webhook body for a file-backed Task is byte-identical before and after the release`() =
        runTest {
            val now = 1000L
            val listId = dao.createFolderList("Trip", ROOT, "2026-07-11", files("a.jpg"), emptyList(), false, "", now)
            val list = dao.getList(listId)!!.copy(id = 7)
            // Normalise the random public id and the checked state so the body is deterministic; the
            // row itself is what the recomposed createFolderList produced.
            val item = dao.getItems(listId).single().copy(publicId = "PUBLIC-1", isCompleted = true)

            val payload = buildWebhookPayload(EVENT_LIST_COMPLETED, list, listOf(item), AppSettings())

            assertEquals(
                """{"event":"list.completed",""" +
                    """"list":{"id":7,"title":"Trip","completedAt":null},""" +
                    """"items":[{"id":"PUBLIC-1","title":"a.jpg","isCompleted":true,""" +
                    """"relativePath":"2026-07-11","actions":[]}]}""",
                payload
            )
        }

    // --- helpers ---------------------------------------------------------------------------------

    private fun files(vararg names: String) = names.map {
        ScannedFile(name = it, relativePath = it, uri = "$ROOT/$it")
    }

    private suspend fun registerSource(rootUri: String, relativePath: String, kind: ImporterKind): SourceEntity =
        when (val result = dao.registerSource(rootUri, relativePath, relativePath.ifEmpty { "Root" }, kind, 1000)) {
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

    private fun rawText(sql: String): String =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            assertTrue("no row for: $sql", cursor.moveToFirst())
            cursor.getString(0)
        }

    private companion object {
        const val ROOT = "content://root"
    }
}
