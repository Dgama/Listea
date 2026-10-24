package com.example.listea

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.data.AddOnStore
import com.example.listea.data.CatalogRow
import com.example.listea.data.DecisionTarget
import com.example.listea.data.ListeaDatabase
import com.example.listea.data.ListsDao
import com.example.listea.data.ReviewItem
import com.example.listea.data.TemplateStore
import com.example.listea.domain.CommitPolicy
import com.example.listea.domain.PlannedPackageResource
import com.example.listea.domain.PlannedPromptResource
import com.example.listea.domain.ReviewContext
import com.example.listea.domain.ReviewResponse
import com.example.listea.domain.StoredTemplateVersion
import com.example.listea.domain.Templates
import com.example.listea.domain.ViewerEvent
import com.example.listea.domain.ViewerEventType
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
 * The submission boundary for a structured Prompt.
 *
 * A Prompt is answered by one choice, and the boundary is where that is made a fact of the record:
 * the option must be one the stored body declares, the revision must be the one the review opened,
 * required content must be present, and a template that is not revisable may be answered once. Each
 * refusal writes neither an event nor a completion and reports `accepted = false` to the surface,
 * which is what keeps a refused tap on screen instead of advancing.
 *
 * The committed value is a [ReviewResponse.PrimaryReview] holding the choice exactly as the Viewer
 * emitted it: the id is identity, the label is the snapshot a reader can still understand after the
 * body has been revised away.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptSubmissionTest {

    private lateinit var database: ListeaDatabase
    private lateinit var dao: ListsDao
    private val dispatcher = StandardTestDispatcher()

    private val application: Application
        get() = ApplicationProvider.getApplicationContext()

    private val like = ViewerEvent.Choice(optionId = "like", label = "Like")

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
    fun `a declared choice commits as one primary review`() = runTest(dispatcher) {
        val taskId = importedPrompt()

        val accepted = submit(itemOf(taskId), Templates.StructuredSingleChoice, listOf(like))

        assertTrue(accepted)
        assertEquals(1, rawInt("SELECT COUNT(*) FROM review_events WHERE taskId = '$taskId'"))
        assertEquals(
            1,
            rawInt(
                "SELECT COUNT(*) FROM review_event_answers a " +
                    "JOIN review_events r ON r.id = a.reviewEventId WHERE r.taskId = '$taskId'"
            )
        )
        assertEquals(1, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$taskId'"))
        assertEquals(
            ReviewResponse.PrimaryReview(completed = true, events = listOf(like)),
            ReviewResponse.parse(
                rawText("SELECT response FROM review_events WHERE taskId = '$taskId'")
            )
        )
    }

    @Test
    fun `a commit with no choice writes nothing and reports refusal`() = runTest(dispatcher) {
        val taskId = importedPrompt()

        val accepted = submit(itemOf(taskId), Templates.StructuredSingleChoice, emptyList())

        assertFalse(accepted)
        assertEquals(0, rawInt("SELECT COUNT(*) FROM review_events WHERE taskId = '$taskId'"))
        assertEquals(0, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$taskId'"))
    }

    @Test
    fun `a body cannot be answered with two choices`() = runTest(dispatcher) {
        val taskId = importedPrompt()

        val accepted = submit(
            itemOf(taskId),
            Templates.StructuredSingleChoice,
            listOf(like, ViewerEvent.Choice(optionId = "pass", label = "Pass"))
        )

        assertFalse(accepted)
        assertEquals(0, rawInt("SELECT COUNT(*) FROM review_events WHERE taskId = '$taskId'"))
    }

    @Test
    fun `an option the stored body does not declare is refused`() = runTest(dispatcher) {
        val taskId = importedPrompt()

        val accepted = submit(
            itemOf(taskId),
            Templates.StructuredSingleChoice,
            listOf(ViewerEvent.Choice(optionId = "superlike", label = "Super like"))
        )

        assertFalse(accepted)
        assertEquals(0, rawInt("SELECT COUNT(*) FROM review_events WHERE taskId = '$taskId'"))
    }

    @Test
    fun `a stale label for a declared id is refused`() = runTest(dispatcher) {
        val taskId = importedPrompt()

        val accepted = submit(
            itemOf(taskId),
            Templates.StructuredSingleChoice,
            listOf(ViewerEvent.Choice(optionId = "like", label = "Love it"))
        )

        assertFalse(accepted)
        assertEquals(0, rawInt("SELECT COUNT(*) FROM review_events WHERE taskId = '$taskId'"))
    }

    @Test
    fun `a body revised under an open review is refused`() = runTest(dispatcher) {
        val taskId = importedPrompt()

        val stale = itemOf(taskId).copy(payloadRevision = 2)
        val accepted = submit(stale, Templates.StructuredSingleChoice, listOf(like))

        assertFalse(accepted)
        assertEquals(0, rawInt("SELECT COUNT(*) FROM review_events WHERE taskId = '$taskId'"))
    }

    @Test
    fun `a missing required Resource refuses the judgment`() = runTest(dispatcher) {
        val taskId = importedPrompt(resourcePresent = false)

        val accepted = submit(itemOf(taskId), Templates.StructuredSingleChoice, listOf(like))

        assertFalse(accepted)
        assertEquals(0, rawInt("SELECT COUNT(*) FROM review_events WHERE taskId = '$taskId'"))
    }

    @Test
    fun `a non-revisable template answers once`() = runTest(dispatcher) {
        val taskId = importedPrompt()
        val once = Templates.StructuredSingleChoice.copy(
            taskReview = Templates.StructuredSingleChoice.taskReview!!.copy(revisable = false)
        )

        assertTrue(submit(itemOf(taskId), once, listOf(like)))
        assertFalse(submit(itemOf(taskId), once, listOf(like)))

        assertEquals(1, rawInt("SELECT COUNT(*) FROM review_events WHERE taskId = '$taskId'"))
    }

    @Test
    fun `a revisable template records the newer answer`() = runTest(dispatcher) {
        val taskId = importedPrompt()
        val pass = ViewerEvent.Choice(optionId = "pass", label = "Pass")

        assertTrue(submit(itemOf(taskId), Templates.StructuredSingleChoice, listOf(like)))
        assertTrue(submit(itemOf(taskId), Templates.StructuredSingleChoice, listOf(pass)))

        assertEquals(2, rawInt("SELECT COUNT(*) FROM review_events WHERE taskId = '$taskId'"))
        assertEquals(
            ReviewResponse.PrimaryReview(completed = true, events = listOf(pass)),
            ReviewResponse.parse(
                rawText(
                    "SELECT response FROM review_events WHERE taskId = '$taskId' " +
                        "ORDER BY at DESC, id DESC LIMIT 1"
                )
            )
        )
    }

    @Test
    fun `a template that catches no choice cannot answer a Prompt`() = runTest(dispatcher) {
        val taskId = importedPrompt()
        val silent = Templates.StructuredSingleChoice.copy(
            taskReview = Templates.StructuredSingleChoice.taskReview!!.copy(
                accepts = emptySet()
            )
        )

        assertFalse(submit(itemOf(taskId), silent, listOf(like)))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM review_events WHERE taskId = '$taskId'"))
    }

    @Test
    fun `the commit policy must be one the choice can make`() = runTest(dispatcher) {
        val taskId = importedPrompt()
        // A control-committed template is allowed to submit a choice — the control was pressed.
        val control = Templates.StructuredSingleChoice.copy(
            taskReview = Templates.StructuredSingleChoice.taskReview!!.copy(
                commit = CommitPolicy.ConfirmControl("submit")
            )
        )
        // An Immediate template naming a different intent is not.
        val mismatched = Templates.StructuredSingleChoice.copy(
            taskReview = Templates.StructuredSingleChoice.taskReview!!.copy(
                commit = CommitPolicy.Immediate(ViewerEventType.IMAGE_POINT)
            )
        )

        assertTrue(submit(itemOf(taskId), control, listOf(like)))
        assertFalse(submit(itemOf(taskId), mismatched, listOf(like)))
    }

    // --- reaching the state above ----------------------------------------------------------------

    private suspend fun submit(

        item: ReviewItem,
        template: StoredTemplateVersion,
        events: List<ViewerEvent>
    ): Boolean {
        var accepted = false
        viewModel()
            .submitItemReview(item, terms, template, events) { accepted = it }
            .join()
        return accepted
    }

    /** One Package holding one Prompt, imported as an import run would leave it. */
    private suspend fun importedPrompt(resourcePresent: Boolean = true): String {
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
                        groupPath = catalogKey(PACKAGE_KEY, PROMPT_KEY),
                        displayName = PROMPT_KEY,
                        assets = emptyList(),
                        prompt = PlannedTaskPrompt(
                            packageKey = PACKAGE_KEY,
                            promptKey = PROMPT_KEY,
                            kind = "profile",
                            schema = 1,
                            payload = PAYLOAD,
                            revision = 1,
                            resources = listOf(PlannedPromptResource("photo", required = true))
                        )
                    )
                ),
                notices = emptyList(),
                packages = listOf(
                    PlannedPackage(
                        rootRelativePath = "dating",
                        packageKey = PACKAGE_KEY,
                        protocol = "listea.package/1",
                        title = "Dating demo",
                        revision = 1,
                        fingerprint = "fp-1",
                        resources = listOf(
                            PlannedPackageResource(
                                resourceKey = "photo",
                                kind = "image",
                                member = "media/photo.png",
                                present = resourcePresent
                            )
                        )
                    )
                )
            ),
            1000
        )
        return dao.getCatalogTasks(source.id).single().taskId
    }

    /** The item a review surface hands the write, with the envelope the catalog resolved. */
    private suspend fun itemOf(taskId: String): ReviewItem {
        val row = catalogRow(taskId)
        return ReviewItem(
            id = 0L,
            taskId = taskId,
            publicId = "",
            title = row.displayName,
            sourceUri = null,
            relativePath = row.groupPath,
            sourceMissing = false,
            target = DecisionTarget.File(rootUri = ROOT, relativePath = row.groupPath),
            packageKey = row.packageKey,
            promptKey = row.promptKey,
            payloadKind = row.payloadKind,
            payloadSchema = row.payloadSchema,
            payloadRevision = row.payloadRevision
        )
    }

    private suspend fun catalogRow(taskId: String): CatalogRow {
        val sourceId = rawText("SELECT sourceId FROM task_imports WHERE taskId = '$taskId'")
        return dao.getCatalogTasks(sourceId).single { it.taskId == taskId }
    }

    private fun viewModel() = ListsViewModel(
        application,
        dao,
        FakeFolderSource(),
        dispatcher,
        TemplateStore(database),
        AddOnStore(database)
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
        const val PACKAGE_KEY = "dating-demo"
        const val PROMPT_KEY = "alice"
        const val PAYLOAD =
            """{"options":[{"id":"like","label":"Like"},{"id":"pass","label":"Pass"}],""" +
                """"profile":{"age":29,"bio":"Likes long walks","name":"Alice"},"stem":"Meet Alice?"}"""
    }
}
