package com.example.listea.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.domain.ListId
import com.example.listea.domain.ReviewContext
import com.example.listea.domain.ReviewResponse
import com.example.listea.domain.Templates
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The fork §5 turns on: a judgment is recorded, an edit is not.
 *
 * `review_events` is what separates "somebody was shown this Task and committed a response" from
 * "the completion field moved". Every way the field can move is exercised here against the same
 * assertion — how many events exist — because the failure this pins is not a wrong value but a
 * *missing distinction*: an app that appends an event for a checkbox claims somebody reviewed work
 * they only tidied, and one that appends for a swipe claims a judgment nobody made.
 *
 * Every write below is addressed by the Task's id, as production writes are: the Task was resolved
 * long before the surface showed it, so none of these paths can mint a Task the way the
 * file-addressed path they replaced once could.
 *
 * The two halves are deliberately tested together and from the same fixture. A test that only
 * proved submissions write events would pass on an implementation that wrote them everywhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewSubmissionTest {

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

    // --- what a submission records -----------------------------------------------------------------

    @Test
    fun `a submission moves the state and records the judgment together`() = runTest {
        val listId = folderList(folderPath = "", files = listOf("a.jpg"))
        registerFiles("a.jpg")
        val taskId = dao.getItems(listId).single().taskId

        val transitioned = dao.submitReview(
            taskId = taskId,
            response = media(completed = true),
            template = Templates.MediaRapidReview.ref,
            context = ReviewContext.InFolder(ROOT, ""),
            now = 2000
        )

        // The state, so every screen that asks about the file agrees it is done...
        assertEquals(1, rawInt("SELECT isCompleted FROM task_state"))
        // ...and the judgment, which is a different fact and is the only thing this path adds.
        assertEquals(1, rawInt("SELECT COUNT(*) FROM review_events"))
        assertEquals("submitted", rawString("SELECT kind FROM review_events"))
        assertEquals(
            Templates.MediaRapidReview.ref.templateId.value,
            rawString("SELECT templateId FROM review_events")
        )
        assertEquals(
            Templates.MediaRapidReview.ref.version,
            rawInt("SELECT templateVersion FROM review_events")
        )
        // One file in this fixture, so the event is about the only Task there is.
        assertEquals(
            rawString("SELECT id FROM tasks"),
            rawString("SELECT taskId FROM review_events")
        )
        // The completion transition is still reported, which is what the caller fires a webhook on.
        assertEquals(listOf(listId), transitioned)
    }

    @Test
    fun `the event says what was committed, not only that it was completed`() = runTest {
        val listId = folderList(folderPath = "", files = listOf("a.jpg"))
        registerFiles("a.jpg")
        val taskId = dao.getItems(listId).single().taskId
        dao.setAssetAction(taskId, fileAsset("a.jpg"), ItemAction.Favourite, enabled = true, now = 1500)

        dao.submitReview(
            taskId = taskId,
            // What the review surface was showing when the person committed.
            response = media(
                completed = true,
                selections = setOf("custom1", Templates.FAVOURITE_BUTTON_ID)
            ),
            template = Templates.MediaRapidReview.ref,
            context = ReviewContext.InFolder(ROOT, ""),
            now = 2000
        )

        // The snapshot is the point of keeping history at all: a template's marks ride along with
        // the next judgment about the Task, so a record that held only "completed" could not say
        // what somebody was actually looking at when they said so. One set of ids, sorted, so `★`
        // and the configured tag are spelled the same way the item row spells them.
        assertEquals(
            "completed=true;selections=custom1,favorite",
            rawString("SELECT response FROM review_events")
        )
    }

    @Test
    fun `the state follows the response rather than assuming every submission completes`() = runTest {
        val listId = folderList(folderPath = "", files = listOf("a.jpg"))
        registerFiles("a.jpg")
        val taskId = dao.getItems(listId).single().taskId

        dao.submitReview(
            taskId = taskId,
            // What a template that offers something other than "deal with this" would commit, and
            // the reason the response leads the write: a submission is a judgment being recorded,
            // and whether that judgment leaves the Task complete is the response's to say. Hard-
            // coding completion here would make every future response type mean the same thing.
            response = media(completed = false),
            template = Templates.MediaRapidReview.ref,
            context = ReviewContext.InFolder(ROOT, ""),
            now = 2000
        )

        assertEquals(0, rawInt("SELECT isCompleted FROM task_state"))
        // Still recorded: somebody was shown the Task and committed an answer to it.
        assertEquals(1, rawInt("SELECT COUNT(*) FROM review_events"))
        assertEquals(
            "completed=false;selections=",
            rawString("SELECT response FROM review_events")
        )
    }

    @Test
    fun `a submission from a list names the list it was conducted under`() = runTest {
        val listId = folderList(folderPath = "", files = listOf("a.jpg"))
        registerFiles("a.jpg")
        val taskId = dao.getItems(listId).single().taskId

        dao.submitReview(
            taskId = taskId,
            response = media(completed = true),
            template = Templates.MediaRapidReview.ref,
            context = ReviewContext.InList(ListId(listId)),
            now = 2000
        )

        assertEquals(ReviewContextKind.LIST, rawString("SELECT contextKind FROM review_events"))
        assertEquals(listId.toString(), rawString("SELECT contextRef FROM review_events"))
    }

    @Test
    fun `a submission from a folder names the folder, and belongs to no list`() = runTest {
        registerFiles("holiday/a.jpg")
        val taskId = dao.taskIdOf(ROOT, "holiday/a.jpg")

        dao.submitReview(
            taskId = taskId,
            response = media(completed = true),
            template = Templates.MediaRapidReview.ref,
            context = ReviewContext.InFolder(ROOT, "holiday"),
            now = 2000
        )

        // The root is not repeated here: the event's Task already points at an Asset carrying the
        // pair, so the grant is one join away and a second copy could only disagree.
        assertEquals(ReviewContextKind.FOLDER, rawString("SELECT contextKind FROM review_events"))
        assertEquals("holiday", rawString("SELECT contextRef FROM review_events"))
        assertEquals(1, rawInt("SELECT isCompleted FROM task_state"))
        assertEquals(1, rawInt("SELECT COUNT(*) FROM review_events"))
    }

    @Test
    fun `a manual item's submission records it against that item's own Task`() = runTest {
        val listId = manualList()
        database.writeLegacyManualItem(listId, "Buy milk", now = 1000)
        val milk = dao.getItems(listId).single()

        dao.submitReview(
            taskId = milk.taskId,
            response = media(completed = true),
            template = Templates.MediaRapidReview.ref,
            context = ReviewContext.InList(ListId(listId)),
            now = 2000
        )

        assertEquals(true, dao.getItems(listId).single().isCompleted)
        assertEquals(1, rawInt("SELECT COUNT(*) FROM review_events"))
        assertEquals(
            rawString("SELECT taskId FROM list_items"),
            rawString("SELECT taskId FROM review_events")
        )
        assertNotNull(dao.getList(listId)?.completedAt)
    }

    // --- what does not record one ------------------------------------------------------------------

    @Test
    fun `a checkbox on the list's own page moves the state and records nothing`() = runTest {
        val listId = folderList(folderPath = "", files = listOf("a.jpg"))
        registerFiles("a.jpg")
        val taskId = dao.getItems(listId).single().taskId

        dao.setTaskCompleted(taskId, completed = true, now = 2000)

        assertEquals(1, rawInt("SELECT isCompleted FROM task_state"))
        assertEquals(true, dao.getItems(listId).single().isCompleted)
        // Somebody tidying a list has reviewed nothing, and an event here would count as a
        // judgment they never made.
        assertEquals(0, rawInt("SELECT COUNT(*) FROM review_events"))
    }

    @Test
    fun `a manual item's checkbox records nothing either`() = runTest {
        val listId = manualList()
        database.writeLegacyManualItem(listId, "Buy milk", now = 1000)
        val milk = dao.getItems(listId).single()

        dao.setTaskCompleted(milk.taskId, completed = true, now = 2000)

        assertEquals(0, rawInt("SELECT COUNT(*) FROM review_events"))
    }

    @Test
    fun `unchecking records nothing, and takes back no event either`() = runTest {
        val listId = folderList(folderPath = "", files = listOf("a.jpg"))
        registerFiles("a.jpg")
        val taskId = dao.getItems(listId).single().taskId
        dao.submitReview(
            taskId = taskId,
            response = media(completed = true),
            template = Templates.MediaRapidReview.ref,
            context = ReviewContext.InFolder(ROOT, ""),
            now = 2000
        )

        dao.setTaskCompleted(taskId, completed = false, now = 3000)

        assertEquals(0, rawInt("SELECT isCompleted FROM task_state"))
        // Retracting a decision is not committing one, and the judgment that *was* committed
        // happened. A review count that moved when something was unchecked would be counting
        // current state and calling it history.
        assertEquals(1, rawInt("SELECT COUNT(*) FROM review_events"))
    }

    @Test
    fun `marking a favourite or an action records nothing`() = runTest {
        val listId = folderList(folderPath = "", files = listOf("a.jpg"))
        registerFiles("a.jpg")
        val taskId = dao.getItems(listId).single().taskId

        val assetId = fileAsset("a.jpg")
        dao.setAssetAction(taskId, assetId, ItemAction.Favourite, enabled = true, now = 2000)
        dao.setAssetAction(taskId, assetId, ItemAction("custom1"), enabled = true, now = 2100)

        // Both marks on the one set, in `encodeActionIds`' spelling: `★` is a button like any other
        // and is stored beside the tag rather than in a column of its own.
        assertEquals("custom1,favorite", rawString("SELECT selections FROM task_asset_state"))
        // A mark is metadata a submission carries a snapshot of, never a submission in itself.
        assertEquals(0, rawInt("SELECT COUNT(*) FROM review_events"))
    }

    // --- re-reviewing ------------------------------------------------------------------------------

    @Test
    fun `re-reviewing an already complete task appends, and leaves the state where it was`() = runTest {
        val listId = folderList(folderPath = "", files = listOf("a.jpg"))
        registerFiles("a.jpg")
        val taskId = dao.getItems(listId).single().taskId
        dao.setAssetAction(taskId, fileAsset("a.jpg"), ItemAction.Favourite, enabled = true, now = 1500)
        dao.submitReview(
            taskId = taskId,
            response = media(
                completed = true,
                selections = setOf(Templates.FAVOURITE_BUTTON_ID)
            ),
            template = Templates.MediaRapidReview.ref,
            context = ReviewContext.InFolder(ROOT, ""),
            now = 2000
        )

        val second = dao.submitReview(
            taskId = taskId,
            response = media(
                completed = true,
                selections = setOf(Templates.FAVOURITE_BUTTON_ID)
            ),
            template = Templates.MediaRapidReview.ref,
            context = ReviewContext.InFolder(ROOT, ""),
            now = 3000
        )

        // Two judgments, because two were made — a Task looked at twice really was reviewed twice.
        assertEquals(2, rawInt("SELECT COUNT(*) FROM review_events"))
        // And the state did not move for the second one, which is the whole reason these are two
        // facts: a review count derived from completions could never have seen the second.
        assertEquals(1, rawInt("SELECT isCompleted FROM task_state"))
        assertEquals("favorite", rawString("SELECT selections FROM task_asset_state"))
        // Re-reviewing a list that was already complete is not a second completion, so nothing
        // downstream is told to deliver again.
        assertEquals(emptyList<Long>(), second)
    }

    // --- the write cannot half-happen ---------------------------------------------------------------

    @Test
    fun `a submission about a row that has gone writes no event and no state`() = runTest {
        val listId = manualList()
        val taskId = database.writeLegacyManualItem(listId, "Buy milk", now = 1000)
        val milk = dao.getItems(listId).single()
        dao.removeItem(listId, milk.id, now = 1500)

        val transitioned = dao.submitReview(
            taskId = taskId,
            response = media(completed = true),
            template = Templates.MediaRapidReview.ref,
            context = ReviewContext.InList(ListId(listId)),
            now = 2000
        )

        // An event naming a Task nobody can reach would be a judgment filed against nothing, and
        // it would also count: a submission that never happened must leave no trace of having.
        assertEquals(0, rawInt("SELECT COUNT(*) FROM review_events"))
        assertEquals(emptyList<Long>(), transitioned)
        // Taking the row took its Task with it. This is the one case where a Task does not outlive
        // its membership, and it is the case that has no file for it to outlive it *for* — a
        // source-backed Task is left alone by the same path, which the cleanup tests pin.
        assertEquals(0, rawInt("SELECT COUNT(*) FROM tasks WHERE id = '$taskId'"))
        assertEquals(0, rawInt("SELECT COUNT(*) FROM task_state WHERE taskId = '$taskId'"))
    }

    // --- the snapshot contract ----------------------------------------------------------------------

    @Test
    fun `a submission snapshots the marks without mutating them`() = runTest {
        // The submission's own contract: `completed` is written to state, while the selection set
        // rides along as a snapshot of what was true at the moment of judgment. A submission that
        // wrote it back would turn a record of a judgment into a second way to make one — and a
        // caller that passes a snapshot disagreeing with current state must get the disagreement on
        // the record, not have it silently corrected.
        val listId = folderList(folderPath = "", files = listOf("a.jpg"))
        registerFiles("a.jpg")
        val taskId = dao.getItems(listId).single().taskId
        val assetId = fileAsset("a.jpg")
        dao.setAssetAction(taskId, assetId, ItemAction.Favourite, enabled = true, now = 1500)
        dao.setAssetAction(taskId, assetId, ItemAction("kept"), enabled = true, now = 1500)

        dao.submitReview(
            taskId = taskId,
            response = media(completed = true, selections = setOf("stale")),
            template = Templates.MediaRapidReview.ref,
            context = ReviewContext.InFolder(ROOT, ""),
            now = 2000
        )

        // The event holds exactly what was passed — the snapshot, not a recomputed state.
        assertEquals(
            "completed=true;selections=stale",
            rawString("SELECT response FROM review_events")
        )
        // The write moved completion and nothing else: the marks on the pair are untouched, both
        // the `★` and the tag the snapshot said nothing about.
        assertEquals(1, rawInt("SELECT isCompleted FROM task_state"))
        assertEquals("favorite,kept", rawString("SELECT selections FROM task_asset_state"))
    }

    // --- helpers -----------------------------------------------------------------------------------

    private fun media(
        completed: Boolean,
        selections: Set<String> = emptySet()
    ) = ReviewResponse.MediaReview(completed = completed, selections = selections)

    private suspend fun folderList(
        folderPath: String,
        files: List<String>,
        title: String = "Trip"
    ): Long = dao.createFolderList(
        title = title,
        rootUri = ROOT,
        relativePath = folderPath,
        files = files.map {
            ScannedFile(name = it, relativePath = it, uri = "$ROOT/$folderPath/$it")
        },
        replacedListIds = emptyList(),
        exportOnCompletion = false,
        now = 1000
    )

    private suspend fun manualList(): Long = dao.insertList(
        ListEntity(title = "Shopping", createdAt = 1000)
    )

    private suspend fun registerFiles(vararg rootRelativePaths: String) = dao.registerFiles(
        rootRelativePaths.map {
            FileReviewStateEntity(
                publicId = "FILE-$it",
                rootUri = ROOT,
                relativePath = it,
                updatedAt = 1000
            )
        }
    )

    /** The Asset the list made for one file: half the address a mark is written to. */
    private fun fileAsset(relativePath: String): String = rawString(
        "SELECT id FROM assets WHERE rootUri = '$ROOT' AND relativePath = '$relativePath'"
    )

    private fun rawInt(sql: String): Int =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun rawString(sql: String): String =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }

    private companion object {
        const val ROOT = "content://root"
    }
}
