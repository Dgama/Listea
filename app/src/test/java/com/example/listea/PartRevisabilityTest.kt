package com.example.listea

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.data.AddOnStore
import com.example.listea.data.DecisionTarget
import com.example.listea.data.ListeaDatabase
import com.example.listea.data.ListsDao
import com.example.listea.data.ReviewItem
import com.example.listea.data.ReviewPart
import com.example.listea.data.TemplateStore
import com.example.listea.domain.PlannedPromptResource
import com.example.listea.domain.ReviewContext
import com.example.listea.domain.StoredTemplateVersion
import com.example.listea.domain.Templates
import com.example.listea.domain.ViewerEvent
import com.example.listea.domain.parse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
 * "One answer for good" is a rule about a **part**, not about a Task.
 *
 * `PromptSubmissionTest` pins the non-revisable rule on a Task with one Prompt. Once a Task holds
 * several questions the old framing would be wrong in both directions: locking the whole Task would
 * refuse question two after question one was answered, and reading "has this Task been answered"
 * from the latest event would let one part's commit lock a sibling that was never shown.
 *
 * The rule the write boundary enforces is the part's own current state. These tests submit through
 * `submitItemReview`, the boundary every surface crosses, against a Task imported with two Prompts.
 * The refusals are asserted by what was *not* written: no event, and the part's answer unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PartRevisabilityTest {

    private lateinit var database: ListeaDatabase
    private lateinit var dao: ListsDao
    private val dispatcher = StandardTestDispatcher()

    private val application: Application
        get() = ApplicationProvider.getApplicationContext()

    private val like = ViewerEvent.Choice(optionId = "like", label = "Like")
    private val pass = ViewerEvent.Choice(optionId = "pass", label = "Pass")

    private val terms = ReviewTerms(
        context = ReviewContext.InFolder(ROOT, "dating"),
        template = Templates.StructuredSingleChoice.ref
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        database = Room.inMemoryDatabaseBuilder(application as Context, ListeaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.listsDao()
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a non-revisable part refuses its own second answer but not its sibling's first`() =
        runTest(dispatcher) {
            val taskId = importedTwoPartTask()
            val locked = onceOnly()

            assertTrue(submit(taskId, locked, "q1", like))
            assertFalse(submit(taskId, locked, "q1", pass))
            assertTrue(submit(taskId, locked, "q2", like))

            assertEquals(2, eventCount(taskId))
            assertEquals("the first answer is the one on record", like, storedChoice(taskId, "q1"))
        }

    @Test
    fun `an accepted second answer on a revisable part replaces its current state`() = runTest(dispatcher) {
        val taskId = importedTwoPartTask()

        assertTrue(submit(taskId, Templates.StructuredSingleChoice, "q1", like))
        assertTrue(submit(taskId, Templates.StructuredSingleChoice, "q1", pass))

        assertEquals(2, eventCount(taskId))
        assertEquals("pass", storedChoice(taskId, "q1")?.optionId)
        // The sibling's part state did not move: its row exists from import, still unanswered.
        assertEquals(
            0,
            rawInt(
                "SELECT COUNT(*) FROM task_part_state " +
                    "WHERE partKey = 'q2' AND (response != '' OR answeredAt IS NOT NULL)"
            )
        )
    }

    @Test
    fun `answered means this part's state, not the Task's newest event`() = runTest(dispatcher) {
        val taskId = importedTwoPartTask()
        val locked = onceOnly()

        // q2 is answered first, so the Task's latest event names q2. q1 has never been seen.
        assertTrue(submit(taskId, locked, "q2", like))
        assertTrue(submit(taskId, locked, "q1", like))
        // Now q1 has been answered once, and only now is it locked.
        assertFalse(submit(taskId, locked, "q1", pass))

        assertEquals(2, eventCount(taskId))
    }

    @Test
    fun `a set-aside answer permits one reconfirmation under a one-answer judgment`() =
        runTest(dispatcher) {
            val taskId = importedTwoPartTask()
            val locked = onceOnly()

            assertTrue(submit(taskId, locked, "q1", like))
            // One answer for good: there is no second one while the first counts.
            assertFalse(submit(taskId, locked, "q1", pass))

            // The branch closed and reopened, so the answer was kept but set aside.
            markStale(taskId, "q1")

            // A set-aside answer is not one the one-shot rule protects: the branch asks again.
            assertTrue(submit(taskId, locked, "q1", pass))
            // And once reconfirmed it counts again, so a second one is refused as before.
            assertFalse(submit(taskId, locked, "q1", like))

            assertEquals(pass, storedChoice(taskId, "q1"))
        }

    @Test
    fun `the refused answer leaves both the event history and the current state untouched`() =
        runTest(dispatcher) {
            val taskId = importedTwoPartTask()
            val locked = onceOnly()
            assertTrue(submit(taskId, locked, "q1", like))
            val stateBefore = rawText(
                "SELECT response FROM task_part_state WHERE taskId = '$taskId' AND partKey = 'q1'"
            )

            var accepted = true
            viewModel()
                .submitItemReview(itemOf(taskId), terms, locked, listOf(pass), "q1") {
                    accepted = it
                }
                .join()

            assertFalse(accepted)
            assertEquals(1, eventCount(taskId))
            assertEquals(
                stateBefore,
                rawText(
                    "SELECT response FROM task_part_state " +
                        "WHERE taskId = '$taskId' AND partKey = 'q1'"
                )
            )
        }

    // --- reaching the state above ----------------------------------------------------------------

    private fun onceOnly(): StoredTemplateVersion = Templates.StructuredSingleChoice.copy(
        taskReview = Templates.StructuredSingleChoice.taskReview!!.copy(revisable = false)
    )

    private suspend fun submit(
        taskId: String,
        template: StoredTemplateVersion,
        partKey: String,
        choice: ViewerEvent.Choice
    ): Boolean {
        var accepted = false
        viewModel()
            .submitItemReview(itemOf(taskId), terms, template, listOf(choice), partKey) {
                accepted = it
            }
            .join()
        return accepted
    }

    /** One Section composition holding two Prompt parts, imported as a Package import run would. */
    private suspend fun importedTwoPartTask(): String {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = "dating",
            displayName = "Dating",
            importerKind = ImporterKind.PACKAGE,
            now = 0
        )
        dao.runImport(
            source.id,
            "dating",
            ImportPlan(
                tasks = listOf(
                    PlannedTask(
                        groupPath = "$PACKAGE_KEY/~section/main",
                        displayName = "main",
                        compositionKey = "section:main",
                        parts = listOf(
                            part("q1", position = 0),
                            part("q2", position = 1)
                        )
                    )
                ),
                notices = emptyList(),
                packages = listOf(
                    PlannedPackage(
                        rootRelativePath = "dating",
                        packageKey = PACKAGE_KEY,
                        protocol = "listea.package/2",
                        title = "Dating demo",
                        revision = 1,
                        fingerprint = "fp-1",
                        resources = emptyList()
                    )
                )
            ),
            1000
        )
        return rawText(
            "SELECT taskId FROM task_imports WHERE groupPath = '$PACKAGE_KEY/~section/main'"
        )
    }

    private fun part(key: String, position: Int) = PlannedPart(
        partKey = key,
        position = position,
        origin = PartOrigin.PACKAGE,
        prompt = PlannedTaskPrompt(
            packageKey = PACKAGE_KEY,
            promptKey = key,
            kind = "profile",
            schema = 1,
            payload = PAYLOAD,
            revision = 1,
            resources = emptyList<PlannedPromptResource>()
        )
    )

    private suspend fun itemOf(taskId: String): ReviewItem {
        val parts = listOf("q1", "q2").mapIndexed { position, key ->
            val payload = dao.partPayload(taskId, key)!!
            ReviewPart(
                partKey = key,
                position = position,
                promptKey = key,
                payloadKind = payload.kind,
                payloadSchema = payload.schemaVersion,
                payloadRevision = payload.revision,
                // The queue a surface draws from carries the flag; a harness that left it out
                // would test every part as though its answer still counted.
                stale = dao.partStates(taskId).firstOrNull { it.partKey == key }?.stale ?: false
            )
        }
        return ReviewItem(
            id = 0L,
            taskId = taskId,
            publicId = "",
            title = "main",
            sourceUri = null,
            relativePath = "$PACKAGE_KEY/~section/main",
            sourceMissing = false,
            target = DecisionTarget.File(
                rootUri = ROOT,
                relativePath = "$PACKAGE_KEY/~section/main"
            ),
            packageKey = PACKAGE_KEY,
            parts = parts
        )
    }

    private fun storedChoice(taskId: String, partKey: String): ViewerEvent.Choice? {
        val response = rawText(
            "SELECT response FROM task_part_state " +
                "WHERE taskId = '$taskId' AND partKey = '$partKey'"
        )
        return (
            com.example.listea.domain.ReviewResponse.parse(response)
                as? com.example.listea.domain.ReviewResponse.PrimaryReview
            )
            ?.events
            ?.filterIsInstance<ViewerEvent.Choice>()
            ?.singleOrNull()
    }

    private fun eventCount(taskId: String): Int = rawInt(
        "SELECT COUNT(*) FROM review_events WHERE taskId = '$taskId'"
    )

    private fun viewModel() = ListsViewModel(
        application,
        dao,
        FakeFolderSource(),
        dispatcher,
        TemplateStore(database),
        AddOnStore(database)
    )

    /** The flag `setAnsweredPartsAside` writes when a branch closes; the state itself is kept. */
    private fun markStale(taskId: String, partKey: String) {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE task_part_state SET stale = 1 WHERE taskId = '$taskId' AND partKey = '$partKey'"
        )
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

    private companion object {
        const val ROOT = "content://root"
        const val PACKAGE_KEY = "dating-demo"
        const val PAYLOAD =
            """{"options":[{"id":"like","label":"Like"},{"id":"pass","label":"Pass"}],""" +
                """"profile":{"age":29,"bio":"Likes long walks","name":"Alice"},"stem":"Meet Alice?"}"""
    }
}
