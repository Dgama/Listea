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
import com.example.listea.domain.CONDITION_FIELD_CHOICE
import com.example.listea.domain.CompletionPolicy
import com.example.listea.domain.PartCondition
import com.example.listea.domain.PartConditionMatch
import com.example.listea.domain.PlannedPromptResource
import com.example.listea.domain.ReviewResponse
import com.example.listea.domain.TemplateId
import com.example.listea.domain.TemplateVersionRef
import com.example.listea.domain.Templates
import com.example.listea.domain.ViewerEvent
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
 * Completion, stale answers and reopening, as the Task's rows and branches say them.
 *
 * A Task's completion under a part policy is a fact about the **open** required parts: a branch a
 * condition closed is not work anyone owes, and an answer set aside when its branch closed is on
 * record but is not an answer. These tests pin the four transitions that follow from that — a Task
 * waiting for an open branch, a Task completing when the branch is closed, an answer going stale
 * when its branch closes, and a completed Task reopening when required work comes back.
 *
 * The reopen case is deliberately explicit as well as automatic: a sign-off is not a retraction,
 * but it was a statement about the Task as it then stood, and a branch that brings required work
 * back takes it away under `reopened`, not under a person's own name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PartCompletionTest {

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
    fun `allRequiredParts waits for every open branch and completes on the last one`() = runTest {
        val source = registerSource()
        dao.runImport(
            source.id, "dating", plan(gate = null, menu = gateIs("yes"), receipt = null), 1000,
            completion = CompletionPolicy.AllRequiredParts
        )
        val taskId = taskId()

        answer(taskId, "gate", "yes")
        assertEquals(0, completed(taskId))
        answer(taskId, "menu", "yes")
        assertEquals(0, completed(taskId))
        answer(taskId, "receipt", "yes")

        assertEquals(1, completed(taskId))
        assertEquals(COMPLETION_AUTOMATIC, completionSource(taskId))
    }

    @Test
    fun `a branch the gate closed is not work the Task is waiting for`() = runTest {
        val source = registerSource()
        dao.runImport(
            source.id, "dating", plan(gate = null, menu = gateIs("yes"), receipt = null), 1000,
            completion = CompletionPolicy.AllRequiredParts
        )
        val taskId = taskId()

        answer(taskId, "gate", "no")
        answer(taskId, "receipt", "yes")

        // The menu was never answered and the Task is still done: the branch it sits on is closed,
        // so the question is not owed. Counting it would leave the Task open for a page the person
        // cannot reach.
        assertEquals(1, completed(taskId))
    }

    @Test
    fun `an answer set aside when its branch closes is kept but no longer counts`() = runTest {
        val source = registerSource()
        dao.runImport(
            source.id, "dating", plan(gate = null, menu = gateIs("yes"), receipt = null), 1000,
            completion = CompletionPolicy.AllRequiredParts
        )
        val taskId = taskId()

        answer(taskId, "gate", "yes")
        answer(taskId, "menu", "yes", now = 1600)
        assertEquals(0, rawInt("SELECT stale FROM task_part_state WHERE partKey = 'menu'"))

        answer(taskId, "gate", "no", now = 1700)

        // Set aside, not deleted: the export still says the menu was answered "yes" while its
        // branch was open, and a fresh commit is the only thing that makes it current again.
        assertEquals(1, rawInt("SELECT stale FROM task_part_state WHERE partKey = 'menu'"))
        assertTrue(rawText("SELECT response FROM task_part_state WHERE partKey = 'menu'").isNotEmpty())
        assertEquals(listOf("gate"), dao.observeAnsweredPartKeys(taskId).first())
    }

    @Test
    fun `a masked part refuses a submission and records no event`() = runTest {
        val source = registerSource()
        dao.runImport(
            source.id, "dating", plan(gate = null, menu = gateIs("yes"), receipt = null), 1000,
            completion = CompletionPolicy.AllRequiredParts
        )
        val taskId = taskId()

        answer(taskId, "gate", "no")
        val eventsBefore = rawInt("SELECT COUNT(*) FROM review_events")

        val written = answer(taskId, "menu", "yes")

        assertEquals(emptyList<Long>(), written)
        assertEquals(eventsBefore, rawInt("SELECT COUNT(*) FROM review_events"))
        // Every part has a state row from the import; the refusal is that this one still holds no
        // answer, not that the row is absent.
        assertEquals(
            0,
            rawInt(
                "SELECT COUNT(*) FROM task_part_state WHERE partKey = 'menu' " +
                    "AND (answeredAt IS NOT NULL OR response != '')"
            )
        )
    }

    @Test
    fun `a required branch reopening reopens an automatic completion`() = runTest {
        val source = registerSource()
        dao.runImport(
            source.id, "dating", plan(gate = null, menu = gateIs("yes"), receipt = null), 1000,
            completion = CompletionPolicy.AllRequiredParts
        )
        val taskId = taskId()

        answer(taskId, "gate", "no")
        answer(taskId, "receipt", "yes")
        assertEquals(1, completed(taskId))
        assertEquals(COMPLETION_AUTOMATIC, completionSource(taskId))

        answer(taskId, "gate", "yes", now = 1800)

        assertEquals(0, completed(taskId))
        assertEquals(COMPLETION_REOPENED, completionSource(taskId))
    }

    @Test
    fun `a part answer that cannot complete keeps the reopen reason`() = runTest {
        val source = registerSource()
        dao.runImport(
            source.id, "dating", plan(gate = null, menu = gateIs("yes"), receipt = null), 1000,
            completion = CompletionPolicy.AllRequiredParts
        )
        val taskId = taskId()

        answer(taskId, "gate", "no")
        answer(taskId, "receipt", "yes")
        answer(taskId, "gate", "yes", now = 1800)
        assertEquals(COMPLETION_REOPENED, completionSource(taskId))

        // The menu is still open, so this answer cannot complete the Task and is not a second
        // transition. The row must still say why the completion went away — a reader that found
        // `automatic` here would have no way to tell the Task changed from the person retracting.
        answer(taskId, "gate", "yes", now = 1900)

        assertEquals(0, completed(taskId))
        assertEquals(COMPLETION_REOPENED, completionSource(taskId))

        // Completing it again is a new completion, and the marker moves with it.
        answer(taskId, "menu", "yes", now = 2000)
        assertEquals(1, completed(taskId))
        assertEquals(COMPLETION_AUTOMATIC, completionSource(taskId))
    }

    @Test
    fun `a required branch reopening reopens an explicit sign-off`() = runTest {
        val source = registerSource()
        dao.runImport(
            source.id, "dating", plan(gate = null, menu = gateIs("yes"), receipt = null), 1000,
            completion = CompletionPolicy.AllRequiredParts
        )
        val taskId = taskId()

        answer(taskId, "gate", "no")
        signOff(taskId)
        assertEquals(1, completed(taskId))
        assertEquals(COMPLETION_EXPLICIT, completionSource(taskId))

        answer(taskId, "gate", "yes", now = 1800)

        // The sign-off said this Task was done as it stood. The menu coming back means it no longer
        // is, and the provenance says the Task changed rather than the person retracting.
        assertEquals(0, completed(taskId))
        assertEquals(COMPLETION_REOPENED, completionSource(taskId))
    }

    @Test
    fun `anyPart completes on the first open required answer`() = runTest {
        val source = registerSource()
        dao.runImport(
            source.id, "dating", plan(gate = null, menu = gateIs("yes"), receipt = null), 1000,
            completion = CompletionPolicy.AnyPart
        )
        val taskId = taskId()

        answer(taskId, "gate", "yes", completion = CompletionPolicy.AnyPart)

        assertEquals(1, completed(taskId))
        assertEquals(COMPLETION_AUTOMATIC, completionSource(taskId))
    }

    @Test
    fun `allRequiredParts is satisfied when no required part is open`() = runTest {
        val source = registerSource()
        dao.runImport(
            source.id,
            "dating",
            plan(gate = null, menu = gateIs("yes"), receipt = gateIs("yes"), gateRequired = false),
            1000,
            completion = CompletionPolicy.AllRequiredParts
        )
        val taskId = taskId()

        answer(taskId, "gate", "no")

        // Nothing required is open, so there is no open work left. This is the policy's meaning,
        // not an accident of an empty list.
        assertEquals(1, completed(taskId))
        assertEquals(COMPLETION_AUTOMATIC, completionSource(taskId))
    }

    @Test
    fun `a mixed Task recomputes under its own container, not the run's review`() = runTest {
        val source = registerSource()
        dao.runImport(
            source.id, "dating", plan(mixed = true), 1000,
            completion = CompletionPolicy.SinglePart
        )
        val taskId = taskId()
        answer(taskId, "gate", "yes")
        answer(taskId, "menu", "yes")
        answer(taskId, "receipt", "yes")
        assertEquals(1, completed(taskId))

        dao.runImport(
            source.id, "dating", plan(extra = true, revision = 2, mixed = true), 4000,
            completion = CompletionPolicy.SinglePart
        )

        // The run's review is singlePart, but the Task stamped the mixed container, whose policy
        // is a fact about its parts. Judging by the run's review would leave a completed Task
        // holding a required part nobody answered.
        assertEquals(0, completed(taskId))
        assertEquals(COMPLETION_AUTOMATIC, completionSource(taskId))
    }

    @Test
    fun `a revision that re-points a condition reopens the completed Task`() = runTest {
        val source = registerSource()
        dao.runImport(
            source.id, "dating", plan(gate = null, menu = gateIs("no"), receipt = null), 1000,
            completion = CompletionPolicy.AllRequiredParts
        )
        val taskId = taskId()

        // With the menu on the "no" branch, answering the gate "yes" closes it and completes the
        // Task with the receipt.
        answer(taskId, "gate", "yes")
        answer(taskId, "receipt", "yes")
        assertEquals(1, completed(taskId))

        // The Package now asks the menu when the gate is "yes". The branch opens under a revision,
        // with nobody answering anything, and the Task is no longer done.
        dao.runImport(
            source.id,
            "dating",
            plan(gate = null, menu = gateIs("yes"), receipt = null, revision = 2),
            4000,
            completion = CompletionPolicy.AllRequiredParts
        )

        assertEquals(0, completed(taskId))
        assertEquals(COMPLETION_REOPENED, completionSource(taskId))
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private fun plan(
        gate: PartCondition? = null,
        menu: PartCondition? = null,
        receipt: PartCondition? = null,
        revision: Int = 1,
        gateRequired: Boolean = true,
        extra: Boolean = false,
        mixed: Boolean = false
    ): ImportPlan = ImportPlan(
        tasks = listOf(
            PlannedTask(
                groupPath = "$PACKAGE_KEY/~section/main",
                displayName = "Main",
                compositionKey = "section:main",
                taskReview = if (mixed) Templates.MixedStructured.ref else null,
                parts = buildList {
                    add(PartSpec("gate", gate, gateRequired))
                    add(PartSpec("menu", menu, true))
                    add(PartSpec("receipt", receipt, true))
                    if (extra) add(PartSpec("extra", null, true))
                }.mapIndexed { position, spec ->
                    PlannedPart(
                        partKey = spec.key,
                        position = position,
                        origin = PartOrigin.PACKAGE,
                        prompt = PlannedTaskPrompt(
                            packageKey = PACKAGE_KEY,
                            promptKey = spec.key,
                            kind = "profile",
                            schema = 1,
                            payload = """{"stem":"${spec.key}"}""",
                            revision = revision,
                            resources = emptyList<PlannedPromptResource>()
                        ),
                        required = spec.required,
                        condition = spec.condition
                    )
                }
            )
        ),
        notices = emptyList(),
        packages = listOf(
            PlannedPackage(
                rootRelativePath = "dating",
                packageKey = PACKAGE_KEY,
                protocol = "listea.package/3",
                title = "Conditional",
                revision = revision,
                fingerprint = "fp-$revision",
                resources = emptyList()
            )
        )
    )

    private data class PartSpec(
        val key: String,
        val condition: PartCondition?,
        val required: Boolean
    )

    private fun gateIs(value: String) = PartCondition(
        sourcePartKey = "gate",
        field = CONDITION_FIELD_CHOICE,
        match = PartConditionMatch.Equals,
        values = listOf(value)
    )

    private suspend fun answer(
        taskId: String,
        partKey: String,
        choice: String,
        now: Long = 1500,
        completion: CompletionPolicy = CompletionPolicy.AllRequiredParts
    ): List<Long> = dao.submitReview(
        taskId = taskId,
        partKey = partKey,
        response = ReviewResponse.PrimaryReview(
            completed = true,
            events = listOf(ViewerEvent.Choice(optionId = choice, label = choice))
        ),
        template = REVIEW,
        context = null,
        completion = completion,
        completesTask = true,
        explicitCompletion = false,
        now = now
    )

    private suspend fun signOff(taskId: String): List<Long> = dao.submitReview(
        taskId = taskId,
        partKey = null,
        response = ReviewResponse.MediaReview(completed = true, selections = emptySet()),
        template = REVIEW,
        context = null,
        completion = CompletionPolicy.AllRequiredParts,
        completesTask = false,
        explicitCompletion = true,
        now = 1600
    )

    private suspend fun registerSource(): SourceEntity = dao.registerSource(
        rootUri = ROOT,
        relativePath = "dating",
        displayName = "Dating",
        importerKind = ImporterKind.PACKAGE,
        now = 1000
    )

    private fun taskId(): String = rawText("SELECT taskId FROM task_imports")

    private fun completed(taskId: String): Int =
        rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$taskId'")

    private fun completionSource(taskId: String): String =
        rawText("SELECT completionSource FROM task_state WHERE taskId = '$taskId'")

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
        const val PACKAGE_KEY = "conditional"
        val REVIEW = TemplateVersionRef(TemplateId("fixture.review"), 1)
    }
}
