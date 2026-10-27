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
import com.example.listea.domain.ViewerEvent
import com.example.listea.domain.encode
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Dissolving a Task of several questions and getting its answers back.
 *
 * A dissolved history used to be one judgment per Task, because a Task *was* one judgment. The
 * parts split makes the Task the container and the answer per part, so the snapshot has to carry
 * each part's current response or a recovery brings back a Task whose questions were all blank.
 *
 * The exit case is the whole feature: a three-part Section Task with one part answered is dissolved,
 * re-imported at the same folder, and comes back with the answered part answered and the other two
 * still pending. The remaining cases are the two ways a history can be less than it looks — a part
 * the new composition no longer names, and a history written before part answers existed — and each
 * must fail closed rather than restore an answer onto the wrong question.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DissolvedPartsTest {

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
    fun `a three-part Task keeps its one answer and its two pending parts across dissolve and recover`() =
        runTest {
            val first = importTodo("dating", plan())
            val oldTask = taskOf(first)
            val answer = ReviewResponse.PrimaryReview(
                completed = true,
                events = listOf(ViewerEvent.Choice(optionId = "like", label = "Like"))
            )
            dao.submitReview(
                taskId = oldTask,
                partKey = "q2",
                response = answer,
                template = template,
                context = null,
                completion = CompletionPolicy.SinglePart,
                completesTask = false,
                explicitCompletion = false,
                now = 2500
            )

            dao.deleteTodo(first.id, keepState = true, now = 5000)

            // The snapshot names every present part, answered or not, because "pending" is a fact
            // the history must be able to restore rather than one it may infer from absence.
            val recorded = decodeDissolvedParts(rawText("SELECT partRecords FROM dissolved_task_state"))
            assertEquals(listOf("q1", "q2", "q3"), recorded?.map { it.partKey })
            assertEquals(answer.encode(), recorded?.single { it.partKey == "q2" }?.response)
            assertEquals("", recorded?.single { it.partKey == "q1" }?.response)

            val second = dao.registerSource(
                rootUri = ROOT,
                relativePath = "dating",
                displayName = "Dating again",
                importerKind = ImporterKind.PACKAGE,
                now = 6000
            )
            val result = dao.runImport(
                second.id,
                "dating",
                plan(),
                6000,
                recoverFrom = first.id
            )

            assertEquals(1, result.tasksRecovered)
            val newTask = taskOf(second)
            assertEquals(3, rawInt("SELECT COUNT(*) FROM task_parts WHERE taskId = '$newTask'"))
            assertEquals(
                answer.encode(),
                rawText(
                    "SELECT response FROM task_part_state " +
                        "WHERE taskId = '$newTask' AND partKey = 'q2'"
                )
            )
            assertEquals(
                6000,
                rawInt(
                    "SELECT answeredAt FROM task_part_state " +
                        "WHERE taskId = '$newTask' AND partKey = 'q2'"
                )
            )
            // The recovery says when the answer was restored, not which body it was made against:
            // the dissolved Task's revision is not this Task's.
            assertNull(
                nullableText(
                    "SELECT revision FROM task_part_state " +
                        "WHERE taskId = '$newTask' AND partKey = 'q2'"
                )
            )
            for (pending in listOf("q1", "q3")) {
                assertEquals(
                    "",
                    rawText(
                        "SELECT response FROM task_part_state " +
                            "WHERE taskId = '$newTask' AND partKey = '$pending'"
                    )
                )
                assertNull(
                    nullableText(
                        "SELECT answeredAt FROM task_part_state " +
                            "WHERE taskId = '$newTask' AND partKey = '$pending'"
                    )
                )
            }
        }

    @Test
    fun `an answered part the new composition does not name is not restored`() = runTest {
        val first = importTodo("dating", plan())
        val oldTask = taskOf(first)
        dao.submitReview(
            taskId = oldTask,
            partKey = "q3",
            response = ReviewResponse.PrimaryReview(
                completed = true,
                events = listOf(ViewerEvent.Choice("yes", "Yes"))
            ),
            template = template,
            context = null,
            completion = CompletionPolicy.SinglePart,
            completesTask = false,
            explicitCompletion = false,
            now = 2500
        )
        dao.deleteTodo(first.id, keepState = true, now = 5000)

        // The revision drops q3, so the new Task has no question to receive its answer.
        val second = dao.registerSource(
            rootUri = ROOT,
            relativePath = "dating",
            displayName = "Dating again",
            importerKind = ImporterKind.PACKAGE,
            now = 6000
        )
        val result = dao.runImport(
            second.id,
            "dating",
            plan(prompts = listOf("q1", "q2")),
            6000,
            recoverFrom = first.id
        )

        assertEquals(1, result.tasksRecovered)
        val newTask = taskOf(second)
        assertEquals(2, rawInt("SELECT COUNT(*) FROM task_parts WHERE taskId = '$newTask'"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM task_part_state WHERE partKey = 'q3'"))
        assertEquals(
            "",
            rawText("SELECT response FROM task_part_state WHERE taskId = '$newTask' AND partKey = 'q1'")
        )
    }

    @Test
    fun `a history written before part answers restores completion and no answers`() = runTest {
        val first = importTodo("dating", plan())
        val oldTask = taskOf(first)
        dao.submitReview(
            taskId = oldTask,
            partKey = "q2",
            response = ReviewResponse.PrimaryReview(
                completed = true,
                events = listOf(ViewerEvent.Choice("like", "Like"))
            ),
            template = template,
            context = null,
            completion = CompletionPolicy.SinglePart,
            completesTask = false,
            explicitCompletion = false,
            now = 2500
        )
        dao.setTaskCompleted(oldTask, true, 2600)
        dao.deleteTodo(first.id, keepState = true, now = 5000)
        // What a history from before schema 33 holds: a completion and no part records at all.
        rawExec("UPDATE dissolved_task_state SET partRecords = ''")

        val second = dao.registerSource(
            rootUri = ROOT,
            relativePath = "dating",
            displayName = "Dating again",
            importerKind = ImporterKind.PACKAGE,
            now = 6000
        )
        val result = dao.runImport(second.id, "dating", plan(), 6000, recoverFrom = first.id)

        assertEquals(1, result.tasksRecovered)
        val newTask = taskOf(second)
        // The completion is what the old snapshot did record, and it comes back.
        assertEquals(1, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$newTask'"))
        // Part answers it never recorded are not invented.
        assertEquals(
            0,
            rawInt(
                "SELECT COUNT(*) FROM task_part_state " +
                    "WHERE taskId = '$newTask' AND (response != '' OR answeredAt IS NOT NULL)"
            )
        )
    }

    @Test
    fun `an unreadable part payload restores completion and no answers rather than guessing`() =
        runTest {
            val first = importTodo("dating", plan())
            dao.deleteTodo(first.id, keepState = true, now = 5000)
            rawExec("UPDATE dissolved_task_state SET partRecords = '99\not a real payload'")

            val second = dao.registerSource(
                rootUri = ROOT,
                relativePath = "dating",
                displayName = "Dating again",
                importerKind = ImporterKind.PACKAGE,
                now = 6000
            )
            val result = dao.runImport(second.id, "dating", plan(), 6000, recoverFrom = first.id)

            assertEquals(1, result.tasksRecovered)
            val newTask = taskOf(second)
            assertEquals(
                0,
                rawInt(
                    "SELECT COUNT(*) FROM task_part_state " +
                        "WHERE taskId = '$newTask' AND (response != '' OR answeredAt IS NOT NULL)"
                )
            )
        }

    // --- fixtures ---------------------------------------------------------------------------------

    private suspend fun importTodo(folder: String, plan: ImportPlan): SourceEntity {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = folder,
            displayName = "Dating",
            importerKind = ImporterKind.PACKAGE,
            now = 1000
        )
        dao.runImport(source.id, folder, plan, 2000)
        return source
    }

    private suspend fun taskOf(source: SourceEntity): String =
        rawText("SELECT taskId FROM task_imports WHERE sourceId = '${source.id}'")

    private fun plan(prompts: List<String> = listOf("q1", "q2", "q3")): ImportPlan = ImportPlan(
        tasks = listOf(
            PlannedTask(
                groupPath = "$PACKAGE_KEY/~section/main",
                displayName = "main",
                compositionKey = "section:main",
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
                            revision = 1,
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
                revision = 1,
                fingerprint = "fp-1",
                resources = emptyList()
            )
        )
    )

    // --- raw reads --------------------------------------------------------------------------------

    private fun rawExec(sql: String) {
        database.openHelper.writableDatabase.execSQL(sql)
    }

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

    private fun nullableText(sql: String): String? =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            assertTrue("no row for: $sql", cursor.moveToFirst())
            if (cursor.isNull(0)) null else cursor.getString(0)
        }

    private companion object {
        const val ROOT = "content://root"
        const val PACKAGE_KEY = "section-demo"
    }
}
