package com.example.listea

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.listea.data.DecisionTarget
import com.example.listea.data.ExportRecordSummary
import com.example.listea.data.ListeaDatabase
import com.example.listea.data.ListsDao
import com.example.listea.data.ReviewItem
import com.example.listea.data.ScannedFile
import com.example.listea.domain.ExportChoice
import com.example.listea.domain.ExportKind
import com.example.listea.domain.encodeExportChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A List that completes while the user is in the middle of something else, driven through the
 * ViewModel and the database it writes to.
 *
 * **This is the behaviour the user asked for, and the fourth device pass is why.** Swiping a Task
 * that two Lists share completes the other List as well as the one being reviewed, and what used to
 * happen then was a dialog: *Send this webhook? / Demo / List completed · 2 items*, raised over the
 * review of a different List, at a moment nobody chose, asking permission for an export the user
 * had not asked for and could not place. In the user's words: *"行为和结果预期相匹配。用户在一个list里
 * 动作，不要反馈给用户另一个list的结果"*.
 *
 * So the answer to that dialog is now made for them, and it is the safe one — the same *Not now* the
 * dialog offers:
 *
 * - nothing is sent, and **nothing is shown**: a notice about a List the user is not looking at is
 *   the same interruption the dialog was;
 * - the payload is built and kept in the history, where the user will find it and can send it;
 * - the List is left saying *Not sent*, which is a third answer beside Success and Failed.
 *
 * Asserted at this level because that is where the three parts meet: the scope is decided from the
 * round bookkeeping while the rows are being written, the payload is built from the database
 * afterwards, and the record is written against a List nobody is looking at. A test of the pure
 * planner can see none of that, and the device pass that found it can only report it.
 *
 * Every fixture here is a real import over a real Todo, so the group that gets set aside is a group
 * the planner would genuinely have delivered — the test would pass vacuously if the round produced
 * nothing to send.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportQuietRoundTest {

    private lateinit var database: ListeaDatabase
    private lateinit var dao: ListsDao
    private val dispatcher = StandardTestDispatcher()

    private val application: Application
        get() = ApplicationProvider.getApplicationContext()

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

    /**
     * **The fix.** A List completing under another List's round is kept, not sent, and not shown.
     *
     * The reviewed List is open on paper rather than on screen — [ListsViewModel.onReviewStarted] is
     * the whole of what the round bookkeeping is, and it is called here exactly as the Review screen
     * calls it. What the user would have seen is the assertion that matters most: no confirmation,
     * because that is the dialog the device pass caught.
     */
    @Test
    fun `a List completing under another List's round is kept, not sent, and not shown`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.webhookTo(HOOK)

            // Two Lists over two Todos, so that completing one leaves the other alone.
            val (reviewed, _) = list("export test", "a", "x.jpg", ExportKind.TXT)
            val (finished, task) = list("Demo", "b", "y.jpg", ExportKind.WEBHOOK)
            viewModel.onReviewStarted(reviewed)
            advanceUntilIdle()

            viewModel.setItemCompleted(itemOf(task, "b", "y.jpg"), true)

            // The round is read back out of the history rather than polled for on the List, and the
            // history is the *only* thing this round produces: it is the record the user will find.
            // Waiting for it is also what makes the two assertions after it mean anything — see
            // [kept].
            val record = viewModel.kept().single()

            // Nothing was put to the user. This is the defect, stated as one assertion.
            assertNull(viewModel.webhookConfirmation.value)
            // Nothing was put on the wire either, which is the other half of *assume not now*.
            assertEquals(DeliveryStatus.NOT_SENT.name, dao.getList(finished)!!.lastDeliveryStatus)
            // And the payload is not lost: it is in the history, with B's own name on it, so the
            // user finds the round they never saw happen and can send it from there.
            assertEquals("Demo", record.listTitle)
            assertEquals(NoticeOutcome.DEFERRED.name, record.outcome)
            assertEquals(EVENT_LIST_COMPLETED, record.event)
            assertEquals(1, record.itemCount)
        }

    /**
     * The control, without which the assertion above proves nothing.
     *
     * The same completion with no round open *does* ask — the app's whole promise is that an
     * automatic delivery is confirmed first — so the quiet path is a branch and not the app having
     * stopped asking altogether. This is the case that has always reached the user, and it is
     * asserted to still reach them.
     */
    @Test
    fun `the same completion with no round open still asks`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.webhookTo(HOOK)

        val (_, task) = list("Demo", "b", "y.jpg", ExportKind.WEBHOOK)

        viewModel.setItemCompleted(itemOf(task, "b", "y.jpg"), true)

        val asked = viewModel.asked()
        assertEquals("Demo", asked.listTitle)
        // Named, so the user can tell what is being asked about. See `webhookTriggerLabel`.
        assertEquals("The List Demo just completed", asked.triggerLabel)
    }

    /**
     * A round that is reviewed *and* completes is not *somewhere else*, so nothing about it changes.
     *
     * The oldest rule in this area, and the one the new branch sits next to. This is the case the
     * device pass was originally about — swiping a List's last item completes that List — and the
     * danger of the fix is that *is a round open* starts answering for the round the user is in.
     *
     * With `webhookOnReviewExit` off, which is the default and what this asserts, the completion is
     * reported on its own behalf exactly as it always was: it asks, and it goes to the List the user
     * is looking at. With the setting on there is no dialog at all, because the round's narrower
     * `review.exited` report is the one worth having — a rule that predates this change and is not
     * touched by it.
     */
    @Test
    fun `a completed List whose own round is open still belongs to that round`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.webhookTo(HOOK)
        val (reviewed, task) = list("export test", "a", "x.jpg", ExportKind.WEBHOOK)

        viewModel.onReviewStarted(reviewed)
        // Drained rather than waited on: the round has to be open *before* the write below decides
        // which round the completion belongs to, and this is the one thing here that is a queued
        // task with nothing to observe.
        advanceUntilIdle()
        viewModel.setItemCompleted(itemOf(task, "a", "x.jpg"), true)

        // `webhookOnReviewExit` is off by default, so the completion is reported on its own behalf,
        // exactly as it was before this release — no regression hiding behind the new branch.
        assertEquals("export test", viewModel.asked().listTitle)
    }

    /**
     * A folder round ends, and the app stops answering for it.
     *
     * The half of this behaviour that no assertion about one round can see, and the one that fails
     * worst when it is wrong. Quick Review has two ways to end — its queue runs out, or the user
     * leaves — and the marker that says *somebody is reviewing something* is given back when the
     * **screen** goes, not when the report is sent. A round that never gave it back would not fail
     * loudly: it would leave every completion in the app afterwards kept quietly, for good, which
     * is a state the user could only reach by running one Quick Review to its end.
     *
     * Asserted as the pair it is: the same completion is kept while the round is open and asked for
     * once it has ended. The Compose half — that the composition really does pair the two — is not
     * reachable from here and is walked on the device.
     */
    @Test
    fun `a completion is kept only for as long as the folder round is open`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.webhookTo(HOOK)
        val (_, task) = list("Demo", "b", "y.jpg", ExportKind.WEBHOOK)

        // A Quick Review is on screen, so completing a List from anywhere else is nobody's business
        // but the app's: kept, not asked.
        viewModel.onFolderReviewStarted()
        advanceUntilIdle()
        viewModel.setItemCompleted(itemOf(task, "b", "y.jpg"), true)
        viewModel.kept().single()
        assertNull(viewModel.webhookConfirmation.value)

        // The round ends — the user left it, or ran it out; the ViewModel's contract is the same
        // either way. The List is put back to unfinished so the second completion is a real
        // transition, which is the same state the device walk would be in after a second swipe.
        viewModel.onFolderReviewEnded()
        viewModel.setItemCompleted(itemOf(task, "b", "y.jpg"), false)
        advanceUntilIdle()
        viewModel.setItemCompleted(itemOf(task, "b", "y.jpg"), true)

        assertEquals("Demo", viewModel.asked().listTitle)
    }

    // --- reaching the states above ---------------------------------------------------------------

    /**
     * A Todo over one file, imported, and a List over the Task it produced.
     *
     * The kind is on the **Todo** and not on the List, which is the model: a List inherits the
     * mechanism from the Todos its Tasks came from, and a fixture that configured the List instead
     * would never reach `planExports`.
     *
     * The List's own switch — *export when this list is completed* — is on, because that is the
     * gate in front of everything here and a fixture with it off would be testing the switch.
     */
    private suspend fun list(
        title: String,
        folder: String,
        file: String,
        kind: ExportKind
    ): Pair<Long, String> {
        val source = dao.registerSource(
            rootUri = ROOT,
            relativePath = folder,
            displayName = folder,
            importerKind = ImporterKind.FILE_AS_TASK,
            exportKind = encodeExportChoice(ExportChoice.By(kind)),
            now = 1000
        )
        dao.runImport(
            source.id,
            folder,
            plan(
                ImporterKind.FILE_AS_TASK,
                folder,
                FolderSnapshot(listOf(scanned(folder, file)), emptyList())
            ),
            2000
        )
        val taskId = dao.getCatalogTasks(source.id).single().taskId
        return dao.createListFromTasks(title, listOf(taskId), true, 3000) to taskId
    }

    /** The item a swipe or a checkbox hands the write, which only ever needs its Task. */
    private fun itemOf(taskId: String, folder: String, file: String): ReviewItem {
        val path = childRelativePath(folder, file)
        return ReviewItem(
            id = 0L,
            taskId = taskId,
            publicId = "row-$taskId",
            title = file,
            sourceUri = "$ROOT/$path",
            relativePath = path,
            sourceMissing = false,
            target = DecisionTarget.File(rootUri = ROOT, relativePath = path)
        )
    }

    /** Names are relative to the import root; `relativePath` is what the plan reads them against. */
    private fun scanned(importRoot: String, relativePath: String) = ScannedFile(
        name = relativePath.substringAfterLast('/'),
        relativePath = relativePath,
        uri = "$ROOT/${childRelativePath(importRoot, relativePath)}"
    )

    /**
     * A URL and the switch, waited for rather than assumed.
     *
     * The store is a DataStore, so the write lands on its own coroutine and `advanceUntilIdle` has
     * nothing to do with it — the same reason `ConsentGateViewModelTest` waits on the flow. Without
     * this the delivery reads an empty URL, reports *there is nowhere for this to go*, and the
     * test passes or fails for a reason that has nothing to do with what it is asking.
     */
    private suspend fun ListsViewModel.webhookTo(url: String) {
        setDefaultWebhookEnabled(true)
        setDefaultWebhookUrl(url)
        settings.first { it.defaultWebhookUrl == url && it.defaultWebhookEnabled }
    }

    /**
     * The confirmation the user was asked, waited for rather than assumed.
     *
     * `advanceUntilIdle()` is not enough to assert on a delivery and the difference is the whole
     * reason this helper exists — the same trap [ConsentGateViewModelTest.settled] documents. It
     * runs what is queued and returns, while a Room suspend query resumes from Room's own executor:
     * at that moment the queue is empty, so the assertion runs against a ViewModel that has not
     * finished thinking and reads a null that has nothing to do with the code under test.
     *
     * Waiting on the flow keeps this coroutine suspended until the ViewModel has actually reached
     * the decision being tested, which is what makes a *null* confirmation an assertion rather than
     * a race: by the time the record below exists, the dialog would already have been raised.
     */
    private suspend fun ListsViewModel.asked(): WebhookConfirmation =
        webhookConfirmation.first { it != null }!!

    /**
     * The rounds the app kept for the user, waited for the same way and for the same reason.
     *
     * The history is the only place a kept round is ever visible, so it is both what the user reads
     * and the only signal that the quiet path ran at all.
     */
    private suspend fun ListsViewModel.kept(): List<ExportRecordSummary> =
        webhookHistory.first { it.isNotEmpty() }

    private fun viewModel() = ListsViewModel(application, dao, FakeFolderSource(), dispatcher)

    private companion object {
        const val ROOT = "content://root"

        /** Nowhere real: nothing in this file delivers, and a test that did would not reach here. */
        const val HOOK = "https://example.invalid/hook"
    }
}
