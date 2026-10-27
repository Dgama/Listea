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
 * A Section Task is identified by its composition rule, not by the Prompts inside it.
 *
 * A Package revision may add a question to the Section, drop one, or reorder them. None of those
 * is a different Task: the taker's rule — "these Prompts make one Task" — has not changed. The
 * write side already reconciled Tasks by `groupPath`; this suite pins that a multi-part Task keeps
 * that identity while its membership moves, and that changing the *rule* (a different
 * `compositionKey`, hence a different catalog key) is what mints a new Task.
 *
 * The cases are the four things a revision can do: add, remove, reorder, and re-key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompositionReconciliationTest {

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
    fun `adding a Prompt to a Section keeps the Task and appends the part`() = runTest {
        val source = registerSource()
        dao.runImport(source.id, "dating", plan(listOf("q1", "q2")), 2000)
        val taskId = rawText("SELECT taskId FROM task_imports")

        dao.runImport(source.id, "dating", plan(listOf("q1", "q2", "q3"), revision = 2), 3000)

        assertEquals(1, rawInt("SELECT COUNT(*) FROM tasks"))
        assertEquals(taskId, rawText("SELECT taskId FROM task_imports"))
        assertEquals(
            listOf("q1|0", "q2|1", "q3|2"),
            rows("SELECT partKey || '|' || position FROM task_parts ORDER BY position")
        )
        assertEquals(
            1,
            rawInt("SELECT COUNT(*) FROM package_composition_prompts WHERE promptKey = 'q3' AND present = 1")
        )
        assertEquals(1, rawInt("SELECT present FROM package_compositions"))
    }

    @Test
    fun `removing a Prompt detaches its part and keeps the Task`() = runTest {
        val source = registerSource()
        dao.runImport(source.id, "dating", plan(listOf("q1", "q2", "q3")), 2000)
        val taskId = rawText("SELECT taskId FROM task_imports")

        dao.runImport(source.id, "dating", plan(listOf("q1", "q2"), revision = 2), 3000)

        assertEquals(1, rawInt("SELECT COUNT(*) FROM tasks"))
        assertEquals(taskId, rawText("SELECT taskId FROM task_imports"))
        assertEquals(1, rawInt("SELECT present FROM task_parts WHERE partKey = 'q1'"))
        assertEquals(1, rawInt("SELECT present FROM task_parts WHERE partKey = 'q2'"))
        assertEquals(0, rawInt("SELECT present FROM task_parts WHERE partKey = 'q3'"))
        assertEquals(0, rawInt("SELECT present FROM package_composition_prompts WHERE promptKey = 'q3'"))
        // Detached is not deleted: the row and its body remain, so a later revision that names
        // q3 again brings back the same question rather than a stranger with the same key.
        assertEquals(1, rawInt("SELECT COUNT(*) FROM task_part_payloads WHERE partKey = 'q3'"))
    }

    @Test
    fun `reordering the Prompts moves positions without changing identities`() = runTest {
        val source = registerSource()
        dao.runImport(source.id, "dating", plan(listOf("q1", "q2", "q3")), 2000)
        val taskId = rawText("SELECT taskId FROM task_imports")

        dao.runImport(source.id, "dating", plan(listOf("q3", "q1", "q2"), revision = 2), 3000)

        assertEquals(1, rawInt("SELECT COUNT(*) FROM tasks"))
        assertEquals(taskId, rawText("SELECT taskId FROM task_imports"))
        assertEquals(
            listOf("q3|0", "q1|1", "q2|2"),
            rows("SELECT partKey || '|' || position FROM task_parts ORDER BY position")
        )
    }

    @Test
    fun `a different composition key mints a new Task and leaves the old one`() = runTest {
        val source = registerSource()
        dao.runImport(
            source.id,
            "dating",
            plan(listOf("q1", "q2"), compositionKey = "section:main", sectionKey = "main"),
            2000
        )
        val oldTaskId = rawText("SELECT taskId FROM task_imports")

        dao.runImport(
            source.id,
            "dating",
            plan(
                listOf("q1", "q2"),
                compositionKey = "section:other",
                sectionKey = "other",
                revision = 2
            ),
            3000
        )

        assertEquals(2, rawInt("SELECT COUNT(*) FROM tasks"))
        assertNotEquals(
            oldTaskId,
            rawText("SELECT taskId FROM task_imports WHERE groupPath = '$PACKAGE_KEY/~section/other'")
        )
        // The old Task and its parts stay exactly as reviewed; only the rule that assembled it is
        // marked gone, because the Package no longer plans that composition.
        assertEquals(
            1,
            rawInt("SELECT COUNT(*) FROM tasks WHERE id = '$oldTaskId'")
        )
        assertEquals(2, rawInt("SELECT COUNT(*) FROM task_parts WHERE taskId = '$oldTaskId'"))
        assertEquals(
            0,
            rawInt(
                "SELECT present FROM package_compositions WHERE taskId = '$oldTaskId'"
            )
        )
        assertEquals(
            1,
            rawInt(
                "SELECT present FROM package_compositions " +
                    "WHERE compositionKey = 'section:other'"
            )
        )
    }

    // --- completion follows membership -----------------------------------------------------------

    @Test
    fun `adding a required Prompt reopens a Task completed under the old membership`() = runTest {
        val source = registerSource()
        dao.runImport(source.id, "dating", plan(listOf("q1", "q2")), 2000)
        val taskId = rawText("SELECT taskId FROM task_imports")
        answer(taskId, "q1")
        answer(taskId, "q2", completesTask = true)
        assertEquals(1, completed(taskId))

        dao.runImport(
            source.id,
            "dating",
            plan(listOf("q1", "q2", "q3"), revision = 2),
            3000,
            completion = CompletionPolicy.AllRequiredParts
        )

        // q3 is a required part that nobody has answered. The Task's answer was true of a two-part
        // Task and is not true of this three-part one.
        assertEquals(0, completed(taskId))
        assertEquals(COMPLETION_AUTOMATIC, completionSource(taskId))
    }

    @Test
    fun `detaching the last unanswered required Prompt completes the Task`() = runTest {
        val source = registerSource()
        dao.runImport(source.id, "dating", plan(listOf("q1", "q2", "q3")), 2000)
        val taskId = rawText("SELECT taskId FROM task_imports")
        answer(taskId, "q1")
        answer(taskId, "q2")
        assertEquals(0, completed(taskId))

        dao.runImport(
            source.id,
            "dating",
            plan(listOf("q1", "q2"), revision = 2),
            3000,
            completion = CompletionPolicy.AllRequiredParts
        )

        // The Package stopped asking q3, so what held the Task open has gone. Leaving it incomplete
        // would make the Todo wait for an answer to a question it no longer has.
        assertEquals(1, completed(taskId))
        assertEquals(COMPLETION_AUTOMATIC, completionSource(taskId))
    }

    @Test
    fun `a sign-off survives a membership change`() = runTest {
        val source = registerSource()
        dao.runImport(source.id, "dating", plan(listOf("q1", "q2")), 2000)
        val taskId = rawText("SELECT taskId FROM task_imports")
        signOff(taskId)
        assertEquals(1, completed(taskId))

        dao.runImport(
            source.id,
            "dating",
            plan(listOf("q1", "q2", "q3"), revision = 2),
            3000,
            completion = CompletionPolicy.AllRequiredParts
        )

        // A person said this Task was done. A revision that adds a question is not a person
        // retracting that, and the provenance stays explicit so no later recompute can clear it.
        assertEquals(1, completed(taskId))
        assertEquals(COMPLETION_EXPLICIT, completionSource(taskId))
    }

    @Test
    fun `a SinglePart Task keeps its answer across a membership change`() = runTest {
        val source = registerSource()
        dao.runImport(source.id, "dating", plan(listOf("q1", "q2")), 2000)
        val taskId = rawText("SELECT taskId FROM task_imports")
        answer(taskId, "q2", completesTask = true)

        // No multi-part review is in force, so completion is the response's statement about the
        // Task and is not derived from the parts. The re-import must not invent an opinion.
        dao.runImport(
            source.id,
            "dating",
            plan(listOf("q1", "q2", "q3"), revision = 2),
            3000,
            completion = CompletionPolicy.SinglePart
        )

        assertEquals(1, completed(taskId))
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private suspend fun answer(
        taskId: String,
        partKey: String,
        completesTask: Boolean = false
    ) {
        dao.submitReview(
            taskId = taskId,
            partKey = partKey,
            response = ReviewResponse.MediaReview(completed = true, selections = emptySet()),
            template = REVIEW,
            context = null,
            completion = CompletionPolicy.SinglePart,
            completesTask = completesTask,
            explicitCompletion = false,
            now = 1500
        )
    }

    private suspend fun signOff(taskId: String) {
        dao.submitReview(
            taskId = taskId,
            partKey = null,
            response = ReviewResponse.MediaReview(completed = true, selections = emptySet()),
            template = REVIEW,
            context = null,
            completion = CompletionPolicy.SinglePart,
            completesTask = false,
            explicitCompletion = true,
            now = 1500
        )
    }

    private fun completed(taskId: String): Int =
        rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$taskId'")

    private fun completionSource(taskId: String): String =
        rawText("SELECT completionSource FROM task_state WHERE taskId = '$taskId'")

    private suspend fun registerSource(): SourceEntity = dao.registerSource(
        rootUri = ROOT,
        relativePath = "dating",
        displayName = "Dating",
        importerKind = ImporterKind.PACKAGE,
        now = 1000
    )

    private fun plan(
        prompts: List<String>,
        compositionKey: String = "section:main",
        sectionKey: String = "main",
        revision: Int = 1
    ): ImportPlan = ImportPlan(
        tasks = listOf(
            PlannedTask(
                groupPath = "$PACKAGE_KEY/~section/$sectionKey",
                displayName = sectionKey,
                compositionKey = compositionKey,
                parts = prompts.mapIndexed { position, key ->
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
        val REVIEW = TemplateVersionRef(TemplateId("fixture.review"), 1)
    }
}
