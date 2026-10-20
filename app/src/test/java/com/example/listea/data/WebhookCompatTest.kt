package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.EVENT_LIST_COMPLETED
import com.example.listea.ExportPayload
import com.example.listea.FolderSnapshot
import com.example.listea.ImporterKind
import com.example.listea.TemplateResolver
import com.example.listea.buildWebhookPayload
import com.example.listea.exportTaskViews
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
    fun `a multi-Asset Task's public id is minted at import and never changes`() = runTest {
        val source = registerSource(ROOT, "", ImporterKind.FOLDER_AS_TASK)
        dao.runImport(source.id, "", folderPlan("", listOf("alice/a.jpg", "alice/b.jpg"), listOf("alice")), 2000)
        val taskId = dao.getCatalogTasks(source.id).single().taskId

        val first = dao.getPayloadTasks(listOf(taskId)).single().publicId
        val second = dao.getPayloadTasks(listOf(taskId)).single().publicId

        // One id, minted when the Task was created, read back on every later round. The delivery
        // path never mints: a write during a round could disagree with the Task it names.
        assertEquals(first, second)
        assertEquals(first, rawText("SELECT publicId FROM tasks WHERE id = '$taskId'"))
        assertTrue(first.isNotBlank())
        assertEquals(0, rawInt("SELECT COUNT(*) FROM legacy_task_ids"))
    }

    // §10
    @Test
    fun `the webhook body for a file-backed Task is the v2 hierarchy`() = runTest {
        val now = 1000L
        val listId = dao.createFolderList("Trip", ROOT, "2026-07-11", files("a.jpg"), emptyList(), false, now)
        val row = dao.getItems(listId).single().copy(isCompleted = true)

        val task = dao.getPayloadTasks(listOf(row.taskId)).single()
        val asset = dao.getPayloadAssets(listOf(row.taskId)).single()
        val payload = buildWebhookPayload(
            ExportPayload(
                event = EVENT_LIST_COMPLETED,
                listId = 7,
                listTitle = "Trip",
                completedAt = null,
                sourceId = null,
                sourceTitle = null,
                at = now,
                tasks = exportTaskViews(
                    rows = listOf(row),
                    tasks = mapOf(task.taskId to task),
                    assetsByTask = mapOf(task.taskId to listOf(asset)),
                    resolver = TemplateResolver.Empty
                )
            )
        )

        // No import, so no template: `templates` is empty rather than absent, and the Asset carries
        // the folder the file actually sits in. The two ids are the rows' own, minted at import.
        assertEquals(
            """{"version":2,"event":"list.completed",""" +
                """"list":{"id":7,"title":"Trip","completedAt":null},""" +
                """"tasks":[{"id":"${task.publicId}","title":"a.jpg","isCompleted":true,""" +
                """"templates":[],"assets":[{"id":"${asset.publicId}","title":"a.jpg",""" +
                """"relativePath":"2026-07-11","sourceMissing":false,"actions":[]}]}]}""",
            payload
        )
        assertTrue(task.publicId.isNotBlank())
        assertTrue(asset.publicId.isNotBlank())
        assertTrue(task.publicId != asset.publicId)
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun files(vararg names: String) = names.map {
        ScannedFile(name = it, relativePath = it, uri = "$ROOT/$it")
    }

    private suspend fun registerSource(rootUri: String, relativePath: String, kind: ImporterKind): SourceEntity =
        dao.registerSource(rootUri, relativePath, relativePath.ifEmpty { "Root" }, kind, keepTree = true, now = 1000)

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
