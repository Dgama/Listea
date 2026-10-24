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
import com.example.listea.data.ScannedFile
import com.example.listea.data.TemplateStore
import com.example.listea.domain.ReviewContext
import com.example.listea.domain.ReviewResponse
import com.example.listea.domain.Templates
import com.example.listea.domain.ViewerEvent
import com.example.listea.domain.parse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a committed judgment writes, and what it refuses to write.
 *
 * The two templates named here are the compatibility pair v0.8.1 exists to prove: the rapid review
 * (Headless) catches no events and records the fold of the pairs, exactly as every release before
 * this one did; the sample review catches a point and records it under a new encoding. Both reach
 * the same write, and the difference between them is a stored definition rather than a branch in
 * the surface.
 *
 * The refusals are at the boundary the caller crosses, not in the button: an event-accepting review
 * handed nothing would record a judgment nobody made, and a Task whose picture has gone is not
 * evidence of anything. Neither writes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewSubmissionTest {

    private lateinit var database: ListeaDatabase
    private lateinit var dao: ListsDao
    private val dispatcher = StandardTestDispatcher()

    private val application: Application
        get() = ApplicationProvider.getApplicationContext()

    private val terms = ReviewTerms(
        context = ReviewContext.InFolder(ROOT, ""),
        template = Templates.MediaRapidReview.ref
    )

    private val point = ViewerEvent.ImagePoint(0.25, 0.75, "#AABBCC", "a.jpg")

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
    fun `a point is written as one event and the state it committed`() = runTest(dispatcher) {
        val taskId = importedTask("a.jpg")

        viewModel()
            .submitItemReview(itemOf(taskId), terms, Templates.ImageSampleReview, listOf(point))
            .join()

        assertEquals(1, rawInt("SELECT COUNT(*) FROM review_events"))
        assertEquals(1, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$taskId'"))

        // The repository names the authored reference, and the response carries its own encoding.
        assertEquals(
            Templates.ImageSampleReview.ref.templateId.value,
            eventText("SELECT templateId FROM review_events WHERE taskId = '$taskId'")
        )
        assertEquals(1, rawInt("SELECT templateVersion FROM review_events WHERE taskId = '$taskId'"))

        val stored = eventText("SELECT response FROM review_events WHERE taskId = '$taskId'")
        assertEquals(
            ReviewResponse.PrimaryReview(completed = true, events = listOf(point)),
            ReviewResponse.parse(stored)
        )
        // The display reads the same row the write put there.
        assertEquals(stored, dao.observeLatestResponse(taskId).first())
    }

    @Test
    fun `an event-accepting review commits nothing without an event`() = runTest(dispatcher) {
        val taskId = importedTask("a.jpg")

        viewModel()
            .submitItemReview(itemOf(taskId), terms, Templates.ImageSampleReview, emptyList())
            .join()

        assertEquals(0, rawInt("SELECT COUNT(*) FROM review_events"))
        assertEquals(0, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$taskId'"))
    }

    @Test
    fun `a Task whose picture has gone writes neither event nor state`() = runTest(dispatcher) {
        val taskId = importedTask("a.jpg")
        dao.markFilesDeleted(ROOT, listOf("a.jpg"))

        viewModel()
            .submitItemReview(itemOf(taskId), terms, Templates.ImageSampleReview, listOf(point))
            .join()

        assertEquals(0, rawInt("SELECT COUNT(*) FROM review_events"))
        assertEquals(0, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$taskId'"))
    }

    @Test
    fun `the rapid review still records the legacy form`() = runTest(dispatcher) {
        // Headless is a stored definition now, but its judgments must stay byte-identical to the
        // ones every earlier release wrote — a reader that moved with the writer would make the
        // existing history unreadable.
        val taskId = importedTask("a.jpg")

        viewModel()
            .submitItemReview(itemOf(taskId), terms, Templates.MediaRapidReviewStored, emptyList())
            .join()

        assertEquals(1, rawInt("SELECT COUNT(*) FROM review_events"))
        assertEquals(1, rawInt("SELECT isCompleted FROM task_state WHERE taskId = '$taskId'"))
        assertEquals(
            ReviewResponse.MediaReview(completed = true, selections = emptySet()),
            ReviewResponse.parse(
                eventText("SELECT response FROM review_events WHERE taskId = '$taskId'")
            )
        )
    }

    // --- reaching the states above --------------------------------------------------------------

    /** One imported Task whose single Asset is [file], as an import run would leave it. */
    private suspend fun importedTask(file: String): String {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = "",
            displayName = "Photos",
            importerKind = ImporterKind.FILE_AS_TASK,
            now = 0
        )
        dao.runImport(
            source.id,
            "",
            plan(
                ImporterKind.FILE_AS_TASK,
                "",
                FolderSnapshot(
                    listOf(ScannedFile(name = file, relativePath = file, uri = "$ROOT/$file")),
                    emptyList()
                )
            ),
            1000
        )
        return dao.getCatalogTasks(source.id).single().taskId
    }

    /** The item a review surface hands the write; only its Task and position matter here. */
    private fun itemOf(taskId: String) = ReviewItem(
        id = 0L,
        taskId = taskId,
        publicId = "row-$taskId",
        title = "a.jpg",
        sourceUri = "$ROOT/a.jpg",
        relativePath = "a.jpg",
        sourceMissing = false,
        target = DecisionTarget.File(rootUri = ROOT, relativePath = "a.jpg")
    )

    private fun viewModel() = ListsViewModel(
        application,
        dao,
        // Nothing here scans; the folder source exists only so the ViewModel can be built.
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

    private fun eventText(sql: String): String =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            assertTrue("no row for: $sql", cursor.moveToFirst())
            cursor.getString(0)
        }

    private companion object {
        const val ROOT = "content://root"
    }
}
