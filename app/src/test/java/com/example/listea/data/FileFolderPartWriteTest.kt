package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.FolderSnapshot
import com.example.listea.ImporterKind
import com.example.listea.domain.CompletionPolicy
import com.example.listea.domain.ReviewResponse
import com.example.listea.domain.TemplateId
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.encode
import com.example.listea.plan
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
 * A locally-planned Task is one degenerate part, not a one-part Package.
 *
 * A folder or a file says nothing about how many questions it contains, so the local importers
 * synthesize exactly one part — `local:0`, with the folder or file as its origin, no Prompt, no
 * payload and all the files as its Assets. That part is what makes a media Task fit the same page
 * model as a three-question Section Task: every Task has parts, and a media Task has one.
 *
 * The refresh case is the old behaviour in the new shape: the Task id and the part row survive, a
 * new file is added to the same part, and an answer already made is not blanked by a re-import.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileFolderPartWriteTest {

    private lateinit var database: ListeaDatabase
    private lateinit var dao: ListsDao

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val template = TemplateVersionRef(TemplateId("fixture.review"), 1)

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
    fun `a folder-as-Task writes one local part holding every file`() = runTest {
        val source = registerSource(ImporterKind.FOLDER_AS_TASK)
        val snapshot = FolderSnapshot(
            files = listOf(
                file("a.jpg", "trip/a.jpg"),
                file("b.jpg", "trip/b.jpg"),
                file("c.jpg", "trip/c.jpg")
            ),
            directories = listOf("trip")
        )

        val result = dao.runImport(source.id, "holiday", plan(ImporterKind.FOLDER_AS_TASK, "holiday", snapshot), 2000)

        assertEquals(ImportOutcome.COMPLETE, result.outcome)
        assertEquals(1, result.tasksCreated)
        assertEquals(3, result.assetsCreated)
        assertEquals(1, rawInt("SELECT COUNT(*) FROM tasks"))
        assertEquals("holiday/trip|0|folder||1|1|", rawText(
            "SELECT groupPath || '|' || p.position || '|' || p.origin || '|' || " +
                "IFNULL(p.promptKey, '') || '|' || p.required || '|' || p.present || '|' || " +
                "IFNULL(p.availability, '') " +
                "FROM task_parts p JOIN task_imports ti ON ti.taskId = p.taskId"
        ))
        // A local part has no body: there is no Prompt it could have read one from.
        assertEquals(0, rawInt("SELECT COUNT(*) FROM task_part_payloads"))
        assertEquals(1, rawInt("SELECT COUNT(*) FROM task_part_state"))
        assertEquals(
            "",
            rawText("SELECT response FROM task_part_state")
        )
        assertEquals(
            listOf(
                "local:0|holiday/trip/a.jpg|0",
                "local:0|holiday/trip/b.jpg|1",
                "local:0|holiday/trip/c.jpg|2"
            ),
            rows("SELECT partKey || '|' || relativePath || '|' || sortOrder FROM assets ORDER BY sortOrder")
        )
        assertEquals(0, rawInt("SELECT COUNT(*) FROM package_compositions"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM package_composition_prompts"))
    }

    @Test
    fun `a file-as-Task writes one local part with the file origin`() = runTest {
        val source = registerSource(ImporterKind.FILE_AS_TASK)
        val snapshot = FolderSnapshot(
            files = listOf(file("a.jpg", "a.jpg")),
            directories = emptyList()
        )

        dao.runImport(source.id, "holiday", plan(ImporterKind.FILE_AS_TASK, "holiday", snapshot), 2000)

        assertEquals(1, rawInt("SELECT COUNT(*) FROM tasks"))
        assertEquals("holiday/a.jpg", rawText("SELECT groupPath FROM task_imports"))
        assertEquals("local:0|file|0", rawText(
            "SELECT partKey || '|' || origin || '|' || " +
                "(SELECT COUNT(*) FROM task_part_payloads) FROM task_parts"
        ))
        assertEquals(1, rawInt("SELECT COUNT(*) FROM assets WHERE partKey = 'local:0'"))
        assertEquals(1, rawInt("SELECT COUNT(*) FROM task_part_state"))
    }

    @Test
    fun `a refresh keeps the part and its answer and adds the new file to it`() = runTest {
        val source = registerSource(ImporterKind.FOLDER_AS_TASK)
        dao.runImport(
            source.id,
            "holiday",
            plan(
                ImporterKind.FOLDER_AS_TASK,
                "holiday",
                FolderSnapshot(
                    files = listOf(
                        file("a.jpg", "trip/a.jpg"),
                        file("b.jpg", "trip/b.jpg")
                    ),
                    directories = listOf("trip")
                )
            ),
            2000
        )
        val taskId = rawText("SELECT taskId FROM task_imports")
        val firstAssetId = rawText("SELECT id FROM assets WHERE relativePath = 'holiday/trip/a.jpg'")
        val answer = ReviewResponse.MediaReview(completed = true, selections = setOf("keep"))
        dao.submitReview(
            taskId = taskId,
            partKey = "local:0",
            response = answer,
            template = template,
            context = null,
            completion = CompletionPolicy.SinglePart,
            completesTask = false,
            explicitCompletion = false,
            now = 2500
        )

        dao.runImport(
            source.id,
            "holiday",
            plan(
                ImporterKind.FOLDER_AS_TASK,
                "holiday",
                FolderSnapshot(
                    files = listOf(
                        file("a.jpg", "trip/a.jpg", sizeBytes = 99L, lastModified = 77L),
                        file("b.jpg", "trip/b.jpg"),
                        file("c.jpg", "trip/c.jpg")
                    ),
                    directories = listOf("trip")
                )
            ),
            3000
        )

        assertEquals(1, rawInt("SELECT COUNT(*) FROM tasks"))
        assertEquals(taskId, rawText("SELECT taskId FROM task_imports"))
        assertEquals(1, rawInt("SELECT COUNT(*) FROM task_parts"))
        assertEquals(1, rawInt("SELECT COUNT(*) FROM task_parts WHERE partKey = 'local:0'"))
        assertEquals(
            answer.encode(),
            rawText("SELECT response FROM task_part_state WHERE partKey = 'local:0'")
        )
        // The file that was already there keeps its Asset row; its measurements are refreshed.
        assertEquals(firstAssetId, rawText("SELECT id FROM assets WHERE relativePath = 'holiday/trip/a.jpg'"))
        assertEquals(99L, rawInt("SELECT sizeBytes FROM assets WHERE relativePath = 'holiday/trip/a.jpg'").toLong())
        assertEquals(77L, rawInt("SELECT lastModified FROM assets WHERE relativePath = 'holiday/trip/a.jpg'").toLong())
        // The new file joins the same part rather than minting a second one.
        assertEquals(
            1,
            rawInt("SELECT COUNT(DISTINCT partKey) FROM assets")
        )
        assertEquals(3, rawInt("SELECT COUNT(*) FROM assets WHERE partKey = 'local:0'"))
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private suspend fun registerSource(importer: ImporterKind): SourceEntity = dao.registerSource(
        rootUri = ROOT,
        relativePath = "holiday",
        displayName = "Holiday",
        importerKind = importer,
        now = 1000
    )

    private fun file(
        name: String,
        relativePath: String,
        sizeBytes: Long? = 10L,
        lastModified: Long? = 20L
    ) = com.example.listea.data.ScannedFile(
        name = name,
        relativePath = relativePath,
        uri = "content://root/holiday/$relativePath",
        sizeBytes = sizeBytes,
        lastModified = lastModified
    )

    // --- raw reads --------------------------------------------------------------------------------

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

    private fun rows(sql: String): List<String> =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).joinToString("|") { cursor.getString(it) })
                }
            }
        }

    private companion object {
        const val ROOT = "content://root"
    }
}
