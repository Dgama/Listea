package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.ImportPlan
import com.example.listea.ImporterKind
import com.example.listea.PartOrigin
import com.example.listea.PlannedPackage
import com.example.listea.PlannedPart
import com.example.listea.PlannedTask
import com.example.listea.PlannedTaskPrompt
import com.example.listea.domain.CompletionPolicy
import com.example.listea.domain.PlannedPromptResource
import com.example.listea.domain.ReviewResponse
import com.example.listea.domain.TemplateId
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.encode
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two Todos over one Package are two decisions, down to the parts.
 *
 * This is the release's motivating case at multi-part scale: one Section Task in "Dating" and
 * another in "Dating — second pass" answer the same Prompts independently. The Task loop already
 * kept Task ids separate; the parts split adds payload rows, state rows and Prompt links that must
 * be *per Task* rather than per Prompt key, or a refresh of one Todo would move the other's
 * questions under it.
 *
 * The second case is the one a shared row would fail: answer a part in one Todo, advance the
 * other's Package revision, and the answer must stay exactly where it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportIndependentTodosTest {

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
    fun `two Todos over one Package write separate Tasks, parts, payloads and checkpoints`() =
        runTest {
            val first = registerSource("Dating")
            val second = registerSource("Dating again")

            dao.runImport(first.id, "dating", plan(), 2000)
            dao.runImport(second.id, "dating", plan(), 3000)

            val firstTask = rawText("SELECT taskId FROM task_imports WHERE sourceId = '${first.id}'")
            val secondTask = rawText("SELECT taskId FROM task_imports WHERE sourceId = '${second.id}'")
            assertNotEquals(firstTask, secondTask)

            // Four part rows, two per Task, and the same part keys on both — because the key
            // names a Prompt, while the row is scoped to the Task that asked it.
            assertEquals(4, rawInt("SELECT COUNT(*) FROM task_parts"))
            assertEquals(2, rawInt("SELECT COUNT(*) FROM task_parts WHERE taskId = '$firstTask'"))
            assertEquals(2, rawInt("SELECT COUNT(*) FROM task_parts WHERE taskId = '$secondTask'"))
            assertEquals(4, rawInt("SELECT COUNT(*) FROM task_part_payloads"))
            assertEquals(
                listOf("q1|$firstTask", "q2|$firstTask"),
                rows(
                    "SELECT partKey || '|' || taskId FROM task_part_payloads " +
                        "WHERE taskId = '$firstTask' ORDER BY partKey"
                )
            )
            assertEquals(4, rawInt("SELECT COUNT(*) FROM task_part_state"))
            assertEquals(2, rawInt("SELECT COUNT(*) FROM package_checkpoints"))
            assertEquals(2, rawInt("SELECT COUNT(*) FROM package_compositions"))
            assertEquals(4, rawInt("SELECT COUNT(*) FROM package_composition_prompts"))
        }

    @Test
    fun `advancing one Todo's revision leaves the other Todo's answer alone`() = runTest {
        val first = registerSource("Dating")
        val second = registerSource("Dating again")
        dao.runImport(first.id, "dating", plan(), 2000)
        dao.runImport(second.id, "dating", plan(), 2000)
        val firstTask = rawText("SELECT taskId FROM task_imports WHERE sourceId = '${first.id}'")
        val secondTask = rawText("SELECT taskId FROM task_imports WHERE sourceId = '${second.id}'")
        val answer = ReviewResponse.MediaReview(completed = true, selections = setOf("keep"))
        dao.submitReview(
            taskId = firstTask,
            partKey = "q1",
            response = answer,
            template = template,
            context = null,
            completion = CompletionPolicy.SinglePart,
            completesTask = false,
            explicitCompletion = false,
            now = 2500
        )

        dao.runImport(second.id, "dating", plan(revision = 2), 3000)

        assertEquals(
            answer.encode(),
            rawText("SELECT response FROM task_part_state WHERE taskId = '$firstTask' AND partKey = 'q1'")
        )
        // The second Todo moved; the first did not.
        assertEquals(
            2,
            rawInt("SELECT revision FROM task_part_payloads WHERE taskId = '$secondTask' AND partKey = 'q1'")
        )
        assertEquals(
            1,
            rawInt("SELECT revision FROM task_part_payloads WHERE taskId = '$firstTask' AND partKey = 'q1'")
        )
        assertEquals(
            "",
            rawText("SELECT response FROM task_part_state WHERE taskId = '$secondTask' AND partKey = 'q1'")
        )
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private suspend fun registerSource(name: String): SourceEntity = dao.registerSource(
        rootUri = ROOT,
        relativePath = "dating",
        displayName = name,
        importerKind = ImporterKind.PACKAGE,
        now = 1000
    )

    private fun plan(revision: Int = 1): ImportPlan = ImportPlan(
        tasks = listOf(
            PlannedTask(
                groupPath = "$PACKAGE_KEY/~section/main",
                displayName = "main",
                compositionKey = "section:main",
                parts = listOf("q1", "q2").mapIndexed { position, key ->
                    PlannedPart(
                        partKey = key,
                        position = position,
                        origin = PartOrigin.PACKAGE,
                        prompt = PlannedTaskPrompt(
                            packageKey = PACKAGE_KEY,
                            promptKey = key,
                            kind = "profile",
                            schema = 1,
                            payload = """{"stem":"$key"}""",
                            revision = revision,
                            resources = emptyList<PlannedPromptResource>()
                        )
                    )
                }
            )
        ),
        notices = emptyList(),
        packages = listOf(
            PlannedPackage(
                rootRelativePath = "dating",
                packageKey = PACKAGE_KEY,
                protocol = "listea.package/2",
                title = "Section demo",
                revision = revision,
                fingerprint = "fp-$revision",
                resources = emptyList()
            )
        )
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
        const val PACKAGE_KEY = "section-demo"
    }
}
